package com.cloudmail.app

import kotlin.test.*

/** Runs on the macOS CI iOS Simulator, using only generated fixture data. */
class IosSecurityTest {
    @Test fun authenticatedCacheRoundTripAndDistinctIv() {
        val platform = IosPlatform(); val body = "Private fixture · 你好 📨".encodeToByteArray()
        val first = platform.encrypt(body); val second = platform.encrypt(body)
        assertContentEquals(body, platform.decrypt(first))
        assertFalse(first.contentEquals(second))
        assertContentEquals(ByteArray(0), platform.decrypt(platform.encrypt(ByteArray(0))))
    }
    @Test fun authenticationRejectsModifiedIvBodyAndTag() {
        val platform = IosPlatform(); val encrypted = platform.encrypt("Fixture content".encodeToByteArray())
        for (offset in listOf(1, 17, encrypted.lastIndex)) {
            val modified = encrypted.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFailsWith<IllegalStateException> { platform.decrypt(modified) }
        }
        assertFailsWith<IllegalArgumentException> { platform.decrypt(ByteArray(3)) }
    }
    @Test fun keychainSecretCanBeCreatedUpdatedAndDeleted() {
        val platform = IosPlatform(); val key = "test-${platform.randomId()}"
        try {
            assertNull(platform.secret(key))
            platform.putSecret(key, "fixture-one"); assertEquals("fixture-one", platform.secret(key))
            platform.putSecret(key, ""); assertEquals("", platform.secret(key))
            platform.putSecret(key, "fixture-two"); assertEquals("fixture-two", platform.secret(key))
        } finally { platform.putSecret(key, null) }
        assertNull(platform.secret(key))
    }
}
