package com.cloudmail.app

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DesktopSecurityTest {
    @Test fun encryptedCacheRejectsTamperingAndUsesFreshNonces() {
        val platform = DesktopPlatform()
        val plaintext = "Synthetic fixture mail".encodeToByteArray()
        val first = platform.encrypt(plaintext)
        val second = platform.encrypt(plaintext)
        assertFalse(first.contentEquals(second))
        assertContentEquals(plaintext, platform.decrypt(first))
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertFails { platform.decrypt(first) }
        assertFailsWith<IllegalArgumentException> { platform.decrypt(byteArrayOf(1, 2)) }
    }
    @Test fun textualHtmlFallbackDecodesEntitiesAndDiscardsActiveContent() {
        val text = desktopMailText("<html><head><title>Hidden</title><style>body { display:none }</style></head><body><p>Hello &amp; &#x4e2d;&#x6587;</p><script>secret()</script><p>Safe<img alt='Photo' src='https://tracking.invalid/image'></p></body></html>")
        assertContains(text, "Hello & 中文")
        assertContains(text, "Safe")
        assertContains(text, "[Photo]")
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("display:none"))
        assertFalse(text.contains("tracking.invalid"))
    }
    @Test fun fileReaderPreservesContentAndRejectsOversizedOrSpecialFiles() {
        val path = Files.createTempFile("cloudmail-fixture", ".txt")
        try {
            Files.writeString(path, "synthetic fixture")
            assertEquals("synthetic fixture", readDesktopAttachment(path.toFile()).decodeToString())
            java.io.RandomAccessFile(path.toFile(), "rw").use { it.setLength(10L * 1024 * 1024 + 1) }
            assertFailsWith<IllegalArgumentException> { readDesktopAttachment(path.toFile()) }
            assertFailsWith<IllegalArgumentException> { readDesktopAttachment(File(path.parent.toFile(), ".")) }
        } finally { Files.deleteIfExists(path) }
    }
}
