package com.cloudmail.app

import kotlinx.serialization.Serializable

@Serializable data class Account(val id: Long, val email: String, val name: String = "")
@Serializable data class Attachment(val id: Long, val name: String, val mimeType: String = "application/octet-stream", val size: Long = 0)
@Serializable data class InlineAttachment(val id: Long, val name: String, val mimeType: String, val size: Long, val reference: String)
@Serializable data class Message(
    val id: Long, val accountId: Long, val fromAddress: String = "", val fromName: String = "",
    val subject: String = "", val text: String = "", val html: String = "", val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(), val bcc: List<String> = emptyList(), val receivedAt: String = "",
    val folder: String = "inbox", val read: Boolean = false, val starred: Boolean = false,
    val deleted: Boolean = false, val status: Int = 0, val attachments: List<Attachment> = emptyList(),
    val inlineAttachments: List<InlineAttachment> = emptyList(), val cachedHtmlSource: String = ""
)
@Serializable data class Session(val token: String, val userId: Long, val email: String, val expiresAt: Long)
@Serializable data class SyncPage(val messages: List<Message>, val accounts: List<Account>, val cursor: String, val hasMore: Boolean, val reset: Boolean = false)
@Serializable data class PendingAction(val operationId: String, val messageId: Long, val kind: String, val value: Boolean, val sequence: Long = 0)
@Serializable data class ActionResult(val operationId: String, val message: Message)
@Serializable data class OutgoingAttachment(val name: String, val mimeType: String, val contentBase64: String) {
    val size: Long get() = ((contentBase64.length * 3L / 4) - when { contentBase64.endsWith("==") -> 2; contentBase64.endsWith("=") -> 1; else -> 0 }).coerceAtLeast(0)
}
@Serializable data class SendRequest(val operationId: String, val accountId: Long, val to: List<String>, val cc: List<String>, val bcc: List<String>, val subject: String, val text: String, val html: String, val replyToId: Long?, val attachments: List<OutgoingAttachment>)
@Serializable data class SendResult(val operationId: String, val status: String, val messageId: Long? = null)
@Serializable data class Draft(val id: String, val accountId: Long, val to: String = "", val cc: String = "", val bcc: String = "", val subject: String = "", val text: String = "", val html: String = "", val replyToId: Long? = null, val attachments: List<OutgoingAttachment> = emptyList(), val operationId: String? = null, val sendStatus: String? = null)
@Serializable data class Settings(val preferHtml: Boolean = true, val dynamicColor: Boolean = true, val theme: String = "system", val textScale: Float = 1f, val remoteImages: Boolean = false, val defaultAccountId: Long? = null, val gestureRead: String = "read", val gestureDelete: String = "delete", val syncMinutes: Int = 15)
data class AppState(
    val serverUrl: String = "", val session: Session? = null, val accounts: List<Account> = emptyList(),
    val messages: List<Message> = emptyList(), val selected: Message? = null, val folder: String = "inbox",
    val query: String = "", val loading: Boolean = false, val searching: Boolean = false, val error: String? = null, val offline: Boolean = false,
    val drafts: List<Draft> = emptyList(), val composer: Draft? = null, val sending: Boolean = false, val sendStatus: String? = null,
    val theme: String = "system", val textScale: Float = 1f, val remoteImages: Boolean = false,
    val selectedAccountId: Long? = null, val defaultAccountId: Long? = null,
    val gestureRead: String = "read", val gestureDelete: String = "delete", val syncMinutes: Int = 15,
    val preferHtml: Boolean = true, val attachmentId: Long? = null, val attachmentProgress: Float? = null, val secureStorage: Boolean = true, val dynamicColor: Boolean = true
)
