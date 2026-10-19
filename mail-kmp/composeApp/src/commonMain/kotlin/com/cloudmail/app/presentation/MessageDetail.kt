package com.cloudmail.app.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudmail.app.*

@Composable
internal fun MessageDetail(message: Message, state: AppState, vm: MailViewModel, onDelete: (Message) -> Unit) {
    var html by remember(message.id) { mutableStateOf(state.preferHtml && message.html.isNotBlank()) }
    var loadImages by remember(message.id) { mutableStateOf(state.remoteImages) }
    val htmlRenderer = supportsHtmlRenderer()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(if (message.folder == "sent") deliveryLabel(message.status) else "Received message", Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.labelMedium, color = if (message.status in listOf(3, 4, 8)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { vm.star(message, !message.starred) }) { Icon(if (message.starred) Icons.Default.Star else Icons.Default.StarBorder, if (message.starred) "Remove star" else "Star message") }
            IconButton(onClick = { vm.markRead(message, !message.read) }) { Icon(if (message.read) Icons.Default.MarkEmailUnread else Icons.Default.MarkEmailRead, if (message.read) "Mark unread" else "Mark read") }
            IconButton(onClick = { onDelete(message) }) { Icon(Icons.Default.DeleteOutline, "Delete message") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SelectionContainer { Text(message.subject.ifBlank { "(No subject)" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    SelectionContainer { Text(message.fromName.ifBlank { message.fromAddress }, style = MaterialTheme.typography.titleMedium) }
                    SelectionContainer { Text(message.fromAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    RecipientLine("To", message.to)
                    RecipientLine("Cc", message.cc)
                    RecipientLine("Bcc", message.bcc)
                    Text(message.receivedAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (message.html.isNotBlank()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = html, onClick = { html = true }, label = { Text(if (htmlRenderer) "HTML" else "HTML as text") })
                    FilterChip(selected = !html, onClick = { html = false }, label = { Text("Plain text") })
                    if (html && htmlRenderer) FilterChip(selected = loadImages, onClick = { loadImages = !loadImages }, label = { Text(if (loadImages) "External images enabled" else "Load external images") }, leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(16.dp)) })
                }
                if (!htmlRenderer) Text("This device uses a safe text rendering of HTML mail.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                if (html && message.html.isNotBlank()) HtmlMessage(message.html, loadImages, Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 640.dp))
                else SelectionContainer { Text(message.text.ifBlank { "This message has no plain text body. Switch to HTML to read its content." }, style = MaterialTheme.typography.bodyLarge) }
            }
            if (message.attachments.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Attachments · ${message.attachments.size}", style = MaterialTheme.typography.titleSmall)
                    message.attachments.forEach { attachment ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AttachFile, null, tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(attachment.name, style = MaterialTheme.typography.bodyMedium); Text("${formatSize(attachment.size)} · ${attachment.mimeType}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { vm.downloadAttachment(attachment) }, enabled = state.attachmentId == null) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Save") }
                                    TextButton(onClick = { vm.openAttachment(attachment) }, enabled = state.attachmentId == null) { Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Open") }
                                    TextButton(onClick = { vm.shareAttachment(attachment) }, enabled = state.attachmentId == null) { Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Share") }
                                }
                                if (state.attachmentId == attachment.id) { state.attachmentProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Downloading…", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.compose("reply", message) }) { Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Reply") }
                    OutlinedButton(onClick = { vm.compose("replyAll", message) }) { Icon(Icons.AutoMirrored.Filled.ReplyAll, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Reply all") }
                    OutlinedButton(onClick = { vm.compose("forward", message) }) { Icon(Icons.AutoMirrored.Filled.Forward, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Forward") }
                }
            }
        }
    }
}

@Composable
private fun RecipientLine(label: String, recipients: List<String>) {
    if (recipients.isNotEmpty()) SelectionContainer { Text("$label: ${recipients.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)}.${(bytes % (1024 * 1024)) * 10 / (1024 * 1024)} MB"
}

/** Values match the existing Worker's emailConst.status contract. */
internal fun deliveryLabel(status: Int): String = when (status) {
    1 -> "Sent · awaiting delivery confirmation"
    2 -> "Delivered"
    3 -> "Bounced"
    4 -> "Reported as spam"
    5 -> "Delivery delayed"
    6 -> "Saving message"
    8 -> "Sending failed"
    else -> "Sent message · delivery status unavailable"
}
