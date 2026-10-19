package com.cloudmail.app.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cloudmail.app.*

@Composable
internal fun SettingsScreen(state: AppState, vm: MailViewModel) {
    var logout by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Make room for your workflow", style = MaterialTheme.typography.headlineSmall)
        SectionTitle("Appearance")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system", "light", "dark").forEach { theme -> FilterChip(selected = state.theme == theme, onClick = { vm.updateSettings(theme, state.textScale, state.remoteImages) }, label = { Text(theme.replaceFirstChar { it.uppercase() }) }) } }
        SettingSwitch("Dynamic color", "Use your system wallpaper colors on supported Android devices.", state.dynamicColor) { vm.updateSettings(state.theme, state.textScale, state.remoteImages, it) }
        Text("Text size · ${(state.textScale * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(value = state.textScale, onValueChange = { vm.updateSettings(state.theme, it, state.remoteImages) }, valueRange = .85f..1.5f, steps = 12)
        Text("Message text scales with your system accessibility settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SectionTitle("Reading & privacy")
        SettingSwitch("Prefer HTML mail", "Use formatted mail when available. Plain text stays one tap away.", state.preferHtml) { vm.updateReadingSettings(it) }
        if (supportsHtmlRenderer()) SettingSwitch("External images", "Allow external images by default. This can reveal when you read a message.", state.remoteImages) { vm.updateSettings(state.theme, state.textScale, it) }
        else Text("Mail HTML is safely rendered as text on this platform. External images and scripts are never loaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.secureStorage) Text("This device cannot persist credentials securely. Your session is kept only in memory; sign in after restarting.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        SectionTitle("Sending")
        Text("Default sending address", style = MaterialTheme.typography.bodyMedium)
        state.accounts.forEach { account -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = state.defaultAccountId == account.id, role = Role.RadioButton, onClick = { vm.setDefaultAccount(account.id) }), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = state.defaultAccountId == account.id, onClick = null); Text(account.email, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) } }
        HorizontalDivider()
        SectionTitle("Gestures")
        Text("Swipe right", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("read" to "Read / unread", "star" to "Star", "none" to "Disabled").forEach { (action, label) -> FilterChip(selected = state.gestureRead == action, onClick = { vm.updateGestureSettings(action, state.gestureDelete) }, label = { Text(label) }) } }
        Text("Swipe left", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("delete" to "Delete", "star" to "Star", "none" to "Disabled").forEach { (action, label) -> FilterChip(selected = state.gestureDelete == action, onClick = { vm.updateGestureSettings(state.gestureRead, action) }, label = { Text(label) }) } }
        Text("Delete asks for confirmation and offers Undo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SectionTitle("Sync")
        Text("Background interval", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15, 30, 60).forEach { minutes -> FilterChip(selected = state.syncMinutes == minutes, onClick = { vm.updateSyncSettings(minutes) }, label = { Text("$minutes min") }) } }
        Text("Background timing follows system battery and network limits. Pull updates immediately with Sync now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = vm::refresh, enabled = !state.loading) { Icon(Icons.Default.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sync now") }
        HorizontalDivider()
        SectionTitle("Server & account")
        Text(state.serverUrl, style = MaterialTheme.typography.bodyMedium)
        Text(state.session?.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { logout = true }) { Icon(Icons.Default.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sign out") }
        Text("Signing out revokes this session and clears saved mail, attachments and drafts from this app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        SectionTitle("About")
        Text("Cloud Mail · Native multiplatform client", style = MaterialTheme.typography.bodyMedium)
        Text("Built with Kotlin, Compose Multiplatform, Ktor and SQLDelight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { licenses = true }) { Text("Open source licenses") }
    }
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text("Sign out of Cloud Mail?") }, text = { Text("Saved messages, attachments and local drafts will be cleared. Unsynced changes need a connection to reach your server.") }, confirmButton = { TextButton(onClick = { logout = false; vm.logout() }) { Text("Sign out", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { logout = false }) { Text("Cancel") } })
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Open source licenses") }, text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(OPEN_SOURCE_NOTICES, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) } }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Close") } })
}

@Composable
private fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f).padding(end = 16.dp)) { Text(title, style = MaterialTheme.typography.bodyMedium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, onCheckedChange = null) }
}
