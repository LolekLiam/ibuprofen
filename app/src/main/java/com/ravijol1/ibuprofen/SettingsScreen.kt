package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.ChildProfile
import com.ravijol1.ibuprofen.data.NetworkProvider

@Composable
internal fun SettingsScreen(reminders: Boolean, remindersError: String?, child: ChildProfile?,
    proxyEnabled: Boolean, proxyUrl: String, exactAlarmsAllowed: Boolean, notificationsAllowed: Boolean,
    onAllowPreciseReminders: () -> Unit, onOpenNotificationSettings: () -> Unit,
    onRemindersChange: (Boolean) -> Unit,
    onProxySave: (Boolean, String) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var draftEnabled by rememberSaveable(proxyEnabled) { mutableStateOf(proxyEnabled) }
    var draftUrl by rememberSaveable(proxyUrl) { mutableStateOf(proxyUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Settings", "Make the app work for you") }
        item {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Lesson reminders", style = MaterialTheme.typography.titleMedium)
                            Text("5 minutes before your lesson", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = reminders, onCheckedChange = onRemindersChange, enabled = child != null || reminders)
                    }
                    Text(child?.let { "For ${it.displayName ?: "your student"}${it.className?.let { name -> " · $name" }.orEmpty()}" }
                        ?: "Sign in and choose a student in My schedule first.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (reminders && !exactAlarmsAllowed) {
                        Text("Allow alarms and reminders in Android settings for precise timing. Otherwise notifications may arrive late.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onAllowPreciseReminders) { Text("Allow precise reminders") }
                    }
                    if (reminders && !notificationsAllowed) {
                        Text("Lesson notifications are turned off in Android settings.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onOpenNotificationSettings) { Text("Open notification settings") }
                    }
                }
            }
        }
        remindersError?.let { notice -> item { ErrorNotice(notice) } }
        item {
            OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Text("Follows your phone’s light or dark theme and uses wallpaper colors on Android 12 and later.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (proxyEnabled) item { AdminServerPanel(proxyUrl, onConfigure = { advanced = true }) }
        item { TextButton(onClick = { advanced = !advanced }, Modifier.fillMaxWidth()) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") } }
        if (advanced) {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Proxy connection", style = MaterialTheme.typography.titleMedium)
                        Text("Use your self-hosted server for public timetable caching. Your personal eAsistent account connects directly to eAsistent.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable proxy", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(draftEnabled, { draftEnabled = it })
                        }
                        OutlinedTextField(draftUrl, { draftUrl = it }, label = { Text("Proxy URL") }, placeholder = { Text("https://your-server") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            if (draftEnabled && NetworkProvider.getProxyApi(draftUrl) == null) {
                                failed = true; message = "Enter a valid proxy URL."
                            } else { onProxySave(draftEnabled, draftUrl.trim()); failed = false; message = "Settings saved." }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Save connection") }
                    }
                }
            }
            message?.let { notice -> item {
                if (failed) ErrorNotice(notice) else Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            } }
        }
        item {
            Text("ibuprofen · Open-source school timetables", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
