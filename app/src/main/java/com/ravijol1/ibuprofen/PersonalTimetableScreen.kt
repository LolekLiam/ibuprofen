package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun PersonalTimetableScreen(auth: AuthRepository, profileUi: ProfileUi, jumpDate: String?, onSessionExpired: () -> Unit) {
    val child = profileUi.selected
    var weeks by remember(child?.uuid) { mutableStateOf<List<MobileSchoolWeek>>(emptyList()) }
    var start by rememberSaveable(child?.uuid) { mutableStateOf<String?>(null) }
    var selectedDay by rememberSaveable(child?.uuid) { mutableStateOf(jumpDate) }
    var revision by remember { mutableIntStateOf(0) }
    var loadingWeeks by remember { mutableStateOf(false) }
    var loadingEvents by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var schedule by remember { mutableStateOf<PersonalSchedule?>(null) }
    val selectedWeek = weeks.firstOrNull { it.from == start }
    val loading = loadingWeeks || loadingEvents || profileUi.loading
    LaunchedEffect(child?.uuid, revision) {
        weeks = emptyList(); schedule = null; error = null
        val student = child ?: return@LaunchedEffect
        loadingWeeks = true
        try {
            weeks = auth.getSchoolWeeks(student.uuid)
            val jump = runCatching { jumpDate?.let(LocalDate::parse) }.getOrNull()
            if (start == null || weeks.none { it.from == start }) {
                start = (weeks.firstOrNull { jump != null && jump in LocalDate.parse(it.from)..LocalDate.parse(it.to) }
                    ?: weeks.firstOrNull { it.current } ?: weeks.firstOrNull())?.from
            }
            if (weeks.isEmpty()) error = "No school weeks were returned for this student."
        } catch (e: CancellationException) { throw e
        } catch (e: SessionExpiredException) { onSessionExpired()
        } catch (e: Exception) { error = e.message
        } finally { loadingWeeks = false }
    }
    LaunchedEffect(child?.uuid, selectedWeek, revision) {
        schedule = null
        val student = child ?: return@LaunchedEffect
        val week = selectedWeek ?: return@LaunchedEffect
        loadingEvents = true; error = null
        try {
            schedule = auth.getPersonalSchedule(student, LocalDate.parse(week.from), LocalDate.parse(week.to))
            val days = schedule!!.week.days
            if (selectedDay == null || (selectedDay != "all" && days.none { it.date.toString() == selectedDay })) {
                selectedDay = (days.firstOrNull { it.date == LocalDate.now() } ?: days.firstOrNull())?.date?.toString()
            }
        } catch (e: CancellationException) { throw e
        } catch (e: SessionExpiredException) { onSessionExpired()
        } catch (e: Exception) { error = e.message
        } finally { loadingEvents = false }
    }
    val index = weeks.indexOf(selectedWeek)
    val current = weeks.firstOrNull { it.current }
    val shortDate = DateTimeFormatter.ofPattern("d MMM")
    val shownDays = schedule?.week?.days.orEmpty().filter { selectedDay == "all" || it.date.toString() == selectedDay }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("My schedule", onRefresh = {
            if (child == null) profileUi.onRetry() else revision++
        }, loading = loading) }
        item { StudentSelector(profileUi) }
        if (child != null) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { start = weeks[index - 1].from }, enabled = index > 0 && !loadingWeeks,
                        modifier = Modifier.semantics { contentDescription = "Previous week" }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(selectedWeek?.let { "${LocalDate.parse(it.from).format(shortDate)} – ${LocalDate.parse(it.to).format(shortDate)}" }
                            ?: "School week", style = MaterialTheme.typography.titleSmall)
                        if (current != null && current.from != start) TextButton(onClick = { start = current.from }) { Text("This week") }
                    }
                    IconButton(onClick = { start = weeks[index + 1].from }, enabled = index >= 0 && index < weeks.lastIndex && !loadingWeeks,
                        modifier = Modifier.semantics { contentDescription = "Next week" }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { ErrorNotice(message) { revision++ } } }
            schedule?.let { personal ->
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selectedDay == "all", { selectedDay = "all" }, label = { Text("All") }) }
                        items(personal.week.days, key = { it.date.toString() }) { day ->
                            FilterChip(selectedDay == day.date.toString(), { selectedDay = day.date.toString() },
                                label = { Text("${day.date.format(DateTimeFormatter.ofPattern("EEE"))} ${day.date.dayOfMonth}") })
                        }
                    }
                }
                shownDays.forEach { day ->
                    item { Text(day.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val timeline = personalScheduleTimeline(day, personal.events)
                    items(timeline, key = { entry ->
                        when (entry) {
                            is PersonalScheduleEntry.Event -> "${day.date}:event:${entry.event.slug}"
                            is PersonalScheduleEntry.Lesson -> "${day.date}:lesson:${entry.cell.periodNumber}:${entry.startTime}:${entry.cell.timeRange.endInclusive}"
                        }
                    }) { entry ->
                        when (entry) {
                            is PersonalScheduleEntry.Lesson -> LessonPeriodCard(entry.cell)
                            is PersonalScheduleEntry.Event -> PersonalEventCard(entry.event)
                        }
                    }
                    if (timeline.isEmpty()) item { EmptyNotice("No lessons scheduled", "Try another day or week.") }
                }
            }
        }
    }
}


@Composable
private fun PersonalEventCard(event: MobileEvent) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(event.title ?: event.shortTitle ?: "School event", style = MaterialTheme.typography.titleMedium)
            Text(if (event.isAllDay) "All day" else "${event.from} – ${event.to}", style = MaterialTheme.typography.bodySmall)
            event.classroom?.takeIf { it.isNotBlank() }?.let { Text("Room $it", style = MaterialTheme.typography.bodyMedium) }
            event.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
