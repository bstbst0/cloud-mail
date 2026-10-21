package com.cloudmail.app

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import kotlin.test.*

class AndroidSecurityTest {
    private fun cipher() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> AndroidCipher { key } }
    @Test fun encryptedCacheRoundTripsUnicodeAndBinary() {
        val codec = cipher()
        val body = "Private mail · 你好 📨".encodeToByteArray() + byteArrayOf(0, 1, -1)
        assertContentEquals(body, codec.decrypt(codec.encrypt(body)))
        assertContentEquals(ByteArray(0), codec.decrypt(codec.encrypt(ByteArray(0))))
    }
    @Test fun eachEncryptionHasDistinctNonce() {
        val codec = cipher(); val message = "same message".encodeToByteArray()
        assertFalse(codec.encrypt(message).contentEquals(codec.encrypt(message)))
    }
    @Test fun tamperedBodyAndNonceFailAuthentication() {
        val codec = cipher(); val encrypted = codec.encrypt("private".encodeToByteArray())
        for (offset in listOf(1, 13, encrypted.lastIndex)) {
            val tampered = encrypted.copyOf(); tampered[offset] = (tampered[offset].toInt() xor 1).toByte()
            assertFailsWith<AEADBadTagException> { codec.decrypt(tampered) }
        }
    }
    @Test fun differentKeyCannotReadCiphertext() {
        val encrypted = cipher().encrypt("private".encodeToByteArray())
        assertFailsWith<AEADBadTagException> { cipher().decrypt(encrypted) }
    }
    @Test fun rejectsTruncatedAndUnknownEnvelope() {
        val codec = cipher()
        assertFailsWith<IllegalArgumentException> { codec.decrypt(ByteArray(8)) }
        val changed = codec.encrypt(byteArrayOf(1)).also { it[0] = 2 }
        assertFailsWith<IllegalArgumentException> { codec.decrypt(changed) }
    }
    @Test fun safeNamesAndMimeTypesPreventPathAndHeaderInjection() {
        assertEquals("attachment", AndroidFiles.safeName(".."))
        assertEquals("attachment", AndroidFiles.safeName("."))
        assertEquals(180, AndroidFiles.safeName("x".repeat(200)).length)
        assertEquals("evil.html", AndroidFiles.safeName("../../evil.html"))
        assertEquals("evil.txt", AndroidFiles.safeName("C:\\private\\evil.txt"))
        assertFalse(AndroidFiles.safeName("a\n<b>:x").contains('\n'))
        assertEquals("application/octet-stream", AndroidFiles.safeMime("text/plain\r\nX-Injected:1"))
        assertEquals("application/pdf", AndroidFiles.safeMime("application/pdf"))
    }
}
