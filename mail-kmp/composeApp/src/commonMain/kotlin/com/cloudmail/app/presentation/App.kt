@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cloudmail.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cloudmail.app.*
import kotlinx.coroutines.launch

@Composable
fun App(viewModel: MailViewModel) {
    val state by viewModel.state.collectAsState()
    CloudMailTheme(state.theme, state.textScale, state.dynamicColor) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (state.session == null) LoginScreen(state, viewModel)
            else MailWorkspace(state, viewModel)
        }
    }
}

@Composable
internal fun LoginScreen(state: AppState, vm: MailViewModel) {
    var server by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val canLogin = !state.loading && server.isNotBlank() && email.isNotBlank() && password.isNotBlank()
    Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Default.Cloud, "Cloud Mail", Modifier.padding(20.dp).size(36.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text("Your mail. Your cloud.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("Sign in to your existing Cloud Mail server. Your addresses and messages stay together across devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(server, { server = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Server URL") }, placeholder = { Text("https://mail.example.com") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), enabled = !state.loading)
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Account") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), enabled = !state.loading)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (canLogin) vm.login(server, email, password) }), enabled = !state.loading)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = { vm.login(server, email, password); password = "" }, enabled = canLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (state.loading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Signing in…") } else Text("Sign in")
            }
            Text("A secure HTTPS connection is required. Passwords are never saved on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MailWorkspace(state: AppState, vm: MailViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.sendStatus) { if (state.sendStatus == "sent") snackbars.showSnackbar("Message sent") }
    val searchFocus = remember { FocusRequester() }
    val workspaceFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { workspaceFocus.requestFocus() }
    var showSearch by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var showDrafts by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Message?>(null) }
    var query by remember(state.query) { mutableStateOf(state.query) }
    var unreadOnly by remember { mutableStateOf(false) }
    var attachmentsOnly by remember { mutableStateOf(false) }
    var dateFilter by remember { mutableStateOf(false) }
    var after by remember { mutableStateOf("") }
    var before by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(showSearch) { if (showSearch) searchFocus.requestFocus() }
    fun confirmDelete(message: Message) { pendingDelete = message }
    fun search() { vm.search(query, read = if (unreadOnly) false else null, hasAttachment = if (attachmentsOnly) true else null, after = after.takeIf { dateFilter && it.isNotBlank() }, before = before.takeIf { dateFilter && it.isNotBlank() }) }
    val keyboard = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false
        else when {
            (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.K -> { showSearch = true; true }
            (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.N && state.accounts.isNotEmpty() -> { vm.compose(); true }
            event.key == Key.Escape -> { when { state.composer != null -> vm.closeComposer(); settings -> settings = false; showSearch -> { showSearch = false; vm.search("") }; state.selected != null -> vm.select(null) }; true }
            !editing && state.composer == null && !showSearch && !settings && event.key == Key.R && state.selected != null -> { vm.compose("reply", state.selected); true }
            !editing && state.composer == null && !showSearch && !settings && event.key == Key.Delete && state.selected != null -> { confirmDelete(state.selected); true }
            else -> false
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().then(keyboard).focusRequester(workspaceFocus).focusable()) {
        val expanded = maxWidth >= 1100.dp
        val split = maxWidth >= 720.dp
        val navigation: @Composable () -> Unit = {
            NavigationPanel(state, showDrafts, settings, onFolder = { vm.setFolder(it); settings = false; showDrafts = false; showSearch = false; unreadOnly = false; attachmentsOnly = false; dateFilter = false; after = ""; before = ""; scope.launch { drawer.close() } }, onAccount = { vm.setAccount(it); if (showSearch) search() }, onCompose = { vm.compose(); scope.launch { drawer.close() } }, onSettings = { settings = true; showDrafts = false; scope.launch { drawer.close() } }, onDrafts = { showDrafts = true; settings = false; showSearch = false; vm.select(null); scope.launch { drawer.close() } })
        }
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = !expanded, drawerContent = { if (!expanded) ModalDrawerSheet { navigation() } }) {
            Scaffold(snackbarHost = { SnackbarHost(snackbars) }, topBar = {
                if (!expanded) TopAppBar(
                    title = { Text(if (settings) "Settings" else if (showDrafts) "Drafts" else folderTitle(state.folder), style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = { IconButton(onClick = { if (!split && state.selected != null) vm.select(null) else scope.launch { drawer.open() } }) { Icon(if (!split && state.selected != null) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu, if (!split && state.selected != null) "Back to messages" else "Open navigation") } },
                    actions = { IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Default.Search, "Search all mail") }; IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, "Refresh messages") } },
                )
            }, floatingActionButton = {
                if (!settings && state.accounts.isNotEmpty() && state.composer == null && state.selected == null && !expanded) ExtendedFloatingActionButton(onClick = { vm.compose() }, icon = { Icon(Icons.Default.Edit, null) }, text = { Text("Compose") })
            }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (expanded) { Surface(Modifier.width(MailSpace.navigation).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) { navigation() }; VerticalDivider() }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.offline) StatusBanner("Offline · Your saved mail is available. Changes will sync when connected.", false, vm::refresh)
                        state.error?.let { StatusBanner(it, true, { if (showSearch) search() else vm.refresh() }, vm::clearError) }
                        if (state.loading || state.searching) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.loading) Text("Syncing mail · ${state.syncProcessed} updates saved", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        if (showSearch) {
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp).focusRequester(searchFocus), placeholder = { Text("Search subject, people and message content") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { IconButton(onClick = { query = ""; unreadOnly = false; attachmentsOnly = false; after = ""; before = ""; vm.search(""); showSearch = false }) { Icon(Icons.Default.Close, "Close search") } }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = unreadOnly, onClick = { unreadOnly = !unreadOnly; search() }, label = { Text("Unread") })
                                FilterChip(selected = attachmentsOnly, onClick = { attachmentsOnly = !attachmentsOnly; search() }, label = { Text("Has attachments") })
                                FilterChip(selected = dateFilter, onClick = { dateFilter = !dateFilter; if (!dateFilter) search() }, label = { Text("Date range") })
                            }
                            if (dateFilter) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(after, { after = it }, Modifier.weight(1f), label = { Text("After · YYYY-MM-DD") }, singleLine = true)
                                OutlinedTextField(before, { before = it }, Modifier.weight(1f), label = { Text("Before · YYYY-MM-DD") }, singleLine = true)
                            }
                            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Search includes mail beyond this page", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { search() }) { Text("Search") }
                            }
                        }
                        when {
                            settings -> SettingsScreen(state, vm)
                            showDrafts -> DraftList(state, vm)
                            split -> Row(Modifier.weight(1f)) {
                                Column(Modifier.width(if (expanded) MailSpace.list else 320.dp).fillMaxHeight()) {
                                    ListHeader(state, vm, onSearch = if (expanded) ({ showSearch = true }) else null)
                                    MessageList(state, vm, ::confirmDelete)
                                }
                                VerticalDivider()
                                Box(Modifier.weight(1f).fillMaxHeight()) { state.selected?.let { MessageDetail(it, state, vm, ::confirmDelete) } ?: EmptyPane("A little space to focus", "Choose a message to read it here.", Icons.Default.MarkEmailRead) }
                            }
                            state.selected != null -> MessageDetail(state.selected, state, vm, ::confirmDelete)
                            else -> { ListHeader(state, vm); MessageList(state, vm, ::confirmDelete) }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { message ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete this message?") }, text = { Text(message.subject.ifBlank { "(No subject)" }) }, confirmButton = { TextButton(onClick = {
            pendingDelete = null
            vm.delete(message)
            scope.launch { if (snackbars.showSnackbar("Message deleted", "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) vm.undoDelete(message) }
        }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep message") } })
    }
    state.composer?.let { Composer(it, state, vm, onEditing = { editing = it }) }
}

@Composable
private fun NavigationPanel(state: AppState, showDrafts: Boolean, showSettings: Boolean, onFolder: (String) -> Unit, onAccount: (Long?) -> Unit, onCompose: () -> Unit, onSettings: () -> Unit, onDrafts: () -> Unit) {
    Column(Modifier.width(MailSpace.navigation).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.padding(12.dp, 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary); Text("Cloud Mail", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        FilledTonalButton(onCompose, Modifier.fillMaxWidth().padding(bottom = 16.dp).heightIn(min = 48.dp), enabled = state.accounts.isNotEmpty()) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(10.dp)); Text("Compose") }
        listOf(Triple("inbox", "Inbox", Icons.Default.Inbox), Triple("sent", "Sent", Icons.AutoMirrored.Filled.Send), Triple("star", "Starred", Icons.Default.Star)).forEach { (folder, label, icon) ->
            NavigationDrawerItem(label = { Text(label) }, selected = state.folder == folder && !showDrafts && !showSettings, onClick = { onFolder(folder) }, icon = { Icon(icon, null) }, modifier = Modifier.heightIn(min = 48.dp))
        }
        NavigationDrawerItem(label = { Text("Drafts") }, selected = showDrafts, onClick = onDrafts, icon = { Icon(Icons.Default.Drafts, null) }, badge = { if (state.drafts.isNotEmpty()) Text(state.drafts.size.toString()) })
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("YOUR ADDRESSES", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { onAccount(null) }, modifier = Modifier.fillMaxWidth()) { Text("All addresses", Modifier.weight(1f)); if (state.selectedAccountId == null) Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.accounts.forEach { account ->
                TextButton(onClick = { onAccount(account.id) }, modifier = Modifier.fillMaxWidth()) { Text(account.email, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall); if (state.selectedAccountId == account.id) Icon(Icons.Default.Check, "Selected", Modifier.size(16.dp)) }
            }
        }
        NavigationDrawerItem(label = { Text("Settings") }, selected = showSettings, onClick = onSettings, icon = { Icon(Icons.Default.Settings, null) })
    }
}

@Composable
private fun DraftList(state: AppState, vm: MailViewModel) {
    if (state.drafts.isEmpty()) EmptyPane("A clean writing desk", "Saved drafts will appear here. Compose a message to get started.", Icons.Default.Edit)
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Drafts", style = MaterialTheme.typography.headlineSmall); Text("Saved on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.drafts, key = { it.id }) { draft ->
            OutlinedCard(onClick = { vm.openDraft(draft) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(draft.subject.ifBlank { "(No subject)" }, style = MaterialTheme.typography.titleMedium)
                    Text("To: ${draft.to.ifBlank { "No recipients yet" }}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(draft.text.take(180), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (draft.operationId != null) Text("Delivery status: ${draft.sendStatus ?: "pending"} · Open to check", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    if (draft.attachments.isNotEmpty()) Text("${draft.attachments.size} attachments", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

internal fun folderTitle(folder: String) = when(folder) { "sent" -> "Sent"; "star" -> "Starred"; else -> "Inbox" }

@Composable
private fun ListHeader(state: AppState, vm: MailViewModel, onSearch: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(folderTitle(state.folder), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(if (state.query.isBlank()) "${state.messages.size} messages" else "Results for “${state.query}”", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        onSearch?.let { IconButton(onClick = it) { Icon(Icons.Default.Search, "Search all mail") } }
        IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, "Sync now") }
    }
}

@Composable
private fun MessageList(state: AppState, vm: MailViewModel, onDelete: (Message) -> Unit) {
    val haptics = LocalHapticFeedback.current
    if (state.messages.isEmpty()) {
        Box(Modifier.fillMaxSize()) { if (state.loading || state.searching) CircularProgressIndicator(Modifier.align(Alignment.Center)) else EmptyPane(if (state.query.isBlank()) "You're all caught up" else "No matching messages", if (state.query.isBlank()) "New mail will appear here. Pull your cloud into view with Refresh." else "Try another name or keyword.", Icons.Default.Inbox, vm::refresh) }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(state.messages, key = { it.id }) { message ->
            var contextMenu by remember(message.id) { mutableStateOf(false) }
            val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                when (value) {
                    SwipeToDismissBoxValue.StartToEnd -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (state.gestureRead == "star") vm.star(message, !message.starred) else vm.markRead(message, !message.read); false }
                    SwipeToDismissBoxValue.EndToStart -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (state.gestureDelete == "star") vm.star(message, !message.starred) else onDelete(message); false }
                    else -> false
                }
            })
            Box(Modifier.pointerInput(message.id) { awaitPointerEventScope { while (true) { val event = awaitPointerEvent(); if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) { contextMenu = true; event.changes.forEach { it.consume() } } } } }) {
            SwipeToDismissBox(state = dismiss, backgroundContent = {
                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Icon(if (state.gestureRead == "star") Icons.Default.Star else Icons.Default.MarkEmailRead, "Read action"); Icon(if (state.gestureDelete == "star") Icons.Default.Star else Icons.Default.Delete, "Delete action") }
            }, enableDismissFromStartToEnd = state.gestureRead != "none", enableDismissFromEndToStart = state.gestureDelete != "none") {
                MessageRow(message, selected = state.selected?.id == message.id, onClick = { vm.select(message) }, onStar = { vm.star(message, !message.starred) })
            }
            DropdownMenu(expanded = contextMenu, onDismissRequest = { contextMenu = false }) {
                DropdownMenuItem(text = { Text("Open message") }, onClick = { contextMenu = false; vm.select(message) })
                DropdownMenuItem(text = { Text(if (message.read) "Mark unread" else "Mark read") }, onClick = { contextMenu = false; vm.markRead(message, !message.read) })
                DropdownMenuItem(text = { Text(if (message.starred) "Remove star" else "Star") }, onClick = { contextMenu = false; vm.star(message, !message.starred) })
                DropdownMenuItem(text = { Text("Reply") }, onClick = { contextMenu = false; vm.compose("reply", message) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { contextMenu = false; onDelete(message) })
            }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
        }
    }
}

