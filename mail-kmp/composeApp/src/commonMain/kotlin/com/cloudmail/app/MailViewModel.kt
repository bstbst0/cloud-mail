package com.cloudmail.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MailViewModel(private val platform: PlatformServices = createPlatformServices()) : ViewModel() {
    private val store = MailStore(platform)
    private val _state = MutableStateFlow(AppState(secureStorage = platform.persistentSecureStorage))
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val httpClient = platform.httpClient()
    private var sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var sessionScope = CoroutineScope(viewModelScope.coroutineContext + sessionJob)
    private var api: ApiClient? = null
    private val repository = MailRepository(store, { sessionJob.isActive && _state.value.session != null }) { api ?: error("Sign in first") }
    private var loginJob: Job? = null
    private var authenticationGeneration = 0L
    private var searchJob: Job? = null
    private var searchActive = false
    private var syncJob: Job? = null
    init {
        val server = platform.secret("server") ?: platform.defaultServerUrl
        val session = platform.secret("session")?.let { runCatching { MailJson.decodeFromString<Session>(it) }.getOrNull() }
        val validServer = runCatching { ApiClient.validatedServer(server, platform.allowLocalHttp) }.isSuccess
        if (session != null && validServer && !sessionExpired(session)) {
            attach(server, session); publishCache(); refresh()
        } else {
            if (session != null) {
                store.owner = "$server#${session.userId}"; store.clear(); store.owner = ""
                platform.putSecret("session", null)
            }
            _state.update { it.copy(serverUrl = server) }
        }
    }
    private fun attach(server: String, session: Session) {
        store.owner = "$server#${session.userId}"
        _state.update { it.copy(serverUrl = server, session = session) }
        api = ApiClient(httpClient, server) { session.token }
        startPolling()
    }
    private fun startPolling() {
        syncJob?.cancel()
        syncJob = sessionScope.launch { while (isActive) { delay(if (_state.value.offline) 30_000L else _state.value.syncMinutes.coerceAtLeast(15) * 60_000L); refresh() } }
    }
    private fun publishCache() {
        if (_state.value.session == null) return
        val settings = store.settings()
        platform.configureSync(settings.syncMinutes)
        val cached = store.messages().associateBy { it.id }
        val messages = cached.values.filter { !it.deleted && (_state.value.selectedAccountId == null || it.accountId == _state.value.selectedAccountId) && (if (_state.value.folder == "star") it.starred else it.folder == _state.value.folder) }.sortedWith(compareByDescending<Message> { it.receivedAt }.thenByDescending { it.id })
        _state.update { old -> old.copy(drafts = store.drafts(), accounts = store.accounts(), messages = if (searchActive) old.messages.mapNotNull { found -> cached[found.id] }.filter { !it.deleted } else messages, selected = old.selected?.let { selected -> cached[selected.id]?.takeUnless { it.deleted } }, theme = settings.theme, textScale = settings.textScale, remoteImages = settings.remoteImages, defaultAccountId = settings.defaultAccountId, gestureRead = settings.gestureRead, gestureDelete = settings.gestureDelete, syncMinutes = settings.syncMinutes, dynamicColor = settings.dynamicColor, preferHtml = settings.preferHtml) }
    }
    private suspend fun report(error: Throwable) {
        currentCoroutineContext().ensureActive()
        if (error is CancellationException) throw error
        val unauthorized = error is MailException && error.code == 401
        _state.update { it.copy(error = error.message ?: "Something went wrong. Try again.", offline = error is MailException && error.code == 0, loading = false, sending = false) }
        if (unauthorized && _state.value.session == null) {
            _state.update { it.copy(error = "Sign-in failed. Check your email and password.") }
            return
        }
        if (unauthorized) {
            // An expired session cannot leave an unlocked mailbox on disk.
            val server = _state.value.serverUrl
            authenticationGeneration++; sessionJob.cancel(); store.clear(); store.owner = ""
            platform.putSecret("session", null); api = null
            _state.value = AppState(serverUrl = server, secureStorage = platform.persistentSecureStorage, error = "Session expired. Sign in again.")
        }
    }
    fun login(server: String, email: String, password: String) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        val generation = ++authenticationGeneration
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            try {
                sessionJob.cancelAndJoin(); sessionJob = SupervisorJob(viewModelScope.coroutineContext[Job]); sessionScope = CoroutineScope(viewModelScope.coroutineContext + sessionJob)
                val validated = ApiClient.validatedServer(server, platform.allowLocalHttp)
                val session = ApiClient(httpClient, validated) { null }.login(email.trim(), password)
                currentCoroutineContext().ensureActive()
                if (generation != authenticationGeneration) return@launch
                platform.putSecret("server", validated); platform.putSecret("session", MailJson.encodeToString(session))
                attach(validated, session); publishCache(); repository.sync(); currentCoroutineContext().ensureActive(); publishCache()
                _state.update { it.copy(loading = false, offline = false) }
            } catch (error: Throwable) { report(error) }
        }
    }
    fun refresh() {
        if (api == null) return
        sessionScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try { val initial = store.cursor() == null; val arrived = repository.sync(); currentCoroutineContext().ensureActive(); publishCache(); if (!initial) platform.notifyMail(arrived); _state.update { it.copy(loading = false, offline = false) } }
            catch (error: Throwable) { currentCoroutineContext().ensureActive(); publishCache(); report(error) }
        }
    }
    fun clearError() { _state.update { it.copy(error = null) } }
    fun setFolder(folder: String) { searchJob?.cancel(); searchActive = false; _state.update { it.copy(folder = folder, selected = null, query = "") }; publishCache() }
    fun selectFolder(folder: String) = setFolder(folder)
    fun setAccount(id: Long?) { searchJob?.cancel(); searchActive = false; _state.update { it.copy(selectedAccountId = id, selected = null, query = "") }; publishCache() }
    fun search(query: String, read: Boolean? = null, hasAttachment: Boolean? = null, after: String? = null, before: String? = null) {
        searchJob?.cancel(); _state.update { it.copy(query = query) }
        searchActive = query.isNotBlank() || read != null || hasAttachment != null || after != null || before != null
        if (!searchActive) { publishCache(); return }
        searchJob = sessionScope.launch {
            delay(300)
            try { val result = repository.search(query, read, hasAttachment, after, before, _state.value.folder, _state.value.selectedAccountId).filter { m -> !m.deleted && (_state.value.selectedAccountId == null || m.accountId == _state.value.selectedAccountId) && (if (_state.value.folder == "star") m.starred else m.folder == _state.value.folder) }; currentCoroutineContext().ensureActive(); _state.update { it.copy(messages = result, offline = false, loading = false) } }
            catch (error: Throwable) { currentCoroutineContext().ensureActive(); _state.update { it.copy(messages = store.messages().filter { m -> !m.deleted && (_state.value.selectedAccountId == null || m.accountId == _state.value.selectedAccountId) && (if (_state.value.folder == "star") m.starred else m.folder == _state.value.folder) && (read == null || m.read == read) && (hasAttachment == null || m.attachments.isNotEmpty() == hasAttachment) && (after.isNullOrBlank() || m.receivedAt.take(10) >= after) && (before.isNullOrBlank() || m.receivedAt.take(10) <= before) && listOf(m.subject, m.fromAddress, m.to.joinToString(), m.text).any { it.contains(query, true) } }) }; report(error) }
        }
    }
    fun select(message: Message?) {
        _state.update { it.copy(selected = message) }
        if (message == null) return
        if (!message.read) markRead(message, true)
        sessionScope.launch { try { repository.detail(message.id); currentCoroutineContext().ensureActive(); publishCache() } catch (error: Throwable) { report(error) } }
    }
    private fun mutate(message: Message, kind: String, value: Boolean) { repository.action(message, kind, value, platform.randomId()); publishCache(); refresh() }
    fun markRead(message: Message, value: Boolean) = mutate(message, "read", value)
    fun star(message: Message, value: Boolean) = mutate(message, "star", value)
    fun delete(message: Message) = mutate(message, "delete", true)
    fun undoDelete(message: Message) = mutate(message.copy(deleted = true), "delete", false)
    fun compose(mode: String = "new", message: Message? = null) {
        val restored = if (mode == "new") store.drafts().firstOrNull() else null
        val account = message?.accountId?.takeIf { id -> _state.value.accounts.any { it.id == id } } ?: _state.value.defaultAccountId?.takeIf { id -> _state.value.accounts.any { it.id == id } } ?: _state.value.accounts.firstOrNull()?.id ?: return
        val quote = message?.let { "\n\nOn ${it.receivedAt}, ${it.fromAddress} wrote:\n${it.text}" } ?: ""
        val draft = restored ?: Draft(platform.randomId(), account,
            to = if (mode in listOf("reply", "replyAll")) { if (message?.folder == "sent") message.to.joinToString(", ") else message?.fromAddress ?: "" } else "",
            cc = if (mode == "replyAll") (message?.to.orEmpty() + message?.cc.orEmpty()).filter { address -> _state.value.accounts.none { it.email.equals(address, true) } && address != message?.fromAddress }.distinct().joinToString(", ") else "",
            subject = message?.let { if (mode == "forward") "Fwd: ${it.subject}" else "Re: ${it.subject}" } ?: "",
            text = quote, replyToId = if (mode in listOf("reply", "replyAll")) message?.id else null)
        store.saveDraft(draft); _state.update { it.copy(composer = draft, sendStatus = draft.sendStatus) }
        if (mode == "forward" && message != null) sessionScope.launch {
            try {
                require(message.attachments.size <= 10) { "A message can contain at most 10 attachments. Add the files you want to forward manually." }
                require(message.attachments.sumOf { it.size } <= 20 * 1024 * 1024) { "Combined attachments must be smaller than 20 MB." }
                val forwarded = mutableListOf<OutgoingAttachment>()
                for (attachment in message.attachments) {
                    val file = OutgoingAttachment(attachment.name, attachment.mimeType, encodeAttachment(repository.attachment(attachment.id)))
                    validateAttachments(forwarded + file)
                    forwarded += file
                }
                currentCoroutineContext().ensureActive()
                _state.value.composer?.takeIf { it.id == draft.id }?.let { updateDraft(it.copy(attachments = it.attachments + forwarded)) }
            } catch (error: Throwable) { report(error) }
        }
    }
    fun updateDraft(draft: Draft) {
        if (_state.value.session == null || _state.value.composer?.id != draft.id || _state.value.accounts.none { it.id == draft.accountId } || _state.value.sending || draft.operationId != null) return
        try { validateAttachments(draft.attachments) }
        catch (error: IllegalArgumentException) { _state.update { it.copy(error = error.message) }; return }
        store.saveDraft(draft); _state.update { it.copy(composer = draft, drafts = store.drafts()) }
    }
    fun openDraft(draft: Draft) { _state.update { it.copy(composer = store.drafts().find { saved -> saved.id == draft.id }, sendStatus = draft.sendStatus) } }
    fun closeComposer() { _state.update { it.copy(composer = null) } }
    fun discardDraft() { _state.value.composer?.let { store.deleteDraft(it.id) }; publishCache(); closeComposer() }
    fun send() {
        val original = _state.value.composer ?: return
        if (_state.value.sending) return
        if (original.operationId != null) { checkSendStatus(); return }
        if (original.to.isBlank()) { _state.update { it.copy(error = "Add at least one recipient.") }; return }
        try { validateAttachments(original.attachments) } catch (error: IllegalArgumentException) { _state.update { it.copy(error = error.message) }; return }
        val draft = original.copy(operationId = platform.randomId(), sendStatus = "pending")
        store.saveDraft(draft); _state.update { it.copy(composer = draft, sending = true, sendStatus = "pending") }
        sessionScope.launch {
            try { val result = repository.send(draft); currentCoroutineContext().ensureActive(); handleSendResult(draft, result) }
            catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                if (error is CancellationException) throw error
                val definitive = error is MailException && error.code in listOf(400, 403, 404, 413, 422)
                val saved = if (definitive) draft.copy(operationId = null, sendStatus = "failed") else draft.copy(sendStatus = "uncertain")
                store.saveDraft(saved); _state.update { it.copy(composer = saved, sending = false, sendStatus = saved.sendStatus) }; report(error)
            }
        }
    }
    fun checkSendStatus() {
        val draft = _state.value.composer ?: return
        val id = draft.operationId ?: return
        sessionScope.launch { _state.update { it.copy(sending = true) }; try { val result = repository.sendStatus(id); currentCoroutineContext().ensureActive(); handleSendResult(draft, result) } catch (error: Throwable) { report(error) } }
    }
    private fun handleSendResult(draft: Draft, result: SendResult) {
        if (result.status == "sent") { store.deleteDraft(draft.id); _state.update { it.copy(composer = null, sending = false, sendStatus = "sent") }; refresh() }
        else { val persisted = draft.copy(sendStatus = result.status, operationId = if (result.status == "failed") null else draft.operationId); store.saveDraft(persisted); _state.update { it.copy(composer = persisted, sending = false, sendStatus = result.status, error = "Send status: ${result.status}. Check status before creating another message.") } }
    }
    fun addAttachment() { sessionScope.launch { try { val picked = platform.pickAttachments(); currentCoroutineContext().ensureActive(); _state.value.composer?.let { draft -> validateAttachments(draft.attachments + picked); updateDraft(draft.copy(attachments = draft.attachments + picked)) } } catch (error: Throwable) { report(error) } } }
    fun removeAttachmentAt(index: Int) { _state.value.composer?.let { draft -> updateDraft(draft.copy(attachments = draft.attachments.filterIndexed { i, _ -> i != index })) } }
    fun removeAttachment(name: String) { _state.value.composer?.let { updateDraft(it.copy(attachments = it.attachments.filterNot { attachment -> attachment.name == name })) } }
    private fun attachment(attachment: Attachment, action: String) {
        if (_state.value.attachmentId != null) return
        _state.update { it.copy(attachmentId = attachment.id, attachmentProgress = null) }
        sessionScope.launch {
            try {
                val bytes = repository.attachment(attachment.id) { progress -> if (sessionJob.isActive) _state.update { it.copy(attachmentProgress = progress) } }
                currentCoroutineContext().ensureActive()
                platform.saveAttachment(attachment.name, attachment.mimeType, bytes, action)
            }
            catch (error: Throwable) { report(error) }
            finally { if (currentCoroutineContext().isActive) _state.update { it.copy(attachmentId = null, attachmentProgress = null) } }
        }
    }
    fun downloadAttachment(attachment: Attachment) = attachment(attachment, "download")
    fun openAttachment(attachment: Attachment) = attachment(attachment, "open")
    fun shareAttachment(attachment: Attachment) = attachment(attachment, "share")
    private fun settings(settings: Settings) { store.saveSettings(settings); publishCache() }
    fun updateSettings(theme: String, textScale: Float, remoteImages: Boolean, dynamicColor: Boolean = _state.value.dynamicColor) = settings(store.settings().copy(theme = theme, textScale = textScale.coerceIn(0.8f, 1.5f), remoteImages = remoteImages, dynamicColor = dynamicColor))
    fun updateReadingSettings(preferHtml: Boolean) = settings(store.settings().copy(preferHtml = preferHtml))
    fun setDefaultAccount(id: Long?) = settings(store.settings().copy(defaultAccountId = id))
    fun updateSyncSettings(syncMinutes: Int) { val minutes = syncMinutes.coerceIn(15, 1440); settings(store.settings().copy(syncMinutes = minutes)); platform.configureSync(minutes); startPolling() }
    fun updateGestureSettings(read: String, delete: String) = settings(store.settings().copy(gestureRead = read, gestureDelete = delete))
    fun logout() {
        val client = api
        val generation = ++authenticationGeneration
        // Revoke local access immediately. Remote revocation may be slow or offline,
        // and must never erase a subsequent login or use its replacement token.
        loginJob?.cancel(); sessionJob.cancel(); searchJob?.cancel(); syncJob?.cancel(); searchActive = false
        store.clear(); store.owner = ""
        platform.putSecret("session", null); api = null
        _state.value = AppState(serverUrl = platform.secret("server") ?: platform.defaultServerUrl, secureStorage = platform.persistentSecureStorage)
        viewModelScope.launch {
            try { client?.logout() }
            catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation == authenticationGeneration) _state.update { it.copy(error = "Local data cleared. Server session revocation failed; it expires automatically.") }
            }
        }
    }
    override fun onCleared() { loginJob?.cancel(); sessionJob.cancel(); httpClient.close(); super.onCleared() }
}
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun encodeAttachment(bytes: ByteArray): String = kotlin.io.encoding.Base64.Default.encode(bytes)

@OptIn(kotlin.time.ExperimentalTime::class)
private fun sessionExpired(session: Session): Boolean = session.expiresAt <= kotlin.time.Clock.System.now().epochSeconds

private fun validateAttachments(attachments: List<OutgoingAttachment>) {
    require(attachments.size <= 10) { "A message can contain at most 10 attachments." }
    require(attachments.sumOf { it.size } <= 20 * 1024 * 1024) { "Combined attachments must be smaller than 20 MB." }
}
