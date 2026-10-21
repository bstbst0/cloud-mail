@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cloudmail.app.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cloudmail.app.*
import com.cloudmail.db.MailDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import org.jetbrains.skia.Image

class UiSmokeTest {
    @Test fun compactLoginReadComposeAndSettings() = runDesktopComposeUiTest(width = 420, height = 780) {
        val platform = UiPlatform()
        val vm = MailViewModel(platform)
        setContent { Box(Modifier.requiredSize(420.dp, 780.dp)) { TestApp(vm) } }
        onNodeWithText("Server URL").performTextInput("https://fixture.example")
        onNodeWithText("Account").performTextInput("reader@example.com")
        onNodeWithText("Password").performTextInput("test-only-password")
        screenshot("login-compact")
        onNodeWithText("Sign in").performClick()
        waitUntil(timeoutMillis = 10_000) { vm.state.value.session != null && !vm.state.value.loading }
        onNodeWithText("Welcome to your cloud").assertIsDisplayed()
        screenshot("inbox-compact-light")
        onNodeWithText("Welcome to your cloud").performClick()
        waitUntil(timeoutMillis = 10_000) { vm.state.value.selected?.read == true && platform.remoteRead }
        onNodeWithText("A safe fixture message for local UI testing.").assertIsDisplayed()
        screenshot("message-detail-compact")
        onNodeWithText("Reply").performClick()
        onNodeWithText("Subject").assertIsDisplayed()
        screenshot("composer-compact")
        onNodeWithText("Save & close").performClick()
        onNodeWithContentDescription("Back to messages").performClick()
        onNodeWithContentDescription("Open navigation").performClick()
        onNodeWithText("Settings").performClick()
        onNodeWithText("Dark").performClick()
        screenshot("settings-compact-dark")
        onNodeWithText("Prefer HTML mail").assertIsDisplayed()
    }

    @Test fun expandedInboxAndEmptyState() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val platform = UiPlatform()
        val vm = MailViewModel(platform)
        setContent { Box(Modifier.requiredSize(1280.dp, 900.dp)) { TestApp(vm) } }
        vm.login("https://fixture.example", "reader@example.com", "test-only-password")
        waitUntil(timeoutMillis = 10_000) { vm.state.value.session != null && !vm.state.value.loading }
        onNodeWithText("Your addresses", ignoreCase = true).assertExists()
        onNodeWithContentDescription("Search all mail").assertIsDisplayed()
        onNodeWithText("A little space to focus").assertExists()
        screenshot("inbox-expanded")
        vm.setFolder("sent")
        onNodeWithText("You're all caught up").assertExists()
        screenshot("empty-expanded")
    }

    @Test fun loadingAndLargeFontsStayReachable() = runDesktopComposeUiTest(width = 320, height = 640) {
        val vm = MailViewModel(UiPlatform())
        setContent {
            DisposableEffect(vm) { onDispose { vm.dispose() } }
            CloudMailTheme("light", 1.5f) {
                Box(Modifier.requiredSize(320.dp, 640.dp)) { LoginScreen(AppState(loading = true), vm) }
            }
        }
        onNodeWithText("Signing in…").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        onNodeWithText("Server URL").performScrollTo().assertIsDisplayed()
        screenshot("login-compact-large-font-loading")
    }

    @Test fun authenticationErrorRemainsRecoverable() = runDesktopComposeUiTest(width = 420, height = 780) {
        val vm = MailViewModel(UiPlatform(loginFailure = true))
        setContent { Box(Modifier.requiredSize(420.dp, 780.dp)) { TestApp(vm) } }
        vm.login("https://fixture.example", "reader@example.com", "wrong-test-password")
        waitUntil(timeoutMillis = 10_000) { !vm.state.value.loading }
        onNodeWithText("Sign-in failed. Check your email and password.").performScrollTo().assertIsDisplayed()
        onNodeWithText("Password").assertExists()
        screenshot("login-error")
    }

    @Test fun offlineMailAndRetryUseTheRealViewModel() = runDesktopComposeUiTest(width = 420, height = 780) {
        val platform = UiPlatform()
        val vm = MailViewModel(platform)
        setContent { Box(Modifier.requiredSize(420.dp, 780.dp)) { TestApp(vm) } }
        vm.login("https://fixture.example", "reader@example.com", "test-only-password")
        waitUntil(timeoutMillis = 10_000) { vm.state.value.session != null && !vm.state.value.loading }
        platform.failNetwork = true
        vm.refresh()
        waitUntil(timeoutMillis = 10_000) { vm.state.value.offline && !vm.state.value.loading }
        onNodeWithText("Offline · Your saved mail is available. Changes will sync when connected.").assertExists()
        onNodeWithText("Welcome to your cloud").assertIsDisplayed()
        screenshot("inbox-offline")
        platform.failNetwork = false
        onAllNodesWithText("Retry").onFirst().performClick()
        waitUntil(timeoutMillis = 10_000) { !vm.state.value.offline && !vm.state.value.loading }
        assertFalse(vm.state.value.offline)
    }

    @Test fun settingsAndComposerSupportLargeType() = runDesktopComposeUiTest(width = 320, height = 640) {
        val vm = MailViewModel(UiPlatform())
        setContent { Box(Modifier.requiredSize(320.dp, 640.dp)) { TestApp(vm) } }
        vm.login("https://fixture.example", "reader@example.com", "test-only-password")
        waitUntil(timeoutMillis = 10_000) { vm.state.value.session != null && !vm.state.value.loading }
        vm.updateSettings("dark", 1.5f, false)
        onNodeWithContentDescription("Open navigation").performClick()
        onNodeWithText("Settings").performClick()
        onNodeWithText("Sign out").performScrollTo().assertIsDisplayed()
        screenshot("settings-compact-large-font")
        vm.compose()
        waitUntil(timeoutMillis = 10_000) { vm.state.value.composer != null }
        waitForIdle()
        onNodeWithText("Message").performScrollTo().assertIsDisplayed()
        onNodeWithText("Save & close").assertIsDisplayed()
        onNodeWithText("Send").assertIsDisplayed()
        screenshot("composer-compact-dark-large-font")
    }

    @Test fun desktopKeyboardActionsWorkWithoutAnInputFocused() = runDesktopComposeUiTest(width = 1280, height = 900) {
        val vm = MailViewModel(UiPlatform())
        setContent { Box(Modifier.requiredSize(1280.dp, 900.dp)) { TestApp(vm) } }
        vm.login("https://fixture.example", "reader@example.com", "test-only-password")
        waitUntil(timeoutMillis = 10_000) { vm.state.value.session != null && !vm.state.value.loading }
        onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.K); keyUp(Key.CtrlLeft) }
        onNodeWithText("Search subject, people and message content").assertIsDisplayed()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithText("Search subject, people and message content").assertDoesNotExist()
        onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.N); keyUp(Key.CtrlLeft) }
        onNodeWithText("New message").assertIsDisplayed()
        onNodeWithText("Save & close").performClick()
        waitUntil(timeoutMillis = 10_000) { vm.state.value.composer == null }
    }

    private fun ComposeUiTest.screenshot(name: String) {
        val bitmap = onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()
        val encoded = Image.makeFromBitmap(bitmap).encodeToData() ?: error("Screenshot encoding failed")
        File("build/ui-screenshots").apply { mkdirs() }.resolve("$name.png").writeBytes(encoded.bytes)
    }
}

