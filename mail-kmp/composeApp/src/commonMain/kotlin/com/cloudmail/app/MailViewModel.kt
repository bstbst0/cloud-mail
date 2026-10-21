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
    private var closed = false
    private var searchJob: Job? = null
    private var searchActive = false
    private var searchGeneration = 0L
    private var syncJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshRequested = false
    private var cacheJob: Job? = null
    init {
        val server = platform.secret("server") ?: platform.defaultServerUrl
        val session = platform.secret("session")?.let { runCatching { MailJson.decodeFromString<Session>(it) }.getOrNull() }
        val validServer = runCatching { ApiClient.validatedServer(server, platform.allowLocalHttp) }.isSuccess
        if (session != null && validServer && !sessionExpired(session)) {
            attach(server, session); publishCache(); refresh()
        } else {
            if (session != null) {
                val owner = "$server#${session.userId}"
                platform.putSecret("session", null)
                val generation = authenticationGeneration
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { repository.clearOwnerIf(owner) { generation == authenticationGeneration || store.owner != owner } } }
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
        cacheJob?.cancel()
        cacheJob = sessionScope.launch {
            try { publishCacheNow() } catch (error: Throwable) { report(error) }
        }
    }
    private suspend fun publishCacheNow() = withContext(platform.cacheDispatcher) {
        val generation = store.generation
        val snapshot = _state.value
        if (_state.value.session == null) return@withContext
        val settings = store.settings()
        platform.configureSync(settings.syncMinutes)
        val cached = store.summaries().associateBy { it.id }
        val selected = _state.value.selected?.let { store.message(it.id) }
        val messages = cached.values.filter { !it.deleted && (snapshot.selectedAccountId == null || it.accountId == snapshot.selectedAccountId) && (if (snapshot.folder == "star") it.starred else it.folder == snapshot.folder) }.sortedWith(compareByDescending<Message> { it.receivedAt }.thenByDescending { it.id })
        val drafts = store.drafts()
        val accounts = store.accounts()
        currentCoroutineContext().ensureActive()
        if (store.generation != generation || _state.value.session == null) return@withContext
        _state.update { old -> if (old.folder != snapshot.folder || old.selectedAccountId != snapshot.selectedAccountId || old.query != snapshot.query) old else old.copy(drafts = drafts, accounts = accounts, messages = if (searchActive) old.messages.mapNotNull { found -> cached[found.id] }.filter { !it.deleted } else messages, selected = old.selected?.let { current -> if (cached[current.id]?.deleted != false) null else selected?.takeIf { it.id == current.id && !it.deleted } ?: current }, theme = settings.theme, textScale = settings.textScale, remoteImages = settings.remoteImages, defaultAccountId = settings.defaultAccountId, gestureRead = settings.gestureRead, gestureDelete = settings.gestureDelete, syncMinutes = settings.syncMinutes, dynamicColor = settings.dynamicColor, preferHtml = settings.preferHtml) }
    }
    private suspend fun report(error: Throwable, preserveBusy: Boolean = false) {
        currentCoroutineContext().ensureActive()
        if (error is CancellationException) throw error
        val unauthorized = error is MailException && error.code == 401
        _state.update { it.copy(error = error.message ?: "Something went wrong. Try again.", offline = error is MailException && error.code == 0, loading = if (preserveBusy) it.loading else false, sending = if (preserveBusy) it.sending else false) }
        if (unauthorized && _state.value.session == null) {
            _state.update { it.copy(error = "Sign-in failed. Check your email and password.") }
            return
        }
        if (unauthorized) {
            clearLocalSession("Session expired. Sign in again.")
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
                attach(validated, session); publishCache(); refresh()
            } catch (error: Throwable) { report(error) }
        }
    }
    fun refresh() {
        if (api == null) return
        if (refreshJob?.isActive == true) { refreshRequested = true; return }
        refreshJob = sessionScope.launch {
            do {
                refreshRequested = false
                _state.update { it.copy(loading = true, error = null, syncProcessed = 0) }
                try {
                    val initial = withContext(platform.cacheDispatcher) { store.cursor() == null }
                    val arrived = repository.sync { count ->
                        _state.update { it.copy(syncProcessed = count) }
                        if (initial && count in 1..10) publishCacheNow()
                    }
                    currentCoroutineContext().ensureActive()
                    publishCacheNow()
                    if (!initial) platform.notifyMail(arrived)
                    _state.update { it.copy(loading = false, offline = false) }
                } catch (error: Throwable) {
                    currentCoroutineContext().ensureActive()
                    try { publishCacheNow() } catch (cacheError: Throwable) {
                        if (cacheError is CancellationException) throw cacheError
                    }
                    report(error)
                }
            } while (refreshRequested && isActive)
        }
    }
    fun clearError() { _state.update { it.copy(error = null) } }
    fun setFolder(folder: String) { searchGeneration++; searchJob?.cancel(); searchActive = false; _state.update { it.copy(folder = folder, selected = null, query = "", searching = false) }; publishCache() }
    fun selectFolder(folder: String) = setFolder(folder)
    fun setAccount(id: Long?) { searchGeneration++; searchJob?.cancel(); searchActive = false; _state.update { it.copy(selectedAccountId = id, selected = null, query = "", searching = false) }; publishCache() }
    fun search(query: String, read: Boolean? = null, hasAttachment: Boolean? = null, after: String? = null, before: String? = null) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchActive = query.isNotBlank() || read != null || hasAttachment != null || after != null || before != null
        _state.update { it.copy(query = query, searching = searchActive) }
        if (!searchActive) { publishCache(); return }
        searchJob = sessionScope.launch {
            delay(300)
            try { val result = repository.search(query, read, hasAttachment, after, before, _state.value.folder, _state.value.selectedAccountId).filter { m -> !m.deleted && (_state.value.selectedAccountId == null || m.accountId == _state.value.selectedAccountId) && (if (_state.value.folder == "star") m.starred else m.folder == _state.value.folder) }; currentCoroutineContext().ensureActive(); _state.update { it.copy(messages = result, offline = false) } }
            catch (error: Throwable) { currentCoroutineContext().ensureActive(); _state.update { it.copy(messages = store.messages().filter { m -> !m.deleted && (_state.value.selectedAccountId == null || m.accountId == _state.value.selectedAccountId) && (if (_state.value.folder == "star") m.starred else m.folder == _state.value.folder) && (read == null || m.read == read) && (hasAttachment == null || m.attachments.isNotEmpty() == hasAttachment) && (after.isNullOrBlank() || m.receivedAt.take(10) >= after) && (before.isNullOrBlank() || m.receivedAt.take(10) <= before) && listOf(m.subject, m.fromAddress, m.to.joinToString(), m.text).any { it.contains(query, true) } }) }; report(error, preserveBusy = true) }
            finally { if (generation == searchGeneration) _state.update { it.copy(searching = false) } }
        }
    }
    fun select(message: Message?) {
        _state.update { it.copy(selected = message) }
        if (message == null) return
        if (!message.read) markRead(message, true)
        sessionScope.launch { try {
            withContext(platform.cacheDispatcher) { store.message(message.id) }?.let { cached ->
                if (_state.value.selected?.id == message.id) _state.update { it.copy(selected = cached) }
            }
            repository.detail(message.id); currentCoroutineContext().ensureActive(); publishCache() } catch (error: Throwable) { report(error) } }
    }
    private fun mutate(message: Message, kind: String, value: Boolean) { sessionScope.launch {
        try { withContext(platform.cacheDispatcher) { repository.action(message, kind, value, platform.randomId()) }; publishCacheNow(); refresh() }
        catch (error: Throwable) { report(error) }
    } }
    fun markRead(message: Message, value: Boolean) = mutate(message, "read", value)
    fun star(message: Message, value: Boolean) = mutate(message, "star", value)
    fun delete(message: Message) = mutate(message, "delete", true)
    fun undoDelete(message: Message) = mutate(message.copy(deleted = true), "delete", false)
    fun compose(mode: String = "new", message: Message? = null) {
        sessionScope.launch {
            try {
                val full = message?.let { withContext(platform.cacheDispatcher) { store.message(it.id) } }
                currentCoroutineContext().ensureActive()
                composeResolved(mode, full ?: message)
            } catch (error: Throwable) { report(error) }
        }
    }
    private fun composeResolved(mode: String, message: Message?) {
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
    private fun clearLocalSession(error: String? = null): Long {
        val owner = store.owner
        val generation = ++authenticationGeneration
        // Invalidate background credentials before waiting for in-flight cache work.
        platform.putSecret("session", null)
        loginJob?.cancel(); sessionJob.cancel(); searchJob?.cancel(); syncJob?.cancel(); refreshJob?.cancel(); cacheJob?.cancel(); searchActive = false; searchGeneration++
        // Keep the old partition fixed until cancelled background SQL completes.
        // The next login joins that session job before changing the owner.
        store.invalidate(); api = null
        _state.value = AppState(serverUrl = platform.secret("server") ?: platform.defaultServerUrl, secureStorage = platform.persistentSecureStorage, error = error)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { repository.clearOwnerIf(owner) { generation == authenticationGeneration || store.owner != owner } }
        }
        return generation
    }
    fun logout() {
        val client = api
        val generation = clearLocalSession()
        viewModelScope.launch {
            try { client?.logout() }
            catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (generation == authenticationGeneration) _state.update { it.copy(error = "Local data cleared. Server session revocation failed; it expires automatically.") }
            }
        }
    }
    /** Hosts without a ViewModelStore must release this model when their UI closes. */
    fun dispose() {
        if (closed) return
        closed = true
        loginJob?.cancel(); sessionJob.cancel(); viewModelScope.cancel(); httpClient.close()
    }
    override fun onCleared() { dispose(); super.onCleared() }
}
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun encodeAttachment(bytes: ByteArray): String = kotlin.io.encoding.Base64.Default.encode(bytes)

@OptIn(kotlin.time.ExperimentalTime::class)
private fun sessionExpired(session: Session): Boolean = session.expiresAt <= kotlin.time.Clock.System.now().epochSeconds

private fun validateAttachments(attachments: List<OutgoingAttachment>) {
    require(attachments.size <= 10) { "A message can contain at most 10 attachments." }
    require(attachments.sumOf { it.size } <= 20 * 1024 * 1024) { "Combined attachments must be smaller than 20 MB." }
}
