package com.cloudmail.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.cloudmail.db.MailDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudMailApplication : Application() {
    override fun onCreate() { super.onCreate(); AndroidRuntime.context = applicationContext }
}
internal object AndroidRuntime {
    lateinit var context: Context
    @Volatile var foreground = false
    /** Serializes logout with temporary file creation and external URI handoff. */
    val sessionLock = Any()
}

/** AES-GCM key never leaves Android Keystore. Neither session nor cached mail is plaintext. */
class AndroidPlatform(private val context: Context = AndroidRuntime.context) : PlatformServices {
    override val persistentSecureStorage = true
    override val defaultServerUrl = BuildConfig.DEFAULT_SERVER_URL
    override val allowLocalHttp = BuildConfig.DEBUG && BuildConfig.ALLOW_LOCAL_HTTP
    private val preferences = context.getSharedPreferences("encrypted-vault", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        synchronized(AndroidPlatform::class.java) {
            val vault = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (vault.getKey("cloudmail-aes-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder("cloudmail-aes-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build()); generateKey()
            }
        }
    }
    private val codec = AndroidCipher { key }
    override fun encrypt(bytes: ByteArray): ByteArray = codec.encrypt(bytes)
    override fun decrypt(bytes: ByteArray): ByteArray = codec.decrypt(bytes)
    override fun secret(key: String): String? = preferences.getString(key, null)?.let { decrypt(Base64.decode(it, Base64.NO_WRAP)).decodeToString() }
    override fun putSecret(key: String, value: String?) = synchronized(AndroidRuntime.sessionLock) {
        if (key == "session" && value == null) {
            val old = secret("session")?.let { runCatching { MailJson.decodeFromString<Session>(it) }.getOrNull() }
            if (old != null) preferences.edit().remove("notified-${secret("server")}#${old.userId}").commit()
        }
        check(preferences.edit().apply { if (value == null) remove(key) else putString(key, Base64.encodeToString(encrypt(value.encodeToByteArray()), Base64.NO_WRAP)) }.commit()) { "Unable to save secure credentials." }
        if (key == "session") {
            if (value == null) { AndroidFiles.clearSensitiveOperations(); MailSyncWorker.cancel(context); context.getSystemService(android.app.NotificationManager::class.java).cancelAll(); File(context.cacheDir, "shared-attachments").deleteRecursively() }
            else MailSyncWorker.schedule(context, preferences.getInt("sync-minutes", 15).toLong())
        }
    }
    override fun randomId() = UUID.randomUUID().toString()
    override fun httpClient() = HttpClient(OkHttp) { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 }; engine { config { followRedirects(false); followSslRedirects(false) } } }
    override fun databaseDriver() = AndroidSqliteDriver(MailDatabase.Schema, context, "mail.db")
    override suspend fun pickAttachments(): List<OutgoingAttachment> = AndroidFiles.pick()
    override suspend fun saveAttachment(name: String, mimeType: String, bytes: ByteArray, action: String) {
        require(bytes.size <= 25 * 1024 * 1024) { "Attachment exceeds 25 MB." }
        if (action == "download") { AndroidFiles.save(name, mimeType, bytes); return }
        val session = secret("session") ?: error("Sign in before opening an attachment.")
        withContext(Dispatchers.IO) {
            val file = synchronized(AndroidRuntime.sessionLock) {
                check(secret("session") == session) { "Session ended. Open the attachment after signing in again." }
                val directory = File(context.cacheDir, "shared-attachments").apply { mkdirs() }
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60_000 }?.forEach { it.delete() }
                File(directory, "${randomId()}-${AndroidFiles.safeName(name)}").apply { writeBytes(bytes) }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
            withContext(Dispatchers.Main) {
                synchronized(AndroidRuntime.sessionLock) {
                    check(secret("session") == session) { file.delete(); "Session ended. Open the attachment after signing in again." }
                    val intent = if (action == "share") Intent(Intent.ACTION_SEND).apply { type = AndroidFiles.safeMime(mimeType); putExtra(Intent.EXTRA_STREAM, uri); clipData = android.content.ClipData.newRawUri("Attachment", uri) }
                    else Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, AndroidFiles.safeMime(mimeType)) }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val chooser = Intent.createChooser(intent, if (action == "share") "Share attachment" else "Open attachment").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                }
            }
        }
    }
    override fun configureSync(minutes: Int) {
        val clamped = minutes.coerceIn(15, 1440)
        if (secret("session") != null && preferences.getInt("sync-minutes", -1) != clamped) {
            preferences.edit().putInt("sync-minutes", clamped).apply(); MailSyncWorker.schedule(context, clamped.toLong())
        }
    }
    override fun notifyMail(messages: List<Message>) = MailNotifications.show(context, this, messages)
}

/** Testable wire envelope; the production key supplier above always resolves Android Keystore. */
internal class AndroidCipher(private val key: () -> SecretKey) {
    fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
    }
    fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Invalid encrypted cache." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size))
    }
}
actual fun createPlatformServices(): PlatformServices = AndroidPlatform()
actual fun platformColorScheme(dark: Boolean, dynamic: Boolean): androidx.compose.material3.ColorScheme? =
    if (!dynamic || Build.VERSION.SDK_INT < 31) null else if (dark) androidx.compose.material3.dynamicDarkColorScheme(AndroidRuntime.context) else androidx.compose.material3.dynamicLightColorScheme(AndroidRuntime.context)
actual fun supportsHtmlRenderer(): Boolean = true
