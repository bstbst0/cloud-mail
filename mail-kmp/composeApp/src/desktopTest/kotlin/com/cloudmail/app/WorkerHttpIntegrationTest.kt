package com.cloudmail.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.*

/** Opt-in, real HTTP integration against test/http-fixture.mjs, never a deployed mailbox. */
class WorkerHttpIntegrationTest {
    @Test fun nativeRepositoriesConvergeThroughTheActualWorker() = runBlocking {
        val server = System.getenv("CLOUD_MAIL_E2E_URL")
        assumeTrue("Start the isolated Worker fixture and set CLOUD_MAIL_E2E_URL", server != null)
        require(server == "http://127.0.0.1:8789") { "This test only accepts the isolated fixture address." }
        val client = HttpClient(CIO) {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000 }
        }
        val disconnected = HttpClient(MockEngine { throw java.io.IOException("Deliberately offline test transport") })
        try {
            val anonymous = ApiClient(client, server!!) { null }
            assertEquals(401, assertFailsWith<MailException> { anonymous.login("user1@example.com", "wrong") }.code)
            val first = anonymous.login("user1@example.com", "fixture-password")
            val second = anonymous.login("user1@example.com", "fixture-password")
            val recipient = anonymous.login("user2@example.com", "fixture-password")
            val firstApi = ApiClient(client, server) { first.token }
            val secondApi = ApiClient(client, server) { second.token }
            val recipientApi = ApiClient(client, server) { recipient.token }
            var online = true
            val store = MailStore(TestPlatform(client)).apply { owner = "${server}#${first.userId}" }
            val repository = MailRepository(store) { if (online) firstApi else ApiClient(disconnected, server) { first.token } }
            repository.sync()
            assertEquals(listOf(1L), store.accounts().map { it.id })
            assertTrue(store.messages().any { it.id == 11L })
            assertFalse(store.messages().any { it.id == 21L })
            val original = repository.detail(11)
            assertTrue(original.text.isNotBlank())
            assertEquals("Synthetic document", firstApi.attachment(1).decodeToString())
            assertEquals(404, assertFailsWith<MailException> { recipientApi.message(11) }.code)
            online = false
            repository.action(original, "read", true, UUID.randomUUID().toString())
            repository.action(store.messages().first { it.id == 11L }, "star", true, UUID.randomUUID().toString())
            assertEquals(0, assertFailsWith<MailException> { repository.sync() }.code)
            assertEquals(2, store.pending().size)
            assertTrue(store.messages().first { it.id == 11L }.let { it.read && it.starred && it.text.isNotBlank() })
            online = true
            repository.sync()
            assertTrue(store.pending().isEmpty())
            val otherStore = MailStore(TestPlatform(client)).apply { owner = "second-device" }
            val otherRepository = MailRepository(otherStore) { secondApi }
            otherRepository.sync()
            assertTrue(otherStore.messages().first { it.id == 11L }.let { it.read && it.starred })

            val subject = "Native HTTP E2E ${UUID.randomUUID()}"
            val request = SendRequest(UUID.randomUUID().toString(), 1, listOf("user2@example.com"), emptyList(), emptyList(), subject,
                "An internal synthetic message", "<p>An internal synthetic message</p>", 11,
                listOf(OutgoingAttachment("fixture.txt", "text/plain", "Rml4dHVyZSBhdHRhY2htZW50")))
            val sent = firstApi.send(request)
            assertEquals("sent", sent.status)
            assertEquals(sent, firstApi.send(request))
            assertEquals("sent", firstApi.sendStatus(request.operationId).status)
            val recipientStore = MailStore(TestPlatform(client)).apply { owner = "recipient" }
            val recipientRepository = MailRepository(recipientStore) { recipientApi }
            recipientRepository.sync()
            val received = recipientStore.messages().single { it.subject == subject && it.folder == "inbox" }
            val detail = recipientRepository.detail(received.id)
            assertEquals("Fixture attachment", recipientApi.attachment(detail.attachments.single().id).decodeToString())
            val reply = recipientApi.send(SendRequest(UUID.randomUUID().toString(), 2, listOf("user1@example.com"), emptyList(), emptyList(),
                "Re: $subject", "Synthetic reply", "<p>Synthetic reply</p>", received.id, emptyList()))
            assertEquals("sent", reply.status)
            repository.sync()
            assertEquals(1, store.messages().count { it.subject == subject && it.folder == "sent" })
            val replyMessage = store.messages().single { it.subject == "Re: $subject" && it.folder == "inbox" }
            assertTrue(firstApi.search(subject).any { it.id == replyMessage.id })
            repository.action(replyMessage, "delete", true, UUID.randomUUID().toString())
            repository.sync(); otherRepository.sync()
            assertTrue(otherStore.messages().first { it.id == replyMessage.id }.deleted)
            firstApi.logout()
            assertEquals(401, assertFailsWith<MailException> { firstApi.sync(null) }.code)
            // Revoking one device keeps the independently authenticated device valid.
            secondApi.sync(null)
            repository.clear()
            assertTrue(store.messages().isEmpty()); assertNull(store.cursor())
            secondApi.logout(); recipientApi.logout()
        } finally { disconnected.close(); client.close() }
    }
}
