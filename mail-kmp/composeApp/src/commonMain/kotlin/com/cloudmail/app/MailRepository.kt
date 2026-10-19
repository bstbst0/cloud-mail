package com.cloudmail.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MailRepository(val store: MailStore, private val sessionIsValid: () -> Boolean = { true }, private val api: () -> ApiClient) {
    companion object { private val syncLock = Mutex() }
    suspend fun clear() {
        val generation = store.generation
        val owner = store.owner
        syncLock.withLock { if (store.generation == generation) store.clearOwner(owner) }
    }
    suspend fun clearOwnerIf(owner: String, allowed: () -> Boolean) = syncLock.withLock {
        if (allowed()) store.clearOwner(owner)
    }
    private suspend fun checkSession(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (store.generation != generation || !sessionIsValid()) throw CancellationException("Session changed")
    }
    suspend fun sync(): List<Message> {
        val generation = store.generation
        val client = api()
        return syncLock.withLock {
            checkSession(generation)
            // Each operation keeps its ID until acknowledged. A rejected action is
            // removed before applying the authoritative state and later intentions.
            for (action in store.pending()) {
                try {
                    val result = client.action(action)
                    checkSession(generation)
                    store.acknowledge(action, result.message)
                } catch (error: MailException) {
                    checkSession(generation)
                    if (error.code !in listOf(403, 404, 410)) throw error
                    val authoritative = try { client.message(action.messageId) }
                    catch (detailError: MailException) {
                        checkSession(generation)
                        // A transient failure must retain the queued action for retry.
                        if (detailError.code !in listOf(403, 404, 410)) throw detailError
                        store.messages().find { it.id == action.messageId }?.copy(deleted = true)
                    }
                    checkSession(generation)
                    store.dropAction(action)
                    authoritative?.let { store.saveMessage(it) }
                }
            }
            val before = store.messages().map { it.id }.toSet()
            var page: SyncPage
            var cursor = store.cursor()
            do {
                page = try { client.sync(cursor) } catch (error: MailException) {
                    checkSession(generation)
                    if (cursor != null && error.code in listOf(400, 409, 410)) client.sync(null) else throw error
                }
                checkSession(generation)
                store.merge(page); cursor = page.cursor
            } while (page.hasMore)
            store.messages().filter { it.id !in before && it.folder == "inbox" && !it.deleted }
        }
    }
    fun action(message: Message, kind: String, value: Boolean, operationId: String) {
        if (!sessionIsValid() || (store.accounts().isNotEmpty() && store.accounts().none { it.id == message.accountId })) return
        val current = store.messages().find { it.id == message.id } ?: message
        val action = PendingAction(operationId, current.id, kind, value)
        store.queue(action, applyAction(current, action))
    }
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun detail(id: Long): Message {
        val generation = store.generation
        val client = api()
        return syncLock.withLock {
            checkSession(generation)
            val message = client.message(id)
            checkSession(generation)
            var html = message.html; var total = 0L
            for (image in message.inlineAttachments) {
                if (image.mimeType !in setOf("image/png", "image/jpeg", "image/gif", "image/webp") || image.size > 2 * 1024 * 1024 || total + image.size > 5 * 1024 * 1024) continue
                val bytes = try { client.attachment(image.id) } catch (error: MailException) { if (error.code == 401) throw error else continue }
                checkSession(generation)
                if (bytes.size > 2 * 1024 * 1024 || total + bytes.size > 5 * 1024 * 1024) continue
                total += bytes.size
                val data = "data:${image.mimeType};base64,${kotlin.io.encoding.Base64.Default.encode(bytes)}"
                html = html.replace("\"${image.reference}\"", "\"$data\"").replace("'${image.reference}'", "'$data'")
            }
            checkSession(generation)
            message.copy(html = html, cachedHtmlSource = if (html != message.html) message.html else "").also { store.saveMessage(it) }
        }
    }
    suspend fun search(query: String, read: Boolean? = null, hasAttachment: Boolean? = null, after: String? = null, before: String? = null, folder: String? = null, accountId: Long? = null): List<Message> {
        val generation = store.generation
        val client = api()
        return syncLock.withLock {
            checkSession(generation)
            val messages = client.search(query, read, hasAttachment, after, before, folder, accountId)
            checkSession(generation)
            messages.forEach { store.saveMessage(it) }
            val cached = store.messages().associateBy { it.id }
            messages.map { message -> cached[message.id] ?: message }
        }
    }
    suspend fun send(draft: Draft): SendResult {
        require(draft.operationId != null) { "A persisted send operation is required." }
        fun addresses(value: String) = value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        return api().send(SendRequest(draft.operationId, draft.accountId, addresses(draft.to), addresses(draft.cc), addresses(draft.bcc), draft.subject, draft.text, draft.html, draft.replyToId, draft.attachments))
    }
    suspend fun sendStatus(operationId: String) = api().sendStatus(operationId)
    suspend fun attachment(id: Long, progress: (Float?) -> Unit = {}) = api().attachment(id, progress)
}