@Composable
private fun MessageRow(message: Message, selected: Boolean, onClick: () -> Unit, onStar: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val person = if (message.folder == "sent") message.to.joinToString(", ").ifBlank { "No recipients" } else message.fromName.ifBlank { message.fromAddress }
    Surface(onClick = onClick, color = bg, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${if (!message.read) "Unread, " else ""}$person, ${message.subject}, ${message.receivedAt}" }) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) { Text(person.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(person, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (message.read) FontWeight.Normal else FontWeight.Bold)
                    Spacer(Modifier.width(6.dp)); Text(message.receivedAt.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(message.subject.ifBlank { "(No subject)" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (message.read) FontWeight.Normal else FontWeight.SemiBold)
                Text(remember(message.text) { message.text.take(512).replace(Regex("\\s+"), " ").take(180) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (message.attachments.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AttachFile, null, Modifier.size(14.dp)); Text(" ${message.attachments.size} attachment${if (message.attachments.size == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall) }
                if (message.folder == "sent") Text(deliveryLabel(message.status), style = MaterialTheme.typography.labelSmall, color = if (message.status in listOf(3, 4, 8)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onStar, Modifier.size(48.dp)) { Icon(if (message.starred) Icons.Default.Star else Icons.Default.StarBorder, if (message.starred) "Remove star" else "Star message", tint = if (message.starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
internal fun EmptyPane(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, retry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 340.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            retry?.let { TextButton(it) { Text("Refresh") } }
        }
    }
}

@Composable
internal fun StatusBanner(text: String, error: Boolean, retry: () -> Unit, dismiss: (() -> Unit)? = null) {
    Surface(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(retry) { Text("Retry") }
            dismiss?.let { IconButton(it) { Icon(Icons.Default.Close, "Dismiss error", Modifier.size(18.dp)) } }
        }
    }
}
