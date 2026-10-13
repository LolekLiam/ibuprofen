package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.PeriodCell
import java.time.format.DateTimeFormatter

@Composable
internal fun LessonPeriodCard(cell: PeriodCell.WithLessons, showClasses: Boolean = false) {
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text("${cell.periodNumber}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(8.dp))
                if (cell.timeRange.start != cell.timeRange.endInclusive) {
                    Text(cell.timeRange.start.format(clock), style = MaterialTheme.typography.labelLarge)
                    Text(cell.timeRange.endInclusive.format(clock), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        if (lesson.isSubstitution) Text("Substitution", color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelLarge)
                        lesson.notes.distinct().forEach { note ->
                            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (lesson.isCancelled) Text("Cancelled", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
