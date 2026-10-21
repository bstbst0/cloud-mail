package com.cloudmail.app

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlin.test.*

class MailPerformanceTest {
    @Test fun incrementalPageDoesNotDecryptTheWholeMailboxAndSummariesBoundRetainedBodies() {
        val client = HttpClient(testEngine { error("Network unused") })
        var decryptions = 0
        val platform = object : PlatformServices by TestPlatform(client) {
            override fun encrypt(bytes: ByteArray) = bytes
            override fun decrypt(bytes: ByteArray): ByteArray { decryptions++; return bytes }
        }
        try {
            val store = MailStore(platform).apply { owner = "synthetic" }
            val accounts = listOf(Account(1, "synthetic@example.com"))
            val text = "Synthetic text ".repeat(1000)
            val html = "<p>Synthetic HTML</p>".repeat(1000)
            store.merge(SyncPage((1L..500L).map { Message(it, 1, text = text, html = html) }, accounts, "initial", false, true))
            decryptions = 0
            store.merge(SyncPage(listOf(Message(500, 1, text = text, html = html, read = true)), accounts, "next", false))
            assertTrue(decryptions < 20, "A one-message update decrypted $decryptions records")
            val rows = store.summaries()
            assertEquals(500, rows.size)
            assertTrue(rows.all { it.html.isEmpty() && it.text.length <= 512 })
            assertEquals(html, store.message(500)?.html)
            assertEquals(text, store.message(500)?.text)
            assertTrue(store.message(500)!!.read)
        } finally { client.close() }
    }

    @Test fun repositoryEncryptionRunsOffCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val page = SyncPage(listOf(Message(1, 1, text = "Synthetic")), listOf(Account(1, "synthetic@example.com")), "cursor", false, true)
        val client = HttpClient(testEngine { respond("{\"code\":200,\"data\":${MailJson.encodeToString(page)}}", headers = headersOf("Content-Type", "application/json")) })
        var encryptedOnCaller = false
        val platform = object : PlatformServices by TestPlatform(client) {
            override val cacheDispatcher = Dispatchers.Default
            override fun encrypt(bytes: ByteArray): ByteArray {
                if (Thread.currentThread() === caller) encryptedOnCaller = true
                return bytes
            }
            override fun decrypt(bytes: ByteArray) = bytes
        }
        try {
            val store = MailStore(platform).apply { owner = "synthetic" }
            var progress = 0
            MailRepository(store) { ApiClient(client, "https://fixture.invalid") { "synthetic" } }.sync { progress = it }
            assertFalse(encryptedOnCaller)
            assertEquals(1, progress)
            assertEquals("cursor", store.cursor())
        } finally { client.close() }
    }
}
