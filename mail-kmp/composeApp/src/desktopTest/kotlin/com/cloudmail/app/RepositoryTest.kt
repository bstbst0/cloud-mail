package com.cloudmail.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cloudmail.db.MailDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import java.util.UUID

internal fun testEngine(handler: MockRequestHandler) = MockEngine(MockEngineConfig().apply { dispatcher = Dispatchers.Unconfined; addHandler(handler) })

internal class TestPlatform(private val client: HttpClient, private val attachments: () -> List<OutgoingAttachment> = { emptyList() }) : PlatformServices {
    override val persistentSecureStorage = false
    private val secrets = mutableMapOf<String,String>()
    override fun httpClient() = client
    override fun databaseDriver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MailDatabase.Schema.create(it) }
    override fun secret(key: String) = secrets[key]
    override fun putSecret(key: String, value: String?) { if(value==null) secrets.remove(key) else secrets[key]=value }
    override fun encrypt(bytes: ByteArray) = bytes.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    override fun decrypt(bytes: ByteArray) = encrypt(bytes)
    override fun randomId() = UUID.randomUUID().toString()
    override suspend fun pickAttachments() = attachments()
    override suspend fun saveAttachment(name:String,mimeType:String,bytes:ByteArray,action:String) {}
}
class RepositoryTest {
    private val account = Account(1, "me@example.com")
    private fun page(messages:List<Message>, cursor:String="c1", reset:Boolean=false, more:Boolean=false) = SyncPage(messages,listOf(account),cursor,more,reset)
    @Test fun cursorAndMessageCommitTogetherAndOwnerIsolation() {
        val platform = TestPlatform(HttpClient(testEngine { error("Network unused") }))
        val store = MailStore(platform); store.owner="user-a"
        store.merge(page(listOf(Message(1,1,text="private")), reset=true))
        assertEquals("c1", store.cursor()); assertEquals("private", store.messages().single().text)
        store.owner="user-b"; assertTrue(store.messages().isEmpty()); assertNull(store.cursor())
        store.owner="user-a"; store.clear(); assertTrue(store.messages().isEmpty()); assertNull(store.cursor())
    }
    @Test fun resetPreservesDraftAndQueueIntentAndTombstoneDeduplicates() {
        val store = MailStore(TestPlatform(HttpClient(testEngine { error("Network unused") }))); store.owner="u"
        val message = Message(1,1)
        store.merge(page(listOf(message), reset=true))
        store.saveDraft(Draft("draft",1,text="unfinished"))
        val action = PendingAction("stable-id",1,"star",true); store.queue(action, applyAction(message,action))
        store.merge(page(listOf(message.copy(read=true)),"c2", reset=true))
        assertEquals(1,store.messages().size); assertTrue(store.messages().single().starred); assertTrue(store.messages().single().read)
        assertEquals("unfinished",store.drafts().single().text); assertEquals("stable-id",store.pending().single().operationId)
        store.merge(page(listOf(message.copy(deleted=true)),"c3")); assertTrue(store.messages().single().deleted)
    }
    @Test fun offlineQueueReplaysIdempotentlyThenAuthoritativeSyncAcrossDevices() = runTest {
        var remote = Message(1,1,text="offline readable")
        var offline = true
        val operations = mutableSetOf<String>()
        val client = HttpClient(testEngine { request ->
            assertEquals("raw-token",request.headers[HttpHeaders.Authorization])
            if(offline) throw java.io.IOException("offline")
            val result = if(request.url.encodedPath.endsWith("actions")) {
                val action = MailJson.decodeFromString<PendingAction>((request.body as io.ktor.http.content.TextContent).text)
                operations += action.operationId; remote=applyAction(remote,action)
                MailJson.encodeToString(ActionResult(action.operationId,remote))
            } else MailJson.encodeToString(page(listOf(remote), "next"))
            respond("{\"code\":200,\"message\":\"success\",\"data\":$result}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val store = MailStore(TestPlatform(client)); store.owner="u"; store.merge(page(listOf(remote),reset=true))
        val repo = MailRepository(store) { ApiClient(client,"https://cloud.test") { "raw-token" } }
        repo.action(remote,"read",true,"operation-1")
        assertTrue(store.messages().single().read); assertEquals("offline readable",store.messages().single().text)
        assertFailsWith<MailException> { repo.sync() }; assertEquals(1,store.pending().size)
        offline=false; repo.sync(); assertTrue(store.pending().isEmpty()); assertTrue(remote.read); assertEquals(setOf("operation-1"),operations)
        val other = MailStore(TestPlatform(client)); other.owner="u"
        MailRepository(other) { ApiClient(client,"https://cloud.test") { "raw-token" } }.sync()
        assertEquals(store.messages(),other.messages())
    }
    @Test fun sendTimeoutNeverAutomaticallyRetriesAndStatusIsQueryable() = runTest {
        var posts=0
        val client=HttpClient(testEngine { request ->
            if(request.method==HttpMethod.Post) { posts++; throw java.io.IOException("Response lost after provider accepted") }
            respond("""{"code":200,"data":{"operationId":"send-id","status":"sent","messageId":8}}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val api=ApiClient(client,"https://cloud.test"){"raw"}
        assertFailsWith<MailException> { api.send(SendRequest("send-id",1,listOf("a@example.com"),emptyList(),emptyList(),"subject","text","",null,emptyList())) }
        assertEquals(1,posts); assertEquals("sent",api.sendStatus("send-id").status); assertEquals(1,posts)
    }
    @Test fun consecutiveOfflineActionsReplayInCreationOrder() {
        val store = MailStore(TestPlatform(HttpClient(testEngine { error("Network unused") }))); store.owner="u"
        val m = Message(1,1)
        store.queue(PendingAction("zzz",1,"read",true),m.copy(read=true))
        store.queue(PendingAction("aaa",1,"read",false),m.copy(read=false))
        assertEquals(listOf("zzz","aaa"),store.pending().map { it.operationId })
        store.acknowledge(store.pending().first(),m.copy(read=true))
        assertFalse(store.messages().single().read)
    }
    @Test fun allSearchPagesAreFetchedWithFilters() = runTest {
        val requests=mutableListOf<String>()
        val client=HttpClient(testEngine { req ->
            requests += req.url.toString()
            val first=req.url.parameters["next"]==null
            val json=MailJson.encodeToString(listOf(Message(if(first) 2 else 1,1)))
            respond("{\"code\":200,\"data\":{\"messages\":$json,\"next\":${if(first) "\"1\"" else "null"}}}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val found=ApiClient(client,"https://cloud.test"){"raw"}.search("words",read=false,hasAttachment=true)
        assertEquals(listOf(2L,1L),found.map { it.id }); assertEquals(2,requests.size)
        assertTrue(requests.all { "read=false" in it && "hasAttachment=true" in it })
    }
    @Test fun authenticationFailuresRemainTyped() = runTest {
        val client=HttpClient(testEngine { respond("denied",HttpStatusCode.Unauthorized) })
        val error=assertFailsWith<MailException> { ApiClient(client,"https://cloud.test"){"revoked"}.sync(null) }
        assertEquals(401,error.code)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun delayedDetailCannotWriteIntoReplacementAccount() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val client = HttpClient(testEngine {
            started.complete(Unit); release.await()
            respond("{\"code\":200,\"data\":${MailJson.encodeToString(Message(7, 1, text = "old owner secret"))}}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val store = MailStore(TestPlatform(client)); store.owner = "old"
        val repo = MailRepository(store) { ApiClient(client, "https://cloud.test") { "old-token" } }
        val request = async { runCatching { repo.detail(7) } }
        started.await(); store.owner = "new"; release.complete(Unit)
        assertIs<CancellationException>(request.await().exceptionOrNull())
        assertTrue(store.messages().isEmpty())
        store.owner = "old"; assertTrue(store.messages().isEmpty())
    }
    @Test fun delayedRejectedActionCannotMutateReplacementOwnersQueue() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val client = HttpClient(testEngine {
            started.complete(Unit); release.await()
            respond("denied", HttpStatusCode.Forbidden)
        })
        val store = MailStore(TestPlatform(client)); store.owner = "old"
        val repo = MailRepository(store) { ApiClient(client, "https://cloud.test") { "token" } }
        repo.action(Message(1, 1), "read", true, "same-id")
        val request = async { runCatching { repo.sync() } }
        started.await(); store.owner = "new"
        store.queue(PendingAction("same-id", 1, "star", true), Message(1, 1, text = "new owner", starred = true))
        release.complete(Unit)
        assertIs<CancellationException>(request.await().exceptionOrNull())
        assertEquals("same-id", store.pending().single().operationId)
        assertEquals("new owner", store.messages().single().text)
    }
    @Test fun rejectedActionRetainsIntentWhenAuthoritativeRecoveryIsOffline() = runTest {
        val client = HttpClient(testEngine { request ->
            if (request.method == HttpMethod.Post) respond("denied", HttpStatusCode.Forbidden)
            else throw java.io.IOException("offline recovery")
        })
        val store = MailStore(TestPlatform(client)); store.owner = "u"
        val repo = MailRepository(store) { ApiClient(client, "https://cloud.test") { "token" } }
        repo.action(Message(1, 1, text = "cached"), "read", true, "retryable")
        assertFailsWith<MailException> { repo.sync() }
        assertEquals("retryable", store.pending().single().operationId)
        assertEquals("cached", store.messages().single().text)
    }

    @Test fun attachmentNetworkFailureIsTypedAndRetryCountIsBounded() = runTest {
        var requests = 0
        val client = HttpClient(testEngine { requests++; throw java.io.IOException("offline") })
        val error = assertFailsWith<MailException> { ApiClient(client, "https://cloud.test") { "token" }.attachment(1) }
        assertEquals(0, error.code); assertEquals(3, requests)
    }
    @Test fun declaredOversizedAttachmentFailsBeforeReadingThePayload() = runTest {
        val client = HttpClient(testEngine {
            respond(io.ktor.utils.io.ByteReadChannel(ByteArray(0)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, (26 * 1024 * 1024).toString()))
        })
        val error = assertFailsWith<MailException> { ApiClient(client, "https://cloud.test") { "token" }.attachment(1) }
        assertEquals(413, error.code)
    }

    @Test fun streamedAttachmentWithoutDeclaredLengthStopsAtTheLimitWithoutRetry() = runTest {
        var requests = 0
        val client = HttpClient(testEngine {
            requests++
            respond(io.ktor.utils.io.ByteReadChannel(ByteArray(25 * 1024 * 1024 + 1)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
        })
        val error = assertFailsWith<MailException> { ApiClient(client, "https://cloud.test") { "token" }.attachment(1) }
        assertEquals(413, error.code); assertEquals(1, requests)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun syncWaitsForRateLimitWindowAndRetryIsCancellable() = runTest {
        var requests = 0
        val client = HttpClient(testEngine {
            requests++
            if (requests == 1) respond("limited", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30"))
            else respond("{\"code\":200,\"data\":${MailJson.encodeToString(page(emptyList()))}}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = ApiClient(client, "https://cloud.test") { "token" }
        val pending = async { api.sync(null) }; runCurrent()
        advanceTimeBy(29_999); runCurrent(); assertEquals(1, requests)
        advanceTimeBy(1); runCurrent(); assertEquals("c1", pending.await().cursor); assertEquals(2, requests)
        requests = 0
        val cancelled = async { api.sync(null) }; runCurrent(); cancelled.cancelAndJoin()
        advanceTimeBy(30_000); runCurrent(); assertEquals(1, requests)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun rateLimitDelayAndAttemptCountAreBoundedAndSendNeverRetries429() = runTest {
        var requests = 0
        val client = HttpClient(testEngine { requests++; respond("limited", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "999")) })
        val api = ApiClient(client, "https://cloud.test") { "token" }
        val pending = async { runCatching { api.sync(null) } }; runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(429, (pending.await().exceptionOrNull() as MailException).code)
        assertEquals(3, requests)
        assertFailsWith<MailException> { api.send(SendRequest("id", 1, listOf("a@example.com"), emptyList(), emptyList(), "s", "t", "", null, emptyList())) }
        assertEquals(4, requests)
    }
    @Test fun logoutCleanupWaitsForInFlightBackgroundWriteAndLeavesNoCache() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var valid = true
        val client = HttpClient(testEngine {
            started.complete(Unit); release.await()
            respond("{\"code\":200,\"data\":${MailJson.encodeToString(page(listOf(Message(1, 1, text = "private"))))}}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val store = MailStore(TestPlatform(client)); store.owner = "old"
        store.merge(page(listOf(Message(1, 1, text = "cached"))))
        val repo = MailRepository(store, { valid }) { ApiClient(client, "https://cloud.test") { "token" } }
        val syncing = async { runCatching { repo.sync() } }; started.await()
        valid = false
        val cleanup = async { repo.clearOwnerIf("old") { true } }
        release.complete(Unit); syncing.await(); cleanup.await()
        assertTrue(store.messages().isEmpty()); assertNull(store.cursor())
    }

    @Test fun ownershipRevocationPurgesRestoreIntentAndDraftWithAndWithoutCursorReset() {
        for (reset in listOf(false, true)) {
            val store = MailStore(TestPlatform(HttpClient(testEngine { error("Network unused") }))); store.owner = "u"
            val retained = Account(2, "retained@example.com")
            val revoked = Message(1, 1, subject = "private subject", text = "private text", html = "<p>private</p>", attachments = listOf(Attachment(1, "private.txt")))
            val allowed = Message(2, 2, text = "retained")
            store.merge(SyncPage(listOf(revoked, allowed), listOf(account, retained), "before", false))
            store.saveDraft(Draft("revoked-draft", 1, text = "private draft"))
            store.saveDraft(Draft("retained-draft", 2, text = "retained draft"))
            store.queue(PendingAction("restore-revoked", 1, "delete", false), revoked)
            store.queue(PendingAction("star-retained", 2, "star", true), allowed.copy(starred = true))
            store.merge(SyncPage(listOf(Message(1, 0, deleted = true), allowed), listOf(retained), "after", false, reset))
            val tombstone = store.messages().single { it.id == 1L }
            assertTrue(tombstone.deleted, "reset=$reset must not resurrect an ownership tombstone")
            assertEquals("", tombstone.text); assertEquals("", tombstone.html); assertEquals("", tombstone.subject)
            assertTrue(tombstone.attachments.isEmpty()); assertTrue(tombstone.inlineAttachments.isEmpty()); assertEquals("", tombstone.cachedHtmlSource)
            assertEquals(listOf(2L), store.messages().filterNot { it.deleted }.map { it.id })
            assertEquals(listOf("star-retained"), store.pending().map { it.operationId })
            assertTrue(store.messages().single { it.id == 2L }.starred)
            assertEquals(listOf("retained-draft"), store.drafts().map { it.id })
            assertEquals("after", store.cursor())
        }
    }

    @Test fun deletionTombstoneKeepsAuthorizedUndoIntentWithoutMakingItVisible() {
        val store = MailStore(TestPlatform(HttpClient(testEngine { error("Network unused") }))); store.owner = "u"
        val message = Message(1, 1, text = "owned message")
        store.merge(page(listOf(message)))
        store.queue(PendingAction("undo", 1, "delete", false), message)
        store.merge(page(listOf(Message(1, 0, deleted = true)), "deleted"))
        assertTrue(store.messages().single().deleted)
        assertEquals("", store.messages().single().text)
        assertEquals(listOf("undo"), store.pending().map { it.operationId })
        store.merge(page(listOf(Message(1, 0, deleted = true)), "deleted-again", reset = true))
        assertTrue(store.messages().single().deleted)
        assertEquals(listOf("undo"), store.pending().map { it.operationId })
        store.acknowledge(store.pending().single(), message)
        assertEquals(message, store.messages().single()); assertTrue(store.pending().isEmpty())
    }

}
