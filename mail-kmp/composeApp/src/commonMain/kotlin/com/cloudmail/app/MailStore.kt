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
        // Keep the old ownership map until revoked intentions have been removed.
        // A reset otherwise erases the only account association of queued actions.
        val cachedMessages = messages().associateBy { it.id }
        val allowedAccounts = page.accounts.map { it.id }.toSet()
        val revokedIds = cachedMessages.values.filter { it.accountId !in allowedAccounts }.map { it.id }.toSet() +
            page.messages.filter { message ->
                message.accountId !in allowedAccounts &&
                    (message.accountId != 0L || cachedMessages[message.id]?.accountId !in allowedAccounts)
            }.map { it.id }
        pending().filter { it.messageId in revokedIds }.forEach { dropAction(it) }
        val queued = pending().groupBy { it.messageId }
        if (page.reset) db.cacheQueries.clearKind(owner, "message")
        revokedIds.forEach { db.cacheQueries.remove(owner, "message", it.toString()) }
        db.cacheQueries.clearKind(owner, "account")
        page.accounts.forEach { put("account", it.id.toString(), it) }
        drafts().filter { it.accountId !in allowedAccounts }.forEach { deleteDraft(it.id) }
        page.messages.forEach { message ->
            // Tombstones never receive optimistic overlays or retain mail content.
            // An ordinary deletion keeps only its still-authorized local account
            // association, so a later page does not discard a legitimate undo.
            if (message.accountId !in allowedAccounts) {
                val knownAccount = if (message.accountId == 0L) cachedMessages[message.id]?.accountId?.takeIf { it in allowedAccounts } else null
                put("message", message.id.toString(), Message(message.id, knownAccount ?: 0, deleted = true))
            } else {
                val cached = cachedMessages[message.id]?.takeIf { it.accountId in allowedAccounts }
                var merged = if (message.deleted) message.copy(text = "", html = "", attachments = emptyList(), inlineAttachments = emptyList(), cachedHtmlSource = "")
                    else if (cached != null && cached.cachedHtmlSource == message.html && cached.cachedHtmlSource.isNotEmpty()) message.copy(html = cached.html, cachedHtmlSource = cached.cachedHtmlSource) else message
                queued[message.id].orEmpty().forEach { merged = applyAction(merged, it) }
                put("message", merged.id.toString(), merged)
            }
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
