package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.ChildProfile

internal data class ProfileUi(val signedIn: Boolean, val profiles: List<ChildProfile>, val selected: ChildProfile?,
    val loading: Boolean, val error: String?, val onSelect: (ChildProfile) -> Unit, val onRetry: () -> Unit,
    val onSignIn: () -> Unit)

@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null, onRefresh: (() -> Unit)? = null, loading: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (onRefresh != null) TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
    }
}

@Composable
internal fun ErrorNotice(message: String, onRetry: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            onRetry?.let { TextButton(onClick = it) { Text("Try again") } }
        }
    }
}

@Composable
internal fun EmptyNotice(title: String, description: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudentSelector(ui: ProfileUi) {
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    when {
        !ui.signedIn -> EmptyNotice("Sign in to eAsistent", "Your personal school information will appear here.", "Sign in", ui.onSignIn)
        ui.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.error != null -> ErrorNotice(ui.error, ui.onRetry)
        ui.profiles.isEmpty() -> EmptyNotice("No student profiles", "No students were returned for this account.", "Try again", ui.onRetry)
        else -> {
            OutlinedCard(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ui.selected?.displayName ?: "Choose a student", style = MaterialTheme.typography.titleMedium)
                        val detail = listOfNotNull(ui.selected?.className, ui.selected?.schoolName).joinToString(" · ")
                        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (ui.profiles.size > 1) Text("⌄", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Choose a student", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
        if (ui.profiles.size > 5) OutlinedTextField(search, { search = it }, label = { Text("Search students") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(24.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(ui.profiles.filter { (it.displayName.orEmpty() + it.className.orEmpty()).contains(search, true) }, key = { it.uuid }) { student ->
                TextButton(onClick = { ui.onSelect(student); expanded = false }, Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(student.displayName ?: "Student", color = MaterialTheme.colorScheme.onSurface)
                        student.className?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}
