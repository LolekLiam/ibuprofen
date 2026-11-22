package com.ravijol1.ibuprofen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import java.util.Locale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun GradesScreen(auth: AuthRepository, profileUi: ProfileUi, onSessionExpired: () -> Unit) {
    var data by remember { mutableStateOf<MobileGrades?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var showSourceInfo by remember { mutableStateOf(false) }
    var semesterId by remember { mutableStateOf<Int?>(null) }
    val student = profileUi.selected
    val hasPlus = student?.let { it.plusEnabled || it.subscriptionStatus.equals("plus", true) } == true
    LaunchedEffect(student?.uuid, hasPlus, revision) {
        data = student?.let { auth.cachedGrades(it.uuid, hasPlus, it.schoolYear) }; error = null; pages = 0; loading = false; showSourceInfo = false; semesterId = null
        val child = student ?: return@LaunchedEffect
        loading = true
        try { data = auth.getMobileGrades(child.uuid, hasPlus, child.schoolYear) { pages = it }
        } catch (e: CancellationException) { throw e
        } catch (e: SessionExpiredException) { onSessionExpired()
        } catch (e: Exception) { error = e.message
        } finally { loading = false }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Grades", onRefresh = { if (student == null) profileUi.onRetry() else revision++ }, loading = loading || profileUi.loading) }
        if (student == null) item { StudentSelector(profileUi) }
        item { Text("Written: red · Oral: blue · Other: green", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (student != null) {
            if (loading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (pages > 0) Text("Refreshing grade history…", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { message -> item { ErrorNotice(message) { revision++ } } }
            if (data != null && (loading || error != null)) item { Text("Showing saved grades while refreshing. They may be out of date.", style = MaterialTheme.typography.bodySmall) }
            data?.let { grades ->
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(student.schoolYear.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (grades.source == GradeSource.Notifications) TextButton(onClick = { showSourceInfo = true }) { Text("From notifications ⓘ") }
                    }
                    grades.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (!grades.historyComplete) Text("Some history could not be loaded. Refresh to try again.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (grades.source == GradeSource.Plus) {
                        val semesters = grades.subjects.flatMap { it.semesters.orEmpty() }.map { it.id }.distinct().sorted()
                        if (semesters.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = semesterId == null, onClick = { semesterId = null }, label = { Text("All") })
                            semesters.forEach { id -> FilterChip(selected = semesterId == id, onClick = { semesterId = id }, label = { Text("Semester $id") }) }
                        }
                    }
                }
                if (grades.subjects.isEmpty()) item {
                    EmptyNotice(if (grades.source == GradeSource.Plus) "No subjects available" else "No grades recovered",
                        if (grades.source == GradeSource.Plus) "Your gradebook has no subjects for this profile."
                        else "No grade notifications were found for this school year.")
                }
                items(grades.subjects, key = { "${it.id}:${it.name}" }) { subject ->
                    key(student.uuid) { SubjectGradeRow(subject, grades.source, semesterId) }
                }
            }
        }
    }
    if (showSourceInfo) ModalBottomSheet(onDismissRequest = { showSourceInfo = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Grades from notifications", style = MaterialTheme.typography.titleLarge)
            Text("These grades are recovered from your account’s grade notifications. Retakes, changed or deleted grades, and grades without notifications may be missing.")
            Text("Dates come from notifications. Estimated averages use recovered numeric grades only and may differ from the actual gradebook.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun gradeCategory(grade: GradeItem): String {
    val type = grade.typeName.orEmpty().lowercase(Locale.ROOT)
    return when {
        "pisn" in type || "written" in type -> "Written"
        "ustn" in type || "oral" in type -> "Oral"
        else -> "Other"
    }
}

@Composable
private fun GradeValue(grade: GradeItem, replaced: Boolean = false) {
    val category = gradeCategory(grade)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = when (category) {
        "Written" -> if (dark) Color(0xFFFF8A80) else Color(0xFFB3261E)
        "Oral" -> if (dark) Color(0xFF82B1FF) else Color(0xFF1557B0)
        else -> if (dark) Color(0xFF80CBC4) else Color(0xFF176B38)
    }
    Box(Modifier.semantics { contentDescription = "$category grade ${grade.value ?: "unknown"}${if (replaced) ", replaced" else ""}" },
        contentAlignment = Alignment.Center) {
        Text(grade.value ?: "—", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodyLarge,
            textDecoration = if (replaced) TextDecoration.LineThrough else TextDecoration.None,
            color = color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectGradeRow(subject: SubjectGrades, source: GradeSource, semesterId: Int?) {
    val allGrades = subject.semesters.orEmpty().flatMap { it.grades.orEmpty() }
    val replaced = allGrades.flatMap { it.overridesIds.orEmpty() }.toSet()
    val semester = subject.semesters.orEmpty().firstOrNull { it.id == semesterId }
    val visible = if (semesterId == null) allGrades else semester?.grades.orEmpty()
    val ordered = visible.sortedWith(compareBy<GradeItem> { it.date }.thenBy { it.insertedAt })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(subject.name ?: subject.shortName ?: "Subject", Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Clip)
            val values = visible.filter { it.id !in replaced }.distinctBy { it.id }
                .mapNotNull { it.value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { n -> n in 1.0..5.0 } }
            val average = subject.averageGrade?.takeIf { source == GradeSource.Plus && semesterId == null }
                ?: values.takeIf { it.isNotEmpty() }?.average()?.let { String.format(Locale.ROOT, "%.2f", it) }
            val final = if (semesterId == null) subject.finalGrade.gradeValue() else semester?.finalGrade.gradeValue()
            val labels = listOfNotNull(average?.let { "${if (source == GradeSource.Notifications) "Est. avg" else "Avg"} $it" },
                final?.let { "Final $it" })
            if (labels.isNotEmpty()) Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

        }
        if (ordered.isEmpty()) Text(if (subject.isExcused) "Excused" else "No grades yet", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ordered.forEach { grade -> GradeValue(grade, grade.id in replaced) }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
