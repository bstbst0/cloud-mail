@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.cloudmail.app
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ViewModelTest {
    private val viewModels = mutableListOf<MailViewModel>()
    private fun newViewModel(platform: PlatformServices) = MailViewModel(object : PlatformServices by platform {
        override val cacheDispatcher = Dispatchers.Main
    }).also { viewModels += it }
    @BeforeTest fun prepareMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun restoreMain() { viewModels.forEach { it.dispose() }; viewModels.clear(); Dispatchers.resetMain() }
    @Test fun repeatedRefreshesCoalesceInsteadOfQueuingMailboxDownloads() = runTest {
        val release = CompletableDeferred<Unit>()
        var syncs = 0
        val client = HttpClient(testEngine { request ->
            val data = when {
                request.url.encodedPath.endsWith("login") -> MailJson.encodeToString(Session("synthetic", 1, "me@example.com", 4_000_000_000))
                request.url.encodedPath.endsWith("sync") -> {
                    syncs++
                    if (syncs == 1) release.await()
                    MailJson.encodeToString(SyncPage(emptyList(), listOf(Account(1, "me@example.com")), "cursor", false))
                }
                else -> "null"
            }
            respond("{\"code\":200,\"data\":$data}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = newViewModel(TestPlatform(client))
        vm.login("https://cloud.test", "me@example.com", "password"); runCurrent()
        repeat(50) { vm.refresh() }
        assertEquals(1, syncs)
        release.complete(Unit); runCurrent()
        assertEquals(2, syncs)
        assertFalse(vm.state.value.loading)
        vm.logout(); runCurrent()
    }
    @Test fun loginSyncReadDraftReplySendAndLogout() = runTest {
        var remote=Message(1,1,fromAddress="sender@example.com",subject="Hello",text="Message body",to=listOf("me@example.com"))
        var sends=0
        val client=HttpClient(testEngine { request ->
            val data=when {
                request.url.encodedPath.endsWith("login") -> MailJson.encodeToString(Session("raw",1,"me@example.com",4_000_000_000))
                request.url.encodedPath.endsWith("sync") -> MailJson.encodeToString(SyncPage(listOf(remote),listOf(Account(1,"me@example.com")),"cursor",false))
                request.url.encodedPath.endsWith("actions") -> { remote=remote.copy(read=true); MailJson.encodeToString(ActionResult("id",remote)) }
                request.url.encodedPath.endsWith("messages/1") -> MailJson.encodeToString(remote)
                request.url.encodedPath.endsWith("send") -> { sends++; val send=MailJson.decodeFromString<SendRequest>((request.body as io.ktor.http.content.TextContent).text); assertEquals(1L,send.replyToId); assertEquals(listOf("sender@example.com"),send.to); MailJson.encodeToString(SendResult(send.operationId,"sent",2)) }
                request.url.encodedPath.endsWith("logout") -> "null"
                else -> error("Unexpected route")
            }
            respond("{\"code\":200,\"data\":$data}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val platform=TestPlatform(client); val vm=newViewModel(platform)
        vm.login("https://cloud.test","me@example.com","temporary-password"); runCurrent()
        assertNotNull(vm.state.value.session); assertEquals(1,vm.state.value.accounts.size); assertFalse(vm.state.value.loading)
        vm.select(vm.state.value.messages.single()); runCurrent(); assertTrue(vm.state.value.selected!!.read)
        vm.compose("reply",vm.state.value.selected); assertEquals("sender@example.com",vm.state.value.composer!!.to)
        vm.updateDraft(vm.state.value.composer!!.copy(text="Reply")); vm.send(); runCurrent(); assertEquals(1,sends); assertNull(vm.state.value.composer)
        vm.logout(); runCurrent(); assertNull(vm.state.value.session); assertTrue(vm.state.value.messages.isEmpty()); assertNull(platform.secret("session"))
    }
    @Test fun uncertainSendStaysLockedAndQueriesStatusRatherThanResending() = runTest {
        var posts=0; var gets=0
        val client=HttpClient(testEngine { request ->
            val data=when {
                request.url.encodedPath.endsWith("login") -> MailJson.encodeToString(Session("raw",1,"me@example.com",4_000_000_000))
                request.url.encodedPath.endsWith("sync") -> MailJson.encodeToString(SyncPage(emptyList(),listOf(Account(1,"me@example.com")),"cursor",false))
                request.method==HttpMethod.Post -> { posts++; throw java.io.IOException("lost response") }
                request.url.encodedPath.contains("/send/") -> { gets++; MailJson.encodeToString(SendResult("id","pending")) }
                request.url.encodedPath.endsWith("logout") -> "null"
                else -> error("Unexpected")
            }
            respond("{\"code\":200,\"data\":$data}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val vm=newViewModel(TestPlatform(client)); vm.login("https://cloud.test","me@example.com","password");runCurrent()
        vm.compose();vm.updateDraft(vm.state.value.composer!!.copy(to="a@example.com")); vm.send(); runCurrent()
        assertEquals("uncertain",vm.state.value.composer!!.sendStatus); assertEquals(1,posts)
        vm.send(); runCurrent(); assertEquals(1,posts);assertEquals(1,gets)
        vm.logout();runCurrent()
    }
    @Test fun slowOldLogoutCannotEraseANewerLogin() = runTest {
        val logoutStarted = CompletableDeferred<Unit>(); val finishLogout = CompletableDeferred<Unit>()
        var loginCount = 0
        val client = HttpClient(testEngine { request ->
            val data = when {
                request.url.encodedPath.endsWith("login") -> { loginCount++; MailJson.encodeToString(Session("token-$loginCount", loginCount.toLong(), "user$loginCount@example.com", 4_000_000_000)) }
                request.url.encodedPath.endsWith("sync") -> MailJson.encodeToString(SyncPage(listOf(Message(loginCount.toLong(), 1, text = "mail-$loginCount")), listOf(Account(1, "me@example.com")), "cursor", false))
                request.url.encodedPath.endsWith("logout") -> { if (request.headers[HttpHeaders.Authorization] == "token-1") { logoutStarted.complete(Unit); finishLogout.await() }; "null" }
                else -> error("Unexpected route")
            }
            respond("{\"code\":200,\"data\":$data}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val platform = TestPlatform(client); val vm = newViewModel(platform)
        vm.login("https://cloud.test", "old@example.com", "password"); runCurrent()
        vm.logout(); runCurrent(); logoutStarted.await()
        assertNull(vm.state.value.session)
        vm.login("https://cloud.test", "new@example.com", "password"); runCurrent()
        assertEquals(2L, vm.state.value.session!!.userId)
        finishLogout.complete(Unit); runCurrent()
        assertEquals("token-2", vm.state.value.session!!.token)
        assertEquals("mail-2", vm.state.value.messages.single().text)
        assertEquals("token-2", MailJson.decodeFromString<Session>(platform.secret("session")!!).token)
        vm.logout(); runCurrent()
    }
    @Test fun expiredStoredSessionIsRemovedBeforeAnyNetworkOrCachePublication() {
        val platform = TestPlatform(HttpClient(testEngine { error("Expired session must not perform requests") }))
        platform.putSecret("server", "https://cloud.test")
        platform.putSecret("session", MailJson.encodeToString(Session("expired", 1, "me@example.com", 1)))
        val vm = newViewModel(platform)
        assertNull(vm.state.value.session); assertTrue(vm.state.value.messages.isEmpty())
        assertNull(platform.secret("session"))
    }

    @Test fun offlineSearchAppliesAllRequestedFiltersToTheCache() = runTest {
        val attachment = Attachment(1, "file.txt")
        val messages = listOf(
            Message(1, 1, subject = "needle", receivedAt = "2026-01-10", attachments = listOf(attachment)),
            Message(2, 1, subject = "needle", read = true, receivedAt = "2026-01-10", attachments = listOf(attachment)),
            Message(3, 1, subject = "needle", receivedAt = "2026-01-10"),
            Message(4, 1, subject = "needle", receivedAt = "2025-01-10", attachments = listOf(attachment)))
        val client = HttpClient(testEngine { request ->
            val data = when {
                request.url.encodedPath.endsWith("login") -> MailJson.encodeToString(Session("token", 1, "me@example.com", 4_000_000_000))
                request.url.encodedPath.endsWith("sync") -> MailJson.encodeToString(SyncPage(messages, listOf(Account(1, "me@example.com")), "cursor", false))
                request.url.encodedPath.endsWith("logout") -> "null"
                else -> throw java.io.IOException("offline search")
            }
            respond("{\"code\":200,\"data\":$data}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = newViewModel(TestPlatform(client)); vm.login("https://cloud.test", "me@example.com", "password"); runCurrent()
        vm.search("needle", read = false, hasAttachment = true, after = "2026-01-01", before = "2026-02-01")
        advanceTimeBy(2000); runCurrent()
        assertEquals(listOf(1L), vm.state.value.messages.map { it.id }); assertTrue(vm.state.value.offline)
        vm.logout(); runCurrent()
    }
    @Test fun openingSearchResultPreservesResultsAndAccountRevocationRemovesThem() = runTest {
        var revoked = false
        val found = Message(1, 1, subject = "match", read = true)
        val client = HttpClient(testEngine { request ->
            val data = when {
                request.url.encodedPath.endsWith("login") -> MailJson.encodeToString(Session("token", 1, "me@example.com", 4_000_000_000))
                request.url.encodedPath.endsWith("sync") -> MailJson.encodeToString(SyncPage(if (revoked) emptyList() else listOf(found, Message(2, 1, subject = "other")), if (revoked) emptyList() else listOf(Account(1, "me@example.com")), "cursor", false))
                request.url.encodedPath.endsWith("messages") -> "{\"messages\":[${MailJson.encodeToString(found)}],\"next\":null}"
                request.url.encodedPath.endsWith("messages/1") -> MailJson.encodeToString(found.copy(text = "full detail"))
                request.url.encodedPath.endsWith("logout") -> "null"
                else -> error("Unexpected route")
            }
            respond("{\"code\":200,\"data\":$data}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = newViewModel(TestPlatform(client)); vm.login("https://cloud.test", "me@example.com", "password"); runCurrent()
        vm.search("match"); advanceTimeBy(301); runCurrent()
        assertEquals(listOf(1L), vm.state.value.messages.map { it.id })
        vm.select(vm.state.value.messages.single()); runCurrent()
        assertEquals(listOf(1L), vm.state.value.messages.map { it.id })
        assertEquals("full detail", vm.state.value.selected!!.text)
        revoked = true; vm.refresh(); runCurrent()
        assertTrue(vm.state.value.messages.isEmpty()); assertNull(vm.state.value.selected)
        vm.logout(); runCurrent()
    }

    @Test fun invalidCredentialsExplainTheLoginFailureWithoutExpiredSessionWording() = runTest {
        val vm = newViewModel(TestPlatform(HttpClient(testEngine { respond("denied", HttpStatusCode.Unauthorized) })))
        vm.login("https://cloud.test", "me@example.com", "wrong"); runCurrent()
        assertNull(vm.state.value.session)
        assertEquals("Sign-in failed. Check your email and password.", vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }
    @Test fun repeatedPickerSelectionsAcceptTenAttachmentsAndRejectTheEleventh() = runTest {
        val client = HttpClient(testEngine { request ->
            val data = when {
                request.url.encodedPath.endsWith("login") -> MailJson.encodeToString(Session("token", 1, "me@example.com", 4_000_000_000))
                request.url.encodedPath.endsWith("sync") -> MailJson.encodeToString(SyncPage(emptyList(), listOf(Account(1, "me@example.com")), "cursor", false))
                request.url.encodedPath.endsWith("logout") -> "null"
                else -> error("Unexpected route")
            }
            respond("{\"code\":200,\"data\":$data}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val picks = mutableListOf(List(5) { OutgoingAttachment("first-$it.txt", "text/plain", "YQ==") }, List(5) { OutgoingAttachment("second-$it.txt", "text/plain", "YQ==") }, listOf(OutgoingAttachment("eleventh.txt", "text/plain", "YQ==")))
        val vm = newViewModel(TestPlatform(client) { picks.removeAt(0) })
        vm.login("https://cloud.test", "me@example.com", "password"); runCurrent(); vm.compose()
        vm.addAttachment(); runCurrent(); assertEquals(5, vm.state.value.composer!!.attachments.size)
        vm.addAttachment(); runCurrent(); assertEquals(10, vm.state.value.composer!!.attachments.size)
        vm.addAttachment(); runCurrent(); assertEquals(10, vm.state.value.composer!!.attachments.size)
        assertEquals("A message can contain at most 10 attachments.", vm.state.value.error)
        vm.logout(); runCurrent()
    }

    @Test fun deferredSearchShowsProgressAndEmptyQueryCancelsWithoutStaleResults() = runTest {
        var started = CompletableDeferred<Unit>(); var release = CompletableDeferred<Unit>()
        val client = HttpClient(testEngine { request ->
            val data = when {
                request.url.encodedPath.endsWith("login") -> MailJson.encodeToString(Session("token", 1, "me@example.com", 4_000_000_000))
                request.url.encodedPath.endsWith("sync") -> MailJson.encodeToString(SyncPage(listOf(Message(1, 1, text = "cached inbox")), listOf(Account(1, "me@example.com")), "cursor", false))
                request.url.encodedPath.endsWith("messages") -> { val gate = release; started.complete(Unit); gate.await(); "{\"messages\":[],\"next\":null}" }
                request.url.encodedPath.endsWith("logout") -> "null"
                else -> error("Unexpected route")
            }
            respond("{\"code\":200,\"data\":$data}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vm = newViewModel(TestPlatform(client)); vm.login("https://cloud.test", "me@example.com", "password"); runCurrent()
        vm.search("missing"); assertTrue(vm.state.value.searching); assertFalse(vm.state.value.loading)
        advanceTimeBy(301); runCurrent(); started.await(); assertTrue(vm.state.value.searching)
        release.complete(Unit); runCurrent(); assertFalse(vm.state.value.searching); assertTrue(vm.state.value.messages.isEmpty())
        started = CompletableDeferred(); release = CompletableDeferred()
        vm.search("another"); advanceTimeBy(301); runCurrent(); started.await(); assertTrue(vm.state.value.searching)
        vm.search(""); assertFalse(vm.state.value.searching); assertEquals(listOf(1L), vm.state.value.messages.map { it.id })
        release.complete(Unit); runCurrent(); assertFalse(vm.state.value.searching); assertEquals(listOf(1L), vm.state.value.messages.map { it.id })
        vm.logout(); runCurrent()
    }

}
