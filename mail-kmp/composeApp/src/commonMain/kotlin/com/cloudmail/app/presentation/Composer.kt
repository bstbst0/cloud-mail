@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.cloudmail.app.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cloudmail.app.*

@Composable
internal fun Composer(draft: Draft, state: AppState, vm: MailViewModel, onEditing: (Boolean) -> Unit) {
    var showRecipients by remember(draft.id) { mutableStateOf(draft.cc.isNotBlank() || draft.bcc.isNotBlank()) }
    var fromMenu by remember { mutableStateOf(false) }
    var editor by remember(draft.id) { mutableStateOf(TextFieldValue(draft.text)) }
    var rich by remember(draft.id) { mutableStateOf(RichText.restore(draft.text, draft.html)) }
    var discard by remember { mutableStateOf(false) }
    val editable = !state.sending && draft.operationId == null
    val canSend = !state.sending && !state.offline && (draft.operationId != null || (draft.to.isNotBlank() && draft.accountId > 0))
    DisposableEffect(Unit) { onDispose { onEditing(false) } }
    fun saveRich(next: RichText) { rich = next; vm.updateDraft(draft.copy(text = next.text, html = next.html())) }
    Dialog(onDismissRequest = { if (!state.sending) vm.closeComposer() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.sending, dismissOnClickOutside = !state.sending)) {
        Surface(Modifier.widthIn(max = 840.dp).fillMaxWidth().fillMaxHeight(.94f).padding(12.dp), shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
            Column(Modifier.imePadding()) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 480.dp
                    val sendAction: @Composable () -> Unit = {
                        FilledTonalButton(onClick = { if (draft.operationId != null) vm.checkSendStatus() else vm.send() }, enabled = canSend) {
                            if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(if (draft.operationId != null) Icons.Default.Refresh else Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.sending) "Please wait…" else if (draft.operationId != null) "Check delivery" else "Send")
                        }
                    }
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { vm.closeComposer() }, enabled = !state.sending) { Icon(Icons.Default.Close, "Close and save draft") }
                            Column(Modifier.weight(1f)) {
                                Text(if (draft.replyToId != null) "Reply" else "New message", style = MaterialTheme.typography.titleLarge)
                                Text("Draft saved automatically", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!compact) sendAction()
                        }
                        if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { sendAction() }
                    }
                }
                if (state.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        OutlinedButton(onClick = { fromMenu = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                            Text("From: ${state.accounts.firstOrNull { it.id == draft.accountId }?.email ?: "Choose an address"}", Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
                        }
                        DropdownMenu(expanded = fromMenu, onDismissRequest = { fromMenu = false }) {
                            state.accounts.forEach { account -> DropdownMenuItem(text = { Text(account.email) }, onClick = { vm.updateDraft(draft.copy(accountId = account.id)); fromMenu = false }) }
                        }
                    }
                    OutlinedTextField(draft.to, { vm.updateDraft(draft.copy(to = it)) }, Modifier.fillMaxWidth().onFocusChanged { onEditing(it.isFocused) }, label = { Text("To") }, supportingText = { Text("Separate recipients with commas or semicolons") }, enabled = editable, trailingIcon = { TextButton(onClick = { showRecipients = !showRecipients }) { Text("Cc/Bcc") } })
                    if (showRecipients) {
                        OutlinedTextField(draft.cc, { vm.updateDraft(draft.copy(cc = it)) }, Modifier.fillMaxWidth().onFocusChanged { onEditing(it.isFocused) }, label = { Text("Cc") }, enabled = editable)
                        OutlinedTextField(draft.bcc, { vm.updateDraft(draft.copy(bcc = it)) }, Modifier.fillMaxWidth().onFocusChanged { onEditing(it.isFocused) }, label = { Text("Bcc") }, supportingText = { Text("Hidden from other recipients") }, enabled = editable)
                    }
                    OutlinedTextField(draft.subject, { vm.updateDraft(draft.copy(subject = it)) }, Modifier.fillMaxWidth().onFocusChanged { onEditing(it.isFocused) }, label = { Text("Subject") }, enabled = editable)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf(Triple("b", "Bold selected text", Icons.Default.FormatBold), Triple("i", "Italic selected text", Icons.Default.FormatItalic), Triple("u", "Underline selected text", Icons.Default.FormatUnderlined)).forEach { (tag, label, icon) ->
                                IconButton(onClick = { saveRich(rich.toggle(editor.selection.min, editor.selection.max, tag)) }, enabled = editable && !editor.selection.collapsed) { Icon(icon, label) }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = vm::addAttachment, enabled = editable) { Icon(Icons.Default.AttachFile, "Add attachments") }
                        }
                    }
                    OutlinedTextField(editor.copy(annotatedString = rich.annotated()), { next -> editor = next; if (next.text != rich.text) saveRich(rich.edit(next.text)) }, Modifier.fillMaxWidth().heightIn(min = 220.dp).onFocusChanged { onEditing(it.isFocused) }, label = { Text("Message") }, enabled = editable, supportingText = { Text("Select text to apply bold, italic or underline.") })
                    draft.attachments.forEachIndexed { index, attachment ->
                        OutlinedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AttachFile, null, Modifier.size(20.dp)); Column(Modifier.weight(1f).padding(10.dp)) { Text(attachment.name, style = MaterialTheme.typography.bodyMedium); Text(formatSize(attachment.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick = { vm.removeAttachmentAt(index) }, enabled = editable) { Icon(Icons.Default.Close, "Remove ${attachment.name}") }
                        } }
                    }
                    state.sendStatus?.let { Text(it, color = if (it.contains("success", ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.offline) Text("You're offline. This draft is saved; send it when your connection returns.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (draft.sendStatus == "uncertain") Text("Delivery is being checked. Sending is disabled to avoid duplicate mail.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { discard = true }, enabled = editable) { Text("Discard draft", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = vm::closeComposer, enabled = !state.sending) { Text("Save & close") }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard this draft?") }, text = { Text("The saved message and its attachments will be removed from this device.") }, confirmButton = { TextButton(onClick = { discard = false; vm.discardDraft() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}
