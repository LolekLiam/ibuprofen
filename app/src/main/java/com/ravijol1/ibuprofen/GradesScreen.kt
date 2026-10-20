package com.ravijol1.ibuprofen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
    var detail by remember { mutableStateOf<Pair<SubjectGrades, GradeItem>?>(null) }
    var showSourceInfo by remember { mutableStateOf(false) }
    var semesterId by remember { mutableStateOf<Int?>(null) }
    val student = profileUi.selected
    val hasPlus = student?.let { it.plusEnabled || it.subscriptionStatus.equals("plus", true) } == true
    LaunchedEffect(student?.uuid, hasPlus, revision) {
        data = null; error = null; detail = null; pages = 0; loading = false; showSourceInfo = false; semesterId = null
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
        item { StudentSelector(profileUi) }
        if (student != null) {
            if (loading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (pages > 0) Text("Loading grade history…", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { message -> item { ErrorNotice(message) { revision++ } } }
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
                    key(student.uuid) { SubjectGradeRow(subject, grades.source, semesterId) { detail = subject to it } }
                }
            }
        }
    }
    detail?.let { (subject, grade) ->
        val replaced = subject.semesters.orEmpty().flatMap { it.grades.orEmpty() }.any { grade.id in it.overridesIds.orEmpty() }
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(subject.name ?: subject.shortName ?: "Subject", style = MaterialTheme.typography.titleLarge)
                GradeValue(grade.value ?: "—", replaced)
                grade.typeName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                grade.date?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                grade.comment?.takeIf { it.isNotBlank() }?.let { Text(it) }
                if (data?.source == GradeSource.Notifications) Text("Recovered from a notification", style = MaterialTheme.typography.bodySmall)
                if (replaced) Text("This grade was replaced by a newer grade", style = MaterialTheme.typography.bodySmall)
                if (!grade.overridesIds.isNullOrEmpty()) Text("Replaces an earlier grade", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (showSourceInfo) ModalBottomSheet(onDismissRequest = { showSourceInfo = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Grades from notifications", style = MaterialTheme.typography.titleLarge)
            Text("These grades are recovered from your account’s grade notifications. Retakes, changed or deleted grades, and grades without notifications may be missing.")
            Text("Dates come from notifications. Final grades and averages are not inferred.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GradeValue(value: String, replaced: Boolean = false, onClick: (() -> Unit)? = null) {
    val interaction = if (onClick == null) Modifier else Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = onClick)
    Surface(color = if (replaced) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp), modifier = interaction) {
        Text(value, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium,
            textDecoration = if (replaced) TextDecoration.LineThrough else TextDecoration.None,
            color = if (replaced) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectGradeRow(subject: SubjectGrades, source: GradeSource, semesterId: Int?, onGrade: (GradeItem) -> Unit) {
    val allGrades = subject.semesters.orEmpty().flatMap { it.grades.orEmpty() }
    val replaced = allGrades.flatMap { it.overridesIds.orEmpty() }.toSet()
    val semester = subject.semesters.orEmpty().firstOrNull { it.id == semesterId }
    val visible = if (semesterId == null) allGrades else semester?.grades.orEmpty()
    val ordered = visible.sortedWith(compareBy<GradeItem> { it.date }.thenBy { it.insertedAt })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(subject.name ?: subject.shortName ?: "Subject", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (source == GradeSource.Plus) {
                val final = if (semesterId == null) subject.finalGrade.gradeValue() else semester?.finalGrade.gradeValue()
                val labels = listOfNotNull(subject.averageGrade?.takeIf { semesterId == null }?.let { "Avg $it" }, final?.let { "Final $it" })
                if (labels.isNotEmpty()) Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ordered.isEmpty()) Text(if (subject.isExcused) "Excused" else "No grades yet", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ordered.forEach { grade -> GradeValue(grade.value ?: "—", grade.id in replaced) { onGrade(grade) } }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}
