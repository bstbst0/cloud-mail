package com.cloudmail.app

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val MailJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
class MailException(val code: Int, message: String) : Exception(message)
@Serializable private data class Envelope<T>(val code: Int, val message: String = "", val data: T? = null)
@Serializable private data class LoginRequest(val email: String, val password: String)
@Serializable private data class MessagePage(val messages: List<Message>, val next: String? = null)

class ApiClient(val client: HttpClient, private val server: String, private val token: () -> String?) {
    companion object {
        fun validatedServer(input: String, allowLocalHttp: Boolean = false): String {
            val url = Url(input.trim())
            require(url.host.isNotBlank() && input.trim().substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').isNotBlank()) { "Enter a complete server URL." }
            require(url.protocol == URLProtocol.HTTPS || (allowLocalHttp && url.protocol == URLProtocol.HTTP && url.host in listOf("localhost", "127.0.0.1", "10.0.2.2"))) { "Use an HTTPS Cloud Mail server URL." }
            require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.parameters.isEmpty() && url.fragment.isEmpty()) { "Server URL cannot contain credentials, queries or fragments." }
            return input.trim().trimEnd('/')
        }
    }
    private suspend inline fun <reified T> request(method: HttpMethod, path: String, body: String? = null, allowEmpty: Boolean = false): T {
        var attempt = 0
        while (true) {
            val response = try {
                client.request("$server/api/app/v1/$path") {
                    this.method = method
                    token()?.let { header(HttpHeaders.Authorization, it) }
                    if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (method == HttpMethod.Get && attempt++ < 2) { delay(250L shl attempt); continue }
                throw MailException(0, "Cannot reach the server. Your offline data remains available.")
            }
            if (method == HttpMethod.Get && response.status.value in listOf(429, 502, 503, 504) && attempt++ < 2) { response.bodyAsChannel().cancel(null); delay(retryDelay(response, attempt)); continue }
            if (response.status.value !in 200..299) throw MailException(response.status.value, when (response.status.value) { 401 -> "Session expired. Sign in again."; 403 -> "You do not have permission for this operation."; else -> "Server request failed (${response.status.value})." })
            val envelope = try { MailJson.decodeFromString<Envelope<T>>(response.bodyAsText()) } catch (e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e; throw MailException(500, "The server returned an incompatible response.") }
            if (envelope.code != 200) throw MailException(envelope.code, envelope.message.take(250))
            if (envelope.data == null && allowEmpty) return kotlinx.serialization.json.JsonNull as T
            return envelope.data ?: throw MailException(500, "The server returned an empty response.")
        }
    }
    suspend fun login(email: String, password: String) = request<Session>(HttpMethod.Post, "login", MailJson.encodeToString(LoginRequest(email, password)))
    suspend fun logout() { request<kotlinx.serialization.json.JsonElement>(HttpMethod.Delete, "logout", allowEmpty = true) }
    suspend fun sync(cursor: String?) = request<SyncPage>(HttpMethod.Get, "sync" + (cursor?.let { "?cursor=${it.encodeURLParameter()}" } ?: ""))
    suspend fun message(id: Long) = request<Message>(HttpMethod.Get, "messages/$id")
    suspend fun action(action: PendingAction) = request<ActionResult>(HttpMethod.Post, "actions", MailJson.encodeToString(action))
    suspend fun send(request: SendRequest) = request<SendResult>(HttpMethod.Post, "send", MailJson.encodeToString(request))
    suspend fun sendStatus(id: String) = request<SendResult>(HttpMethod.Get, "send/${id.encodeURLPathPart()}")
    suspend fun search(query: String, read: Boolean? = null, hasAttachment: Boolean? = null, after: String? = null, before: String? = null, folder: String? = null, accountId: Long? = null): List<Message> {
        val filters = listOfNotNull(read?.let { "read=$it" }, hasAttachment?.let { "hasAttachment=$it" }, after?.let { "after=${it.encodeURLParameter()}" }, before?.let { "before=${it.encodeURLParameter()}" }, folder?.let { "folder=${if (it == "star") "starred" else it}" }, accountId?.let { "accountId=$it" }).joinToString("&", prefix = "&")
        val found = mutableListOf<Message>(); var cursor: String? = null
        do {
            val page = request<MessagePage>(HttpMethod.Get, "messages?q=${query.encodeURLParameter()}$filters" + (cursor?.let { "&next=${it.encodeURLParameter()}" } ?: ""))
            found.addAll(page.messages); cursor = page.next
        } while (cursor != null)
        return found.distinctBy { it.id }
    }
    suspend fun attachment(id: Long, progress: (Float?) -> Unit = {}): ByteArray {
        var attempt = 0
        while (true) {
            var responseStarted = false
            try {
                // execute{} keeps the response streaming. client.get() saves the
                // entire body before returning, which defeats the memory bound.
                val bytes = client.prepareGet("$server/api/app/v1/attachments/$id") {
                    token()?.let { header(HttpHeaders.Authorization, it) }
                }.execute { response ->
                    responseStarted = true
                    if (response.status.value in listOf(429, 502, 503, 504) && attempt++ < 2) {
                        response.bodyAsChannel().cancel(null)
                        delay(retryDelay(response, attempt))
                        return@execute null
                    }
                    if (!response.status.isSuccess()) throw MailException(response.status.value, "Attachment download failed.")
                    val size = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (size != null && size > 25 * 1024 * 1024) throw MailException(413, "Attachment exceeds the 25 MB limit.")
                    val channel = response.bodyAsChannel()
                    val chunks = mutableListOf<ByteArray>(); val buffer = ByteArray(8192); var total = 0
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > 25 * 1024 * 1024) throw MailException(413, "Attachment exceeds the 25 MB limit.")
                        chunks += buffer.copyOf(count)
                        progress(size?.takeIf { it > 0 }?.let { (total.toFloat() / it).coerceAtMost(1f) })
                    }
                    ByteArray(total).also { output -> var offset = 0; chunks.forEach { it.copyInto(output, offset); offset += it.size } }
                }
                if (bytes != null) return bytes
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException || error is MailException) throw error
                // Never restart a partially consumed response automatically.
                if (!responseStarted && attempt++ < 2) { delay(250L shl attempt); continue }
                throw MailException(0, "Attachment download interrupted. Try again when connected.")
            }
        }
    }

}

@PublishedApi
internal fun retryDelay(response: HttpResponse, attempt: Int): Long =
    if (response.status.value == 429) response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.coerceIn(0, 60)?.times(1000) ?: (250L shl attempt)
    else 250L shl attempt
