package com.cloudmail.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

/** Opt-in real HTTP integration against synthetic fixtures only, never a production mailbox. */
class WorkerHttpIntegrationTest {
    @Test fun nativeRepositoriesConvergeThroughTheActualWorker() = runBlocking {
        val staged = System.getenv("CLOUD_MAIL_STAGING_CONFIG")?.let {
            MailJson.parseToJsonElement(Files.readString(Path.of(it))).jsonObject
        }
        val server = staged?.get("url")?.jsonPrimitive?.content ?: System.getenv("CLOUD_MAIL_E2E_URL")
        assumeTrue("Start the isolated Worker fixture and set CLOUD_MAIL_E2E_URL", server != null)
        val gate = staged?.get("gate")?.jsonPrimitive?.content
        val password = staged?.get("password")?.jsonPrimitive?.content ?: "fixture-password"
        if (staged != null) {
            val uri = URI(server!!)
            val name = staged.getValue("name").jsonPrimitive.content
            val subdomain = staged.getValue("subdomain").jsonPrimitive.content
            require(name.startsWith("cloud-mail-kmp-stage-") && uri.scheme == "https" &&
                subdomain.matches(Regex("[a-z0-9-]+")) && uri.host == "$name.$subdomain.workers.dev" && uri.userInfo == null &&
                uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null && uri.path.isNullOrEmpty() &&
                gate?.length == 64 && password.length >= 32) { "Only the isolated staging fixture is allowed." }
        } else require(server == "http://127.0.0.1:8789") { "This test only accepts the isolated fixture address." }
        val client = HttpClient(CIO) {
            if (staged != null) System.getenv("HTTPS_PROXY")?.let { endpoint ->
                engine { proxy = io.ktor.client.engine.ProxyBuilder.http(io.ktor.http.Url(endpoint)) }
            }
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000 }
            if (gate != null) defaultRequest { headers.append("X-Cloud-Mail-Staging-Key", gate) }
        }
        val disconnected = HttpClient(MockEngine { throw java.io.IOException("Deliberately offline test transport") })
        try {
            if (staged != null) {
                val marker = MailJson.parseToJsonElement(client.get("$server/__staging/health").bodyAsText()).jsonObject
                require(marker["testOnly"]?.jsonPrimitive?.content == "true" &&
                    marker["runId"]?.jsonPrimitive?.content == staged.getValue("name").jsonPrimitive.content) {
                    "Staging fixture identity mismatch; refusing login or writes."
                }
            }
            val anonymous = ApiClient(client, server!!) { null }
            assertEquals(401, assertFailsWith<MailException> { anonymous.login("user1@example.com", "wrong") }.code)
            val first = anonymous.login("user1@example.com", password)
            val second = anonymous.login("user1@example.com", password)
            val recipient = anonymous.login("user2@example.com", password)
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
