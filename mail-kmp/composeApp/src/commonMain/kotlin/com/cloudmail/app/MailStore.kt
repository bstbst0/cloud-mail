package com.cloudmail.app

import com.cloudmail.db.MailDatabase
import kotlinx.serialization.encodeToString

/** All mailbox content, including queued actions and cursors, is authenticated-encrypted before SQL. */
class MailStore(private val platform: PlatformServices) {
    private val db = MailDatabase(platform.databaseDriver())
    var generation: Long = 0
        private set
    var owner: String = ""
        set(value) { field = value; generation++ }
    private inline fun <reified T> all(kind: String): List<T> = db.cacheQueries.entries(owner, kind).executeAsList().map { MailJson.decodeFromString(platform.decrypt(it.payload).decodeToString()) }
    private inline fun <reified T> put(kind: String, id: String, data: T) { db.cacheQueries.upsert(owner, kind, id, platform.encrypt(MailJson.encodeToString(data).encodeToByteArray())) }
    fun messages() = all<Message>("message")
    fun accounts() = all<Account>("account")
    fun pending() = all<PendingAction>("action").sortedBy { it.sequence }
    fun drafts() = all<Draft>("draft")
    fun settings() = all<Settings>("settings").firstOrNull() ?: Settings()
    fun cursor() = all<String>("cursor").firstOrNull()
    fun saveSettings(settings: Settings) = put("settings", "settings", settings)
    fun saveDraft(draft: Draft) = put("draft", draft.id, draft)
    fun deleteDraft(id: String) = db.cacheQueries.remove(owner, "draft", id)
    fun queue(action: PendingAction, optimistic: Message) = db.transaction { put("action", action.operationId, action.copy(sequence = (pending().maxOfOrNull { it.sequence } ?: 0) + 1)); put("message", optimistic.id.toString(), optimistic) }
    fun acknowledge(action: PendingAction, message: Message) = db.transaction { db.cacheQueries.remove(owner, "action", action.operationId); saveMessage(message) }
    fun dropAction(action: PendingAction) = db.cacheQueries.remove(owner, "action", action.operationId)
    fun saveMessage(message: Message) { var merged = if (message.deleted) message.copy(text = "", html = "", attachments = emptyList(), inlineAttachments = emptyList(), cachedHtmlSource = "") else message; pending().filter { it.messageId == message.id }.forEach { merged = applyAction(merged, it) }; put("message", message.id.toString(), merged) }
    /** A sync page and its cursor commit together. Reset clears messages but preserves local intentions. */
    fun merge(page: SyncPage) = db.transaction {
        if (page.reset) db.cacheQueries.clearKind(owner, "message")
        db.cacheQueries.clearKind(owner, "account")
        page.accounts.forEach { put("account", it.id.toString(), it) }
        val allowedAccounts = page.accounts.map { it.id }.toSet()
        val cachedMessages = messages().associateBy { it.id }
        val queued = pending().groupBy { it.messageId }
        cachedMessages.values.filter { it.accountId !in allowedAccounts }.forEach { revoked ->
            db.cacheQueries.remove(owner, "message", revoked.id.toString())
            queued[revoked.id].orEmpty().forEach { dropAction(it) }
        }
        drafts().filter { it.accountId !in allowedAccounts }.forEach { deleteDraft(it.id) }
        page.messages.forEach { message ->
            val cached = cachedMessages[message.id]
            var merged = if (message.deleted) message.copy(text = "", html = "", attachments = emptyList(), inlineAttachments = emptyList(), cachedHtmlSource = "")
                else if (cached != null && cached.cachedHtmlSource == message.html && cached.cachedHtmlSource.isNotEmpty()) message.copy(html = cached.html, cachedHtmlSource = cached.cachedHtmlSource) else message
            queued[message.id].orEmpty().forEach { merged = applyAction(merged, it) }
            put("message", merged.id.toString(), merged)
        }
        put("cursor", "cursor", page.cursor)
    }
    fun clear() = clearOwner(owner)
    fun clearOwner(owner: String) = db.cacheQueries.clearOwner(owner)
}
fun applyAction(message: Message, action: PendingAction): Message = when(action.kind) {
    "read" -> message.copy(read = action.value)
    "star" -> message.copy(starred = action.value)
    "delete" -> message.copy(deleted = action.value)
    else -> message
}
