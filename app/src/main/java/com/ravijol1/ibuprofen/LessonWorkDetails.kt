package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun LessonWorkDetails(assessment: LessonAssessment?, homework: List<LessonHomework>) {
    assessment?.let { item ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Assessment", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            schoolText(item.title)?.let { Text(it) }
            schoolText(item.gradeType)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            schoolText(item.summary)?.takeUnless { it == schoolText(item.title) }?.let { Text(it) }
        }
    }
    homework.forEachIndexed { index, item ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (assessment != null || index > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Homework", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            schoolText(item.title)?.let { Text(it) }
            item.dueDate?.takeIf(String::isNotBlank)?.let { raw ->
                val date = runCatching { LocalDate.parse(raw.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrDefault(raw)
                Text("Due $date", style = MaterialTheme.typography.labelMedium)
            }
            schoolText(item.summary)?.takeUnless { it == schoolText(item.title) }?.let { Text(it) }
        }
    }
}
