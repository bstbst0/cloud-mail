@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.cloudmail.app

import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeUIViewController
import com.cloudmail.app.presentation.App
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.UIKit.UIViewController
import platform.UserNotifications.*

private var foregroundModel: MailViewModel? = null
private val pendingMessage = mutableStateOf<Long?>(null)
fun MainViewController(): UIViewController = ComposeUIViewController {
    val model = remember { MailViewModel(IosPlatform()).also { foregroundModel = it } }
    val state by model.state.collectAsState()
    LaunchedEffect(state.session?.userId) {
        if (state.session != null && !NSUserDefaults.standardUserDefaults.boolForKey("notification-requested")) {
            NSUserDefaults.standardUserDefaults.setBool(true, forKey = "notification-requested")
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound) { _, _ -> }
        }
    }
    LaunchedEffect(pendingMessage.value, state.messages) {
        pendingMessage.value?.let { id -> state.messages.find { it.id == id }?.let { model.select(it); pendingMessage.value = null } }
    }
    DisposableEffect(model) {
        onDispose {
            if (foregroundModel === model) foregroundModel = null
            model.dispose()
        }
    }
    App(model)
}
class IosLifecycle {
    fun refresh() { foregroundModel?.refresh() }
    fun openMessage(id: Long) { pendingMessage.value = id; foregroundModel?.refresh() }
}
/** APNs is optional. BGAppRefresh polls within the background time granted by the OS. */
class BackgroundSync {
    private var job: Job? = null
    fun cancel() { job?.cancel() }
    fun run(completion: (Boolean) -> Unit) {
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val platform = IosPlatform(); var client: io.ktor.client.HttpClient? = null
            var encodedSession: String? = null
            var store: MailStore? = null
            suspend fun revokeSession() {
                if (encodedSession != null && platform.secret("session") == encodedSession) {
                    platform.putSecret("session", null)
                    withContext(NonCancellable) {
                        store?.let { revokedStore ->
                            val revokedOwner = revokedStore.owner
                            MailRepository(revokedStore) { error("Session ended") }.clearOwnerIf(revokedOwner) {
                                val current = platform.secret("session")
                                current == null || runCatching {
                                    val session = MailJson.decodeFromString<Session>(current)
                                    "${platform.secret("server")}#${session.userId}" != revokedOwner
                                }.getOrDefault(false)
                            }
                        }
                    }
                }
            }
            try {
                encodedSession = platform.secret("session")
                val session = encodedSession?.let { MailJson.decodeFromString<Session>(it) }
                val server = platform.secret("server")
                if (session == null || server == null) { completion(true); return@launch }
                val ownerStore = MailStore(platform).apply { owner = "$server#${session.userId}" }
                store = ownerStore
                if (session.expiresAt <= NSDate().timeIntervalSince1970.toLong()) { revokeSession(); completion(true); return@launch }
                val initial = ownerStore.cursor() == null
                client = platform.httpClient()
                val api = ApiClient(client, server) { session.token }
                val new = MailRepository(ownerStore, sessionIsValid = { platform.secret("session") == encodedSession }, api = { api }).sync()
                if (!initial && platform.secret("session") == encodedSession) platform.notifyMail(new)
                completion(true)
            } catch (_: CancellationException) { completion(false) }
            catch (error: MailException) {
                if (error.code == 401) { revokeSession(); completion(true) } else completion(false)
            }
            catch (_: Exception) { completion(false) }
            finally { client?.close() }
        }
    }
}
internal object IosNotifications {
    fun show(platform: PlatformServices, messages: List<Message>) {
        val session = platform.secret("session")?.let { MailJson.decodeFromString<Session>(it) } ?: return
        if (session.expiresAt <= NSDate().timeIntervalSince1970.toLong()) return
        val owner = "${platform.secret("server")}#${session.userId}"
        val key = "notified-$owner"
        val seen = platform.secret(key)?.split(',')?.toMutableSet() ?: mutableSetOf()
        val center = UNUserNotificationCenter.currentNotificationCenter()
        messages.filter { !it.read && !it.deleted && it.id.toString() !in seen }.forEach { message ->
            val content = UNMutableNotificationContent().apply {
                // Explicit Objective-C setters avoid inherited read-only property shadowing.
                setTitle("Cloud Mail")
                setBody("A new message arrived.")
                setSound(UNNotificationSound.defaultSound)
                setUserInfo(mapOf("messageId" to message.id.toString()))
            }
            val request = UNNotificationRequest.requestWithIdentifier("$owner#${message.id}", content, null)
            center.addNotificationRequest(request) { _ -> }
            seen += message.id.toString()
        }
        platform.putSecret(key, seen.toList().takeLast(1000).joinToString(","))
    }
}