@Composable
private fun TestApp(vm: MailViewModel) {
    DisposableEffect(vm) { onDispose { vm.dispose() } }
    App(vm)
}

/** Deliberately isolated test account; no production mailbox or network is used. */
private class UiPlatform(private val loginFailure: Boolean = false) : PlatformServices {
    @Volatile var failNetwork = false
    @Volatile var remoteRead = false
    private val values = mutableMapOf<String, String>()
    private val account = Account(1, "reader@example.com", "Reader")
    private val message = Message(1, 1, "sender@example.com", "Sender", "Welcome to your cloud", "A safe fixture message for local UI testing.", to = listOf(account.email), receivedAt = "2026-10-13 10:00:00")
    override val persistentSecureStorage = false
    override fun httpClient() = HttpClient(MockEngine { request ->
        if (failNetwork) throw java.io.IOException("Test-only network failure")
        if (loginFailure && request.url.encodedPath.endsWith("/login")) return@MockEngine respond("{\"code\":401,\"message\":\"Invalid credentials\",\"data\":null}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        val data = when {
            request.url.encodedPath.endsWith("/login") -> MailJson.encodeToString(Session("fixture-session", 1, account.email, 4_000_000_000))
            request.url.encodedPath.endsWith("/sync") -> MailJson.encodeToString(SyncPage(listOf(message.copy(read = remoteRead)), listOf(account), "fixture-cursor", false))
            request.url.encodedPath.endsWith("/messages/1") -> MailJson.encodeToString(message.copy(read = remoteRead))
            request.url.encodedPath.endsWith("/actions") -> { remoteRead = true; MailJson.encodeToString(ActionResult("fixture-operation", message.copy(read = remoteRead))) }
            else -> "{}"
        }
        respond("{\"code\":200,\"message\":\"success\",\"data\":$data}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    override fun databaseDriver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MailDatabase.Schema.create(it) }
    override fun secret(key: String) = values[key]
    override fun putSecret(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    override fun encrypt(bytes: ByteArray) = bytes
    override fun decrypt(bytes: ByteArray) = bytes
    override fun randomId() = UUID.randomUUID().toString()
    override suspend fun pickAttachments() = emptyList<OutgoingAttachment>()
    override suspend fun saveAttachment(name: String, mimeType: String, bytes: ByteArray, action: String) = Unit
}
