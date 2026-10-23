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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class ScheduleView(val label: String, val plural: String) {
    Class("Class", "classes"), Teacher("Teacher", "teachers"), Room("Room", "rooms")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PublicTimetableScreen(
    meta: SchoolMeta?,
    schoolLoading: Boolean,
    schoolError: String?,
    onReloadSchool: () -> Unit,
    settings: SettingsStore,
    modifier: Modifier = Modifier
) {
    val repository = NetworkProvider.repository
    var mode by rememberSaveable { mutableStateOf(ScheduleView.Class) }
    var classId by rememberSaveable { mutableStateOf(settings.selectedPublicClassId) }
    var teacher by rememberSaveable { mutableStateOf<String?>(null) }
    var room by rememberSaveable { mutableStateOf<String?>(null) }
    var weekId by rememberSaveable(meta?.schoolKey, meta?.currentWeekId) { mutableIntStateOf(meta?.currentWeekId ?: 0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var failedClasses by remember { mutableStateOf<List<ClassInfo>>(emptyList()) }
    var displayedWeek by remember { mutableStateOf<TimetableWeek?>(null) }
    val schoolWeeks = remember(meta?.schoolKey) { mutableStateMapOf<Int, SchoolTimetables>() }
    val entity = when (mode) {
        ScheduleView.Class -> meta?.classes?.firstOrNull { it.id == classId }?.label
        ScheduleView.Teacher -> teacher
        ScheduleView.Room -> room
    }
    val knownWeeks = schoolWeeks.values.flatMap { it.weeks }
    val options = when (mode) {
        ScheduleView.Class -> meta?.classes.orEmpty().map { it.label }
        ScheduleView.Teacher -> TimetableAggregation.teachers(knownWeeks)
        ScheduleView.Room -> TimetableAggregation.rooms(knownWeeks)
    }
    fun refresh(retryMissing: Boolean = false) {
        if (retryMissing) schoolWeeks.remove(weekId) else {
            repository.clearAllCaches()
            schoolWeeks.clear()
        }
        refreshVersion++
        if (meta == null) onReloadSchool()
    }

    LaunchedEffect(meta?.classes) {
        if (meta != null && meta.classes.none { it.id == classId }) classId = null
    }

    // Compose cancels obsolete requests on mode, selection, week, or refresh changes.
    LaunchedEffect(meta?.schoolKey, mode, classId, teacher, room, weekId, refreshVersion) {
        displayedWeek = null
        failure = null
        failedClasses = emptyList()
        progress = null
        loading = false
        val school = meta ?: return@LaunchedEffect
        if (mode == ScheduleView.Class && classId == null) return@LaunchedEffect
        loading = true
        try {
            displayedWeek = when (mode) {
                ScheduleView.Class -> repository.loadTimetableWeek(school.schoolKey, classId!!, weekId)
                else -> {
                    val result = schoolWeeks[weekId] ?: repository.loadSchoolTimetables(school.schoolKey, school.classes, weekId) { done, total ->
                        progress = done to total
                    }.also { schoolWeeks[weekId] = it }
                    failedClasses = result.failedClasses
                    check(result.weeks.isNotEmpty()) { "Could not load the school timetables. Please try again." }
                    when (mode) {
                        ScheduleView.Teacher -> teacher?.let { TimetableAggregation.forTeacher(result.weeks, it) }
                        ScheduleView.Room -> room?.let { TimetableAggregation.forRoom(result.weeks, it) }
                        else -> null
                    }
                }
            }
            displayedWeek?.let { week ->
                if (selectedDay == null || (selectedDay != "all" && week.days.none { it.date.toString() == selectedDay })) {
                    val today = LocalDate.now()
                    selectedDay = (week.days.firstOrNull { it.date == today } ?: week.days.firstOrNull())?.date?.toString()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: "Could not load the timetable."
        } finally {
            loading = false
        }
    }

    val weeks = meta?.allWeekIds.orEmpty().ifEmpty { (1..52).toList() }.sorted()
    val currentWeek = meta?.currentWeekId ?: 0
    val shownId = if (weekId == 0) currentWeek else weekId
    val previousWeek = weeks.lastOrNull { it < shownId }
    val nextWeek = weeks.firstOrNull { it > shownId }
    val week = displayedWeek
    val shortDate = DateTimeFormatter.ofPattern("d MMM")
    val dayFormat = DateTimeFormatter.ofPattern("EEE")
    val message = failure ?: schoolError
    val shownDays = if (selectedDay == "all") week?.days.orEmpty()
        else week?.days.orEmpty().filter { it.date.toString() == selectedDay }

    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Timetable", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(meta?.schoolName ?: "eAsistent", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { refresh() }, enabled = !loading && !schoolLoading,
                    modifier = Modifier.semantics { contentDescription = "Refresh timetable" }) {
                    Text("↻", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ScheduleView.entries.forEachIndexed { index, view ->
                    SegmentedButton(selected = mode == view, onClick = {
                        if (mode != view) { mode = view; showPicker = true; query = "" }
                    }, shape = SegmentedButtonDefaults.itemShape(index, ScheduleView.entries.size), label = { Text(view.label) })
                }
            }
        }
        item {
            OutlinedCard(onClick = { query = ""; showPicker = true }, modifier = Modifier.fillMaxWidth(),
                enabled = meta != null) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mode.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entity ?: "Choose a ${mode.label.lowercase()}", style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("⌄", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { previousWeek?.let { weekId = it } }, enabled = previousWeek != null,
                    modifier = Modifier.semantics { contentDescription = "Previous week" }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(week?.let { "${it.weekStart.format(shortDate)} – ${it.weekEnd.format(shortDate)}" }
                        ?: if (weekId == 0) "Current week" else "Week $weekId", style = MaterialTheme.typography.titleSmall)
                    if (shownId != currentWeek) TextButton(onClick = { weekId = currentWeek }) { Text("This week") }
                }
                IconButton(onClick = { nextWeek?.let { weekId = it } }, enabled = nextWeek != null,
                    modifier = Modifier.semantics { contentDescription = "Next week" }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
            }
        }
        if (loading || schoolLoading) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress?.let { "Loading school timetables · ${it.first}/${it.second}" } ?: "Loading timetable…",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (message != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { refresh() }) { Text("Try again") }
                }
            }
        }
        if (failedClasses.isNotEmpty() && failure == null) item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Schedule is incomplete: ${failedClasses.size} classes could not be loaded.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { refresh(retryMissing = true) }) { Text("Retry missing classes") }
                }
            }
        }
        if (week != null) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(selected = selectedDay == "all", onClick = { selectedDay = "all" }, label = { Text("All") })
                    }
                    items(week.days, key = { it.date.toString() }) { timetableDay ->
                        FilterChip(selected = timetableDay.date.toString() == selectedDay,
                            onClick = { selectedDay = timetableDay.date.toString() },
                            label = { Text("${timetableDay.date.format(dayFormat)} ${timetableDay.date.dayOfMonth}") })
                    }
                }
            }
            shownDays.forEach { day ->
                item { Text(day.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val cells = day.lessonsByPeriod.filterIsInstance<PeriodCell.WithLessons>()
                if (cells.isEmpty()) item { ScheduleEmptyState("No lessons scheduled", "Try another day or week.") }
                items(cells) { cell -> LessonPeriodCard(cell, showClasses = mode != ScheduleView.Class) }
            }
        } else if (!loading && !schoolLoading && message == null) item {
            ScheduleEmptyState("Choose a ${mode.label.lowercase()}", when (mode) {
                ScheduleView.Class -> "View the lessons for your class."
                ScheduleView.Teacher -> "See which classes a teacher has and where."
                ScheduleView.Room -> "See which classes are using a room."
            })
        }
    }

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text("Choose a ${mode.label.lowercase()}", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, onValueChange = { query = it }, label = { Text("Search ${mode.plural}") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
            if (loading && options.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                Text(progress?.let { "Loading ${it.first}/${it.second} classes…" } ?: "Loading choices…", Modifier.padding(24.dp))
            }
            val filtered = options.filter { it.contains(query.trim(), ignoreCase = true) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(filtered, key = { it }) { option ->
                    TextButton(onClick = {
                        when (mode) {
                            ScheduleView.Class -> {
                                classId = meta?.classes?.firstOrNull { it.label == option }?.id
                                settings.selectedPublicClassId = classId
                            }
                            ScheduleView.Teacher -> teacher = option
                            ScheduleView.Room -> room = option
                        }
                        showPicker = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(option, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (filtered.isEmpty() && !loading) item {
                    Text(if (message != null) "Could not load choices. Close this sheet and try again."
                        else if (query.isNotBlank()) "No matches" else "No ${mode.plural} found for this week.", Modifier.padding(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ScheduleEmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
