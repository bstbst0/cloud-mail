package com.cloudmail.app

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cloudmail.db.MailDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.awt.Desktop
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** OS keyring stores secrets. Machines without a keyring get an explicit, memory-only session. */
class DesktopPlatform : PlatformServices {
    private val memory = mutableMapOf<String, String>()
    private val root = File(System.getProperty("user.home"), ".cloud-mail")
    private val vault = DesktopVault(root)
    // Existing ciphertext is preserved if its key is unavailable. No key replacement.
    private val recoveredKey = if (vault.available) runCatching { vault.read("cache-key") }.getOrNull() else null
    override val defaultServerUrl: String get() = System.getProperty("cloudmail.defaultServerUrl", "")
    override val allowLocalHttp: Boolean get() = java.lang.Boolean.getBoolean("cloudmail.development") && java.lang.Boolean.getBoolean("cloudmail.allowLocalHttp")
    override val persistentSecureStorage = vault.available && (!File(root, "mail.db").exists() || recoveredKey != null)
    private val key: ByteArray by lazy {
        recoveredKey?.let { Base64.getDecoder().decode(it) } ?: ByteArray(32).also {
            SecureRandom().nextBytes(it); putSecret("cache-key", Base64.getEncoder().encodeToString(it))
        }
    }
    override fun secret(key: String) = memory[key] ?: if (persistentSecureStorage) vault.read(key) else null
    override fun putSecret(key: String, value: String?) {
        if (persistentSecureStorage) vault.write(key, value)
        if (value == null) memory.remove(key) else memory[key] = value
    }
    override fun encrypt(bytes: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }; val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); return iv + cipher.doFinal(bytes)
    }
    override fun decrypt(bytes: ByteArray): ByteArray { require(bytes.size >= 28) { "Invalid encrypted cache." }; val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0,12))); return cipher.doFinal(bytes.copyOfRange(12,bytes.size)) }
    override fun httpClient() = HttpClient(CIO) { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 30_000 } }
    override fun databaseDriver() = if (!persistentSecureStorage) JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MailDatabase.Schema.create(it) } else {
        root.mkdirs()
        runCatching { java.nio.file.Files.setPosixFilePermissions(root.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val file = File(root, "mail.db"); val fresh = !file.exists(); val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}"); if (fresh) MailDatabase.Schema.create(driver)
        runCatching { java.nio.file.Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        driver
    }
    override fun randomId() = UUID.randomUUID().toString()
    override suspend fun pickAttachments(): List<OutgoingAttachment> {
        val selected = withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFiles.toList() else emptyList()
        }
        require(selected.size <= 10) { "Choose up to 10 attachments." }
        return withContext(Dispatchers.IO) {
            var total = 0L
            selected.map { file ->
                val bytes = readDesktopAttachment(file)
                total += bytes.size
                require(total <= 20 * 1024 * 1024) { "Attachments must total at most 20 MB." }
                OutgoingAttachment(file.name, java.nio.file.Files.probeContentType(file.toPath()) ?: "application/octet-stream", Base64.getEncoder().encodeToString(bytes))
            }
        }
    }
    override suspend fun saveAttachment(name: String, mimeType: String, bytes: ByteArray, action: String) = withContext(Dispatchers.Swing) {
        val safe = File(name).name.replace(Regex("[\\\\/:\\p{Cntrl}]"), "_"); val chooser = JFileChooser().apply { selectedFile = File(safe) }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            if (file.exists() && javax.swing.JOptionPane.showConfirmDialog(null, "Replace ${file.name}?", "Replace file", javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) return@withContext
            require(bytes.size <= 25 * 1024 * 1024) { "Attachment exceeds 25 MB." }
            withContext(Dispatchers.IO) { file.writeBytes(bytes) }
            if (action == "open" && Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
            if (action == "share") {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(file.absolutePath), null)
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file.parentFile)
                javax.swing.JOptionPane.showMessageDialog(null, "File exported and its path copied. Choose Share in your file manager.", "Share attachment", javax.swing.JOptionPane.INFORMATION_MESSAGE)
            }
        }; Unit
    }
}
actual fun createPlatformServices(): PlatformServices = DesktopPlatform()
@Composable actual fun HtmlMessage(html: String, loadImages: Boolean, modifier: Modifier) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(desktopMailText(html), modifier.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())))
    }
}

actual fun platformColorScheme(dark: Boolean, dynamic: Boolean): androidx.compose.material3.ColorScheme? = null

actual fun supportsHtmlRenderer(): Boolean = false

/** Stream instead of trusting a size sampled before opening a user-selected file. */
internal fun readDesktopAttachment(file: File): ByteArray {
    require(file.isFile && file.length() <= 10 * 1024 * 1024) { "Choose a regular file smaller than 10 MB." }
    return file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 10 * 1024 * 1024) { "Each attachment must be smaller than 10 MB." }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

/** Native HTML parser decodes entities without an executable HTML engine. */
internal fun desktopMailText(html: String): String {
    val output = StringBuilder()
    var ignored = 0
    val blocked = setOf("script", "style", "head")
    val breaks = setOf("p", "div", "tr", "li", "blockquote", "pre", "h1", "h2", "h3", "h4")
    javax.swing.text.html.parser.ParserDelegator().parse(java.io.StringReader(html), object : javax.swing.text.html.HTMLEditorKit.ParserCallback() {
        override fun handleStartTag(tag: javax.swing.text.html.HTML.Tag, attributes: javax.swing.text.MutableAttributeSet, position: Int) {
            if (tag.toString() in blocked) ignored++
            if (ignored == 0 && tag.toString() in breaks && output.isNotEmpty()) output.append('\n')
        }
        override fun handleEndTag(tag: javax.swing.text.html.HTML.Tag, position: Int) {
            if (tag.toString() in blocked) ignored = (ignored - 1).coerceAtLeast(0)
            else if (ignored == 0 && tag.toString() in breaks) output.append('\n')
        }
        override fun handleSimpleTag(tag: javax.swing.text.html.HTML.Tag, attributes: javax.swing.text.MutableAttributeSet, position: Int) {
            if (ignored == 0 && tag.toString() in setOf("br", "hr")) output.append('\n')
            if (ignored == 0 && tag.toString() == "img") (attributes.getAttribute(javax.swing.text.html.HTML.Attribute.ALT) as? String)?.let { output.append("[$it]") }
        }
        override fun handleText(data: CharArray, position: Int) { if (ignored == 0) output.append(data) }
    }, true)
    return output.toString().trim()
}
