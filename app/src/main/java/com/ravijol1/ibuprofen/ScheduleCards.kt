package com.ravijol1.ibuprofen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.*
import java.time.format.DateTimeFormatter

@Composable
internal fun LessonPeriodCard(cell: PeriodCell.WithLessons, showClasses: Boolean = false) {
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    val changes = cell.lessons.filter { it.changeKind != null }
    var showChanges by remember(cell) { mutableStateOf(false) }
    val interaction = if (changes.isEmpty()) Modifier else Modifier.clickable(role = Role.Button,
        onClickLabel = "Show lesson changes", onClick = { showChanges = true })
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).then(interaction),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text("${cell.periodNumber}", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (cell.timeRange.start != cell.timeRange.endInclusive) {
                    Text("${cell.timeRange.start.format(clock)} – ${cell.timeRange.endInclusive.format(clock)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cell.lessons.forEach { lesson ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (showClasses) lesson.sourceClassLabel?.let { label ->
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(lesson.subjectTitle ?: lesson.subjectCode ?: "Lesson",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            textDecoration = if (lesson.isCancelled) TextDecoration.LineThrough else TextDecoration.None)
                        val details = listOfNotNull(lesson.teacherFullName ?: lesson.teacher,
                            lesson.room?.let { "Room $it" }, lesson.sourceClassLabel.takeUnless { showClasses }, lesson.groupLabel)
                        if (details.isNotEmpty()) Text(details.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        lesson.changeKind?.let { kind ->
                            Text("${kind.label} ⓘ", color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.labelLarge)
                        }
                        if (lesson.changeKind == null) lesson.notes.distinct().forEach { note ->
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (lesson.isCancelled) Text("Cancelled", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    if (showChanges) LessonChangesSheet(cell, changes) { showChanges = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LessonChangesSheet(cell: PeriodCell.WithLessons, changes: List<Lesson>, onDismiss: () -> Unit) {
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(changes.singleOrNull()?.changeKind?.label ?: "Lesson changes", style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(cell.periodNumber.takeIf { it > 0 }?.let { "Period $it" },
                    "${cell.timeRange.start.format(clock)} – ${cell.timeRange.endInclusive.format(clock)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            changes.forEachIndexed { index, lesson ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (index > 0) HorizontalDivider(Modifier.padding(bottom = 8.dp))
                        if (changes.size > 1) Text(lesson.changeKind?.label.orEmpty(), style = MaterialTheme.typography.labelLarge)
                        lesson.change?.originalSubject?.takeIf { it.isNotBlank() }?.let { original ->
                            Text("Originally scheduled", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(original, style = MaterialTheme.typography.titleMedium)
                        } ?: Text("Original lesson details are not provided.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (lesson.changeKind == LessonChangeKind.Assignment) "Activity" else "Replacement lesson",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(lesson.subjectTitle ?: lesson.subjectCode ?: "Lesson", style = MaterialTheme.typography.titleMedium)
                        (lesson.teacherFullName ?: lesson.teacher)?.takeIf { it.isNotBlank() }?.let { Text("Teacher: $it") }
                        lesson.room?.takeIf { it.isNotBlank() }?.let { Text("Room: $it") }
                        lesson.sourceClassLabel?.let { Text("Class: $it") }
                        lesson.groupLabel?.let { Text(it) }
                        (listOfNotNull(lesson.change?.description) + lesson.notes).filter { it.isNotBlank() }.distinct().forEach { Text(it) }
                        if (lesson.isCancelled) Text("Cancelled", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
