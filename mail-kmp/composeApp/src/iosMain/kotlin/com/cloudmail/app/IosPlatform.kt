@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.cloudmail.app

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.cloudmail.db.MailDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import platform.UserNotifications.UNUserNotificationCenter
import platform.BackgroundTasks.BGTaskScheduler
import platform.posix.size_tVar

/** Keychain owns a random encryption/MAC key. SQLite contains only authenticated ciphertext. */
class IosPlatform : PlatformServices {
    override val persistentSecureStorage = true
    override val defaultServerUrl: String = NSBundle.mainBundle.objectForInfoDictionaryKey("CLOUD_MAIL_SERVER_URL") as? String ?: ""
    private val material: ByteArray by lazy {
        secret("cache-key-v1")?.let { encoded ->
            val bytes = NSData.create(base64EncodedString = encoded, options = 0u)?.bytesArray()
            check(bytes != null && bytes.size == 64) { "The local cache encryption key is invalid." }
            bytes
        }
            ?: secureRandom(64).also { putSecret("cache-key-v1", it.nsData().base64EncodedStringWithOptions(0u)) }
    }
    override fun secret(key: String): String? = memScoped {
        val query = keyQuery(key)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val output = alloc<CFTypeRefVar>()
        try {
            when (val status = SecItemCopyMatching(query, output.ptr)) {
                errSecItemNotFound -> null
                errSecSuccess -> {
                    val data = output.value as CFDataRef
                    try { val length = CFDataGetLength(data).toInt(); if (length == 0) "" else CFDataGetBytePtr(data)!!.readBytes(length).decodeToString() } finally { CFRelease(data) }
                }
                else -> error("Secure Keychain storage is unavailable ($status).")
            }
        } finally { CFRelease(query) }
    }
    override fun putSecret(key: String, value: String?) {
        if (key == "session" && value == null) {
            val old = secret("session")?.let { runCatching { MailJson.decodeFromString<Session>(it) }.getOrNull() }
            if (old != null) putSecret("notified-${secret("server")}#${old.userId}", null)
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier("com.cloudmail.app.refresh")
            UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()
            UNUserNotificationCenter.currentNotificationCenter().removeAllDeliveredNotifications()
        }
        val query = keyQuery(key)
        try {
            if (value == null) {
                val status = SecItemDelete(query); check(status == errSecSuccess || status == errSecItemNotFound) { "Unable to clear Keychain item." }
                if (key == "session") IosFiles.clearTemporaryFiles()
            } else {
                val bytes = value.encodeToByteArray()
                val data = (if (bytes.isEmpty()) byteArrayOf(0) else bytes).usePinned { CFDataCreate(kCFAllocatorDefault, it.addressOf(0).reinterpret(), bytes.size.toLong()) }!!
                val attributes = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
                try {
                    CFDictionarySetValue(attributes, kSecValueData, data)
                    CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    val updated = SecItemUpdate(query, attributes)
                    if (updated == errSecItemNotFound) {
                        CFDictionarySetValue(query, kSecValueData, data)
                        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                        check(SecItemAdd(query, null) == errSecSuccess) { "Unable to create secure Keychain item." }
                    } else check(updated == errSecSuccess) { "Unable to update secure Keychain item." }
                } finally { CFRelease(attributes); CFRelease(data) }
            }
        } finally { CFRelease(query) }
    }
    private fun keyQuery(key: String): CFMutableDictionaryRef {
        val query = CFDictionaryCreateMutable(kCFAllocatorDefault, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
        val service = CFStringCreateWithCString(kCFAllocatorDefault, "com.cloudmail.app", kCFStringEncodingUTF8)!!
        val account = CFStringCreateWithCString(kCFAllocatorDefault, key, kCFStringEncodingUTF8)!!
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, service); CFDictionarySetValue(query, kSecAttrAccount, account)
        CFRelease(service); CFRelease(account); return query
    }
    override fun encrypt(bytes: ByteArray): ByteArray {
        val iv = secureRandom(16)
        val ciphertext = crypt(bytes, iv, kCCEncrypt)
        val authenticated = byteArrayOf(1) + iv + ciphertext
        return authenticated + hmac(authenticated)
    }
    override fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size >= 65 && bytes[0] == 1.toByte()) { "Invalid encrypted cache." }
        val authenticated = bytes.copyOfRange(0, bytes.size - 32)
        val expected = hmac(authenticated); val received = bytes.copyOfRange(bytes.size - 32, bytes.size)
        var difference = 0; for (index in expected.indices) difference = difference or (expected[index].toInt() xor received[index].toInt())
        check(difference == 0) { "Encrypted cache authentication failed." }
        return crypt(authenticated.copyOfRange(17, authenticated.size), authenticated.copyOfRange(1, 17), kCCDecrypt)
    }
    private fun crypt(input: ByteArray, iv: ByteArray, operation: CCOperation): ByteArray = memScoped {
        val output = ByteArray(input.size + 16); val written = alloc<size_tVar>()
        val encryptionKey = material.copyOfRange(0, 32)
        val status = encryptionKey.usePinned { key -> iv.usePinned { nonce -> (if (input.isEmpty()) byteArrayOf(0) else input).usePinned { source -> output.usePinned { destination ->
            CCCrypt(operation, kCCAlgorithmAES, kCCOptionPKCS7Padding, key.addressOf(0), 32uL, nonce.addressOf(0), source.addressOf(0), input.size.toULong(), destination.addressOf(0), output.size.toULong(), written.ptr)
        } } } }
        check(status == kCCSuccess) { "Unable to process encrypted cache." }
        output.copyOf(written.value.toInt())
    }
    private fun hmac(bytes: ByteArray): ByteArray {
        val macKey = material.copyOfRange(32, 64); val digest = ByteArray(32)
        macKey.usePinned { key -> bytes.usePinned { data -> digest.usePinned { target -> CCHmac(kCCHmacAlgSHA256, key.addressOf(0), macKey.size.toULong(), data.addressOf(0), bytes.size.toULong(), target.addressOf(0)) } } }
        return digest
    }
    override fun httpClient() = HttpClient(Darwin) { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 } }
    override fun databaseDriver() = NativeSqliteDriver(MailDatabase.Schema, "cloudmail.db")
    override fun randomId() = NSUUID().UUIDString
    override suspend fun pickAttachments() = IosFiles.pick()
    override suspend fun saveAttachment(name: String, mimeType: String, bytes: ByteArray, action: String) = IosFiles.save(name, mimeType, bytes, action)
    override fun configureSync(minutes: Int) { NSUserDefaults.standardUserDefaults.setInteger(minutes.coerceIn(15, 1440).toLong(), forKey = "sync-minutes") }
    override fun notifyMail(messages: List<Message>) = IosNotifications.show(this, messages)
}
internal fun secureRandom(count: Int): ByteArray = ByteArray(count).also { bytes -> bytes.usePinned { check(SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) == errSecSuccess) { "Secure randomness unavailable." } } }
internal fun NSData.bytesArray(): ByteArray = if (length == 0uL) ByteArray(0) else bytes!!.reinterpret<ByteVar>().readBytes(length.toInt())
internal fun ByteArray.nsData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
actual fun createPlatformServices(): PlatformServices = IosPlatform()
actual fun platformColorScheme(dark: Boolean, dynamic: Boolean): androidx.compose.material3.ColorScheme? = null
actual fun supportsHtmlRenderer(): Boolean = true
