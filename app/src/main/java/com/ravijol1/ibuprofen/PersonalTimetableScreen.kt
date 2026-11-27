package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter

@Composable
internal fun PersonalTimetableScreen(auth: AuthRepository, profileUi: ProfileUi, jumpDate: String?, settings: SettingsStore, onSessionExpired: () -> Unit) {
    val child = profileUi.selected
    val context = LocalContext.current.applicationContext
    var groups by remember(child?.uuid, child?.schoolYear) { mutableStateOf(child?.let { settings.personalGroups(it.uuid, it.schoolYear) } ?: PersonalGroups()) }
    var showGroups by remember(child?.uuid) { mutableStateOf(false) }
    var jumpConsumed by rememberSaveable(jumpDate) { mutableStateOf(false) }
    var weekView by rememberSaveable(child?.uuid) {
        mutableStateOf((jumpDate == null || jumpConsumed) && child?.uuid?.let(settings::scheduleWeekView) == true)
    }
    fun chooseView(weekly: Boolean) {
        weekView = weekly
        child?.uuid?.let { settings.setScheduleWeekView(it, weekly) }
    }
    var weeks by remember(child?.uuid) { mutableStateOf<List<MobileSchoolWeek>>(emptyList()) }
    var start by rememberSaveable(child?.uuid) { mutableStateOf<String?>(null) }
    var selectedDay by rememberSaveable(child?.uuid) { mutableStateOf(jumpDate) }
    var revision by remember { mutableIntStateOf(0) }
    var loadingWeeks by remember { mutableStateOf(false) }
    var loadingEvents by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var schedule by remember(child?.uuid) { mutableStateOf<PersonalSchedule?>(null) }
    val selectedWeek = weeks.firstOrNull { it.from == start }
    val loading = loadingWeeks || loadingEvents || profileUi.loading
    LaunchedEffect(child?.uuid, revision, jumpDate) {
        weeks = emptyList(); schedule = null; error = null
        val student = child ?: return@LaunchedEffect
        loadingWeeks = true
        try {
            weeks = auth.getSchoolWeeks(student.uuid)
            val jump = runCatching { jumpDate?.takeUnless { jumpConsumed }?.let(LocalDate::parse) }.getOrNull()
            if (jump != null) { weekView = false; selectedDay = jump.toString() }
            if (jump != null || start == null || weeks.none { it.from == start }) {
                start = (weeks.firstOrNull { jump != null && jump in LocalDate.parse(it.from)..LocalDate.parse(it.to) }
                    ?: weeks.firstOrNull { it.current } ?: weeks.firstOrNull())?.from
            }
            if (jump != null) jumpConsumed = true
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
            if (selectedDay == null || days.none { it.date.toString() == selectedDay }) {
                selectedDay = (days.firstOrNull { it.date == LocalDate.now(SCHOOL_ZONE) && it.date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) } ?: days.firstOrNull { it.date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) } ?: days.firstOrNull())?.date?.toString()
            }
        } catch (e: CancellationException) { throw e
        } catch (e: SessionExpiredException) { onSessionExpired()
        } catch (e: Exception) { error = e.message
        } finally { loadingEvents = false }
    }
    val index = weeks.indexOf(selectedWeek)
    val current = weeks.firstOrNull { it.current }
    val shortDate = DateTimeFormatter.ofPattern("d MMM")
    LaunchedEffect(schedule, child?.uuid) {
        val loaded = schedule ?: return@LaunchedEffect
        val student = child ?: return@LaunchedEffect
        val discovered = discoverPersonalGroups(loaded, groups)
        if (discovered != groups) { groups = discovered; settings.setPersonalGroups(student.uuid, student.schoolYear, discovered) }
    }
    val personal = remember(schedule, groups) { schedule?.let { filterPersonalGroups(it, groups) } }
    val weekGroups = remember(schedule) { schedule?.let(::discoverTimetableEntries).orEmpty() }
    if (showGroups) PersonalGroupsSheet(groups, schedule?.week?.weekStart, weekGroups, onSave = { updated ->
        groups = updated
        child?.let { settings.setPersonalGroups(it.uuid, it.schoolYear, updated) }
        if (settings.remindersEnabled && settings.selectedChildUuid == child?.uuid) {
            ReminderAlarms.restore(context)
            ReminderWorker.refresh(context)
        }
    }, onDismiss = { showGroups = false })
    val weekdays = personal?.week?.days.orEmpty().filter { it.date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Schedule", onRefresh = { if (child == null) profileUi.onRetry() else revision++ }, loading = loading,
            actions = { if (child != null) TextButton(onClick = { showGroups = true }) { Text("Groups", style = MaterialTheme.typography.labelMedium) } })
        if (child == null) StudentSelector(profileUi)
        else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { start = weeks[index - 1].from }, enabled = index > 0 && !loadingWeeks,
                    modifier = Modifier.semantics { contentDescription = "Previous week" }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    WeekCalendarLabel(weeks.associate { LocalDate.parse(it.from) to it.from }, start?.let(LocalDate::parse),
                        onSelect = { start = it }, label = selectedWeek?.let { "${LocalDate.parse(it.from).format(shortDate)} – ${LocalDate.parse(it.to).format(shortDate)}" }
                            ?: "School week")
                    if (current != null && current.from != start) TextButton(onClick = { start = current.from }) { Text("This week") }
                }
                IconButton(onClick = { start = weeks[index + 1].from }, enabled = index >= 0 && index < weeks.lastIndex && !loadingWeeks,
                    modifier = Modifier.semantics { contentDescription = "Next week" }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { message -> ErrorNotice(message) { revision++ } }
            if (personal != null) {
                LazyRow(Modifier.fillMaxWidth().nestedScroll(DayStripScrollConnection), horizontalArrangement = DayStripArrangement) {
                    item { FilterChip(weekView, { chooseView(true) }, label = { Text("Week") }) }
                    items(weekdays, key = { it.date.toString() }) { day ->
                        FilterChip(!weekView && selectedDay == day.date.toString(), { selectedDay = day.date.toString(); chooseView(false) },
                            label = { Text(day.date.format(DateTimeFormatter.ofPattern("EEE"))) })
                    }
                }
                if (weekView) key(child.uuid, personal.week.weekStart) {
                    WeeklyScheduleGrid(personal, Modifier.weight(1f)) { date -> selectedDay = date.toString(); chooseView(false) }
                } else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)) {
                    personal.week.days.filter { it.date.toString() == selectedDay }.forEach { day ->
                        item { Text(day.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val timeline = personalScheduleTimeline(day, personal.events)
                        items(timeline, key = { entry -> when (entry) {
                            is PersonalScheduleEntry.Event -> "${day.date}:event:${entry.event.slug}"
                            is PersonalScheduleEntry.Lesson -> "${day.date}:lesson:${entry.cell.periodNumber}:${entry.startTime}:${entry.cell.timeRange.endInclusive}"
                        } }) { entry -> when (entry) {
                            is PersonalScheduleEntry.Lesson -> LessonPeriodCard(entry.cell)
                            is PersonalScheduleEntry.Event -> PersonalEventCard(entry.event)
                        } }
                        if (timeline.isEmpty()) item { EmptyNotice("No lessons shown", if (groups.entryChoices.orEmpty().isNotEmpty() || groups.choices.values.any { it.hidden || it.variant != null }) "Check your group choices or choose another day." else "Try another day or week.") }
                    }
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
            if (event.isCancelled) Text("Cancelled", color = MaterialTheme.colorScheme.error)
            LessonWorkDetails(event.evaluation, event.homework.orEmpty())
        }
    }
}
