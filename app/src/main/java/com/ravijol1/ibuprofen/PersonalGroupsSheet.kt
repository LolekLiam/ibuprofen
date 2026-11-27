package com.ravijol1.ibuprofen

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalGroupsSheet(preferences: PersonalGroups, weekStart: LocalDate?, entries: List<TimetableGroupEntry>, onSave: (PersonalGroups) -> Unit, onDismiss: () -> Unit) {
    var choices by remember(preferences, weekStart) { mutableStateOf(preferences.choices) }
    var entryChoices by remember(preferences, weekStart) { mutableStateOf(preferences.entryChoices.orEmpty()) }
    val week = weekStart?.let(::groupWeekKey)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("My groups", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { choices = emptyMap(); entryChoices = emptyMap() }) { Text("Reset") }
                TextButton(onClick = {
                    // Replace the old subject/teacher rules for this week with explicit entry choices.
                    val retained = choices.filterKeys { week == null || !it.endsWith("@$week") }
                    onSave(preferences.copy(choices = retained, entryChoices = entryChoices)); onDismiss()
                }) { Text("Save") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    week?.let { Text("Week of ${LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy"))}", style = MaterialTheme.typography.titleSmall) }
                    Text("For entries containing several lessons, choose the ones you attend. Different subjects can be alternatives too. Choices apply to this week and its reminders; single-lesson entries stay unchanged. Older saved reminders may need one online refresh.", style = MaterialTheme.typography.bodySmall)
                }
                if (entries.isEmpty()) item { Text("No entries with multiple lessons in this week.") }
                items(entries, key = { it.key }) { entry ->
                    val selected = entryChoices[entry.key]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleSmall)
                        Text(entry.occurrences.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilterChip(selected = selected == null, onClick = { entryChoices = entryChoices - entry.key }, label = { Text("Show all") })
                        entry.options.forEach { option ->
                            val checked = selected?.contains(option.key) == true
                            Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = { enabled ->
                                val next = if (enabled) selected.orEmpty() + option.key else selected.orEmpty() - option.key
                                entryChoices = if (next.isEmpty()) entryChoices - entry.key else entryChoices + (entry.key to next)
                            }), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Text(option.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
                item { Text("Subject visibility · all weeks", style = MaterialTheme.typography.titleSmall) }
                items(preferences.subjects, key = { "subject:${it.key}" }) { subject ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(subject.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(modifier = Modifier.semantics { contentDescription = "Show ${subject.name}" }, checked = choices[subject.key]?.hidden != true,
                            onCheckedChange = { visible -> choices = choices + (subject.key to ScheduleGroupChoice(hidden = !visible)) })
                    }
                }
            }
        }
    }
}
