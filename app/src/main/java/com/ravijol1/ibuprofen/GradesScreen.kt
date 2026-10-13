package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.CancellationException

@Composable
internal fun GradesScreen(auth: AuthRepository, profileUi: ProfileUi, onSessionExpired: () -> Unit) {
    var data by remember { mutableStateOf<MobileGrades?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(profileUi.selected?.uuid, revision) {
        data = null; error = null
        val student = profileUi.selected ?: return@LaunchedEffect
        loading = true
        try { data = auth.getMobileGrades(student.uuid)
        } catch (e: CancellationException) { throw e
        } catch (e: SessionExpiredException) { onSessionExpired()
        } catch (e: Exception) { error = e.message
        } finally { loading = false }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("Grades", onRefresh = { if (profileUi.selected == null) profileUi.onRetry() else revision++ }, loading = loading || profileUi.loading) }
        item { StudentSelector(profileUi) }
        if (profileUi.selected != null) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { ErrorNotice(message) { revision++ } } }
            data?.let { grades ->
                item {
                    Text("Recent grades", style = MaterialTheme.typography.titleMedium)
                    Text("The latest grades shared in your account overview.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (grades.recent.isEmpty()) item { EmptyNotice("No recent grades", "Your overview has no recent grades to display.") }
                items(grades.recent, key = { it.id }) { grade ->
                    Card {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                                Text(grade.value ?: "—", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(grade.subjectName ?: grade.subjectShortName ?: "Subject", style = MaterialTheme.typography.titleMedium)
                                Text(listOfNotNull(grade.typeName, grade.date).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                grade.comment?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
                grades.summary?.let { summary ->
                    item { Text("Grade overview", style = MaterialTheme.typography.titleMedium) }
                    item {
                        OutlinedCard {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                listOf("4–5 range" to summary.fourToFive, "3–4 range" to summary.threeToFour,
                                    "1–3 range" to summary.oneToThree, "Without grades" to summary.withoutGrades).forEach { (label, band) ->
                                    Row(Modifier.fillMaxWidth()) {
                                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                        Text("${band?.subjects?.size ?: 0} subjects", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(if (grades.summaryUnavailable) "The grade summary is unavailable for this account. Recent grades are shown above."
                        else "Only recent grades and a limited summary are available for this account.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
