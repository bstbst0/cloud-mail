package com.cloudmail.app

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Native keychain/Secret Service; Windows data is DPAPI protected for this user. */
internal class DesktopVault(private val root: File) {
    private val os = System.getProperty("os.name").lowercase()
    private val windows = os.contains("windows")
    private val mac = os.contains("mac")
    private val command = if (mac) "/usr/bin/security" else "/usr/bin/secret-tool"
    val available: Boolean = runCatching {
        if (windows) {
            val probe = UUID.randomUUID().toString().encodeToByteArray()
            Crypt32Util.cryptUnprotectData(Crypt32Util.cryptProtectData(probe)).contentEquals(probe)
        } else if (!File(command).canExecute()) false else {
            val key = "probe-${UUID.randomUUID()}"; val value = UUID.randomUUID().toString()
            try { writeNative(key, value); readNative(key) == value } finally { deleteNative(key) }
        }
    }.getOrDefault(false)
    private fun file(key: String): File {
        require(Regex("[a-zA-Z0-9_-]+").matches(key))
        return File(root, "$key.dpapi")
    }
    fun read(key: String): String? = if (windows) file(key).takeIf { it.exists() }?.let {
        Crypt32Util.cryptUnprotectData(it.readBytes()).decodeToString()
    } else readNative(key)
    fun write(key: String, value: String?) {
        if (windows) {
            if (value == null) { file(key).delete(); return }
            root.mkdirs()
            val target = file(key); val temp = File(root, "$key.tmp")
            temp.writeBytes(Crypt32Util.cryptProtectData(value.encodeToByteArray()))
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } else if (value == null) deleteNative(key) else writeNative(key, value)
    }
    private fun run(arguments: List<String>, input: String? = null): Pair<Int, String> {
        val process = ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw IllegalStateException("The system credential store is unavailable.")
        }
        return process.exitValue() to process.inputStream.bufferedReader().readText().trim()
    }
    private fun readNative(key: String): String? {
        val args = if (mac) listOf(command, "find-generic-password", "-s", "cloud-mail", "-a", key, "-w")
        else listOf(command, "lookup", "application", "cloud-mail", "key", key)
        val (code, output) = run(args)
        if (code == 44 || code == 1) return null // Native 'item not found'.
        check(code == 0) { "The system credential store could not be read." }
        return output.ifEmpty { null }
    }
    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    private fun writeNative(key: String, value: String) {
        // The macOS interactive interface receives secrets via stdin, not argv.
        val (code, _) = if (mac) run(listOf(command, "-i"), "add-generic-password -U -s cloud-mail -a ${quote(key)} -w ${quote(value)}\n")
        else run(listOf(command, "store", "--label=Cloud Mail", "application", "cloud-mail", "key", key), value)
        check(code == 0) { "The system credential store could not save a secret." }
    }
    private fun deleteNative(key: String) {
        val args = if (mac) listOf(command, "delete-generic-password", "-s", "cloud-mail", "-a", key)
        else listOf(command, "clear", "application", "cloud-mail", "key", key)
        val (code, _) = run(args)
        check(code in listOf(0, 1, 44)) { "The system credential store could not remove the session." }
    }
}
