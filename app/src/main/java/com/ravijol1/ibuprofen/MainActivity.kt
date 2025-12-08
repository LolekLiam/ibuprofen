package com.ravijol1.ibuprofen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravijol1.ibuprofen.data.ClassInfo
import com.ravijol1.ibuprofen.data.NetworkProvider
import com.ravijol1.ibuprofen.data.PeriodCell
import com.ravijol1.ibuprofen.data.SchoolMeta
import com.ravijol1.ibuprofen.data.TimetableWeek
import com.ravijol1.ibuprofen.ui.theme.IbuprofenTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IbuprofenTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TimetableScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun TimetableScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var schoolKey by remember { mutableStateOf("5738623c4f3588f82583378c44ceb026102d6bae") }

    var schoolMeta by remember { mutableStateOf<SchoolMeta?>(null) }
    var classMenuExpanded by remember { mutableStateOf(false) }
    var selectedClass by remember { mutableStateOf<ClassInfo?>(null) }

    var weekId by remember { mutableIntStateOf(0) }
    var timetable by remember { mutableStateOf<TimetableWeek?>(null) }

    // Teacher mode state
    var teacherMode by remember { mutableStateOf(false) }
    var teachers by remember { mutableStateOf<List<String>>(emptyList()) }
    var teacherMenuExpanded by remember { mutableStateOf(false) }
    var selectedTeacher by remember { mutableStateOf<String?>(null) }
    var allWeeksCache by remember { mutableStateOf<List<TimetableWeek>>(emptyList()) }
    var allWeeksCacheWeekId by remember { mutableStateOf<Int?>(null) }
    var teacherWeek by remember { mutableStateOf<TimetableWeek?>(null) }
    // Cache aggregated teacher weeks per (weekId, teacher)
    val teacherAggregateCache = remember { mutableStateMapOf<Pair<Int, String>, TimetableWeek>() }

    // Day filter: null = All days, otherwise index in current week's days list
    var selectedDayIndex by remember { mutableStateOf<Int?>(null) }

    // Week change debounce
    var weekChangeJob by remember { mutableStateOf<Job?>(null) }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun resetDayFilter() { selectedDayIndex = null }

    fun loadSchool() {
        loading = true
        error = null
        scope.launch {
            try {
                val meta = NetworkProvider.repository.loadSchoolMeta(schoolKey)
                schoolMeta = meta
                selectedClass = meta.classes.firstOrNull()
                // Determine current week from school page (fallback to 0)
                val targetWeek = meta.currentWeekId ?: 0
                if (weekId != targetWeek) weekId = targetWeek
                // Clear caches on school change
                teacherAggregateCache.clear()
                allWeeksCache = emptyList()
                allWeeksCacheWeekId = null
                // Load class timetable for determined week
                selectedClass?.let { cls ->
                    val tw = NetworkProvider.repository.loadTimetableWeek(meta.schoolId, cls.id, weekId)
                    timetable = tw
                }
                // Preload all class timetables for the determined week and collect teachers
                val allWeeks = NetworkProvider.repository.loadAllTimetablesForWeek(meta.schoolId, meta.classes, weekId)
                allWeeksCache = allWeeks
                allWeeksCacheWeekId = weekId
                teachers = NetworkProvider.repository.collectTeachersFromTimetables(allWeeks)
                // Reset teacher selections when reloading another school
                if (selectedTeacher !in teachers) selectedTeacher = teachers.firstOrNull()
                teacherWeek = selectedTeacher?.let { sel ->
                    // cache aggregated
                    val built = NetworkProvider.repository.buildTeacherTimetable(allWeeksCache, sel)
                    if (built != null) teacherAggregateCache[weekId to sel] = built
                    built
                }
                resetDayFilter()
            } catch (t: Throwable) {
                error = t.message
            } finally {
                loading = false
            }
        }
    }

    fun loadTimetable() {
        if (teacherMode) return // in teacher mode we don't load single-class timetable
        val meta = schoolMeta ?: return
        val cls = selectedClass ?: return
        loading = true
        error = null
        scope.launch {
            try {
                val tw = NetworkProvider.repository.loadTimetableWeek(meta.schoolId, cls.id, weekId)
                timetable = tw
                resetDayFilter()
            } catch (t: Throwable) {
                error = t.message
            } finally {
                loading = false
            }
        }
    }

    fun recomputeTeacherWeek() {
        val sel = selectedTeacher ?: return
        if (allWeeksCache.isEmpty()) return
        val key = weekId to sel
        val cached = teacherAggregateCache[key]
        if (cached != null) {
            teacherWeek = cached
        } else {
            val built = NetworkProvider.repository.buildTeacherTimetable(allWeeksCache, sel)
            if (built != null) {
                teacherAggregateCache[key] = built
            }
            teacherWeek = built
        }
        resetDayFilter()
    }

    fun loadTeacherAggregateForCurrentWeek() {
        val meta = schoolMeta ?: return
        loading = true
        error = null
        scope.launch {
            try {
                if (allWeeksCacheWeekId != weekId) {
                    val allWeeks = NetworkProvider.repository.loadAllTimetablesForWeek(meta.schoolId, meta.classes, weekId)
                    allWeeksCache = allWeeks
                    allWeeksCacheWeekId = weekId
                    teachers = NetworkProvider.repository.collectTeachersFromTimetables(allWeeks)
                    if (selectedTeacher !in teachers) selectedTeacher = teachers.firstOrNull()
                    // Invalidate aggregated cache for this week (rebuilt on demand)
                    val toRemove = teacherAggregateCache.keys.filter { it.first == weekId }
                    toRemove.forEach { teacherAggregateCache.remove(it) }
                }
                recomputeTeacherWeek()
            } catch (t: Throwable) {
                error = t.message
            } finally {
                loading = false
            }
        }
    }

    // Debounced week change to avoid spamming network/aggregation when jumping
    fun scheduleWeekChange(newWeek: Int, debounceMs: Long = 200L) {
        val clamped = newWeek.coerceIn(0, 52)
        weekChangeJob?.cancel()
        weekChangeJob = scope.launch {
            delay(debounceMs)
            if (weekId == clamped) return@launch
            weekId = clamped
            if (teacherMode) {
                loadTeacherAggregateForCurrentWeek()
            } else {
                loadTimetable()
            }
        }
    }

    LaunchedEffect(Unit) {
        // Auto-load on first composition
        loadSchool()
    }

    Column(modifier = modifier.padding(16.dp)) {
        Text(text = schoolMeta?.schoolName ?: "eAsistent Timetables", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        // Row 2: Class dropdown + Week controls (disabled in teacher mode)
        val classes = schoolMeta?.classes.orEmpty()
        val weekLabel = "Week $weekId"
        // Row A: Class selector only (disabled in Teacher mode)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { classMenuExpanded = true }, enabled = !teacherMode && classes.isNotEmpty()) {
                Text(selectedClass?.label ?: "Select class")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = teacherMode, onCheckedChange = { enabled ->
                    teacherMode = enabled
                    if (enabled) {
                        // Entering Teacher mode: ensure we have aggregated data for the current weekId
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                val meta = schoolMeta
                                if (meta != null) {
                                    if (allWeeksCacheWeekId != weekId) {
                                        val allWeeks = NetworkProvider.repository.loadAllTimetablesForWeek(meta.schoolId, meta.classes, weekId)
                                        allWeeksCache = allWeeks
                                        allWeeksCacheWeekId = weekId
                                        teachers = NetworkProvider.repository.collectTeachersFromTimetables(allWeeks)
                                        if (selectedTeacher !in teachers) selectedTeacher = teachers.firstOrNull()
                                    }
                                    recomputeTeacherWeek()
                                }
                            } catch (t: Throwable) {
                                error = t.message
                            } finally {
                                loading = false
                            }
                        }
                    } else {
                        // Back to class mode: ensure a class timetable is loaded
                        loadTimetable()
                    }
                })
                Text("Teacher mode")
            }
            OutlinedButton(onClick = { teacherMenuExpanded = true }, enabled = teacherMode && teachers.isNotEmpty()) {
                Text(selectedTeacher ?: "Select teacher")
            }
            DropdownMenu(expanded = teacherMenuExpanded, onDismissRequest = { teacherMenuExpanded = false }) {
                teachers.forEach { t ->
                    DropdownMenuItem(text = { Text(t) }, onClick = {
                        teacherMenuExpanded = false
                        if (selectedTeacher != t) {
                            selectedTeacher = t
                            recomputeTeacherWeek()
                        }
                    })
                }
            }
        }
        // Dropdown menu as sibling so it can overflow properly
        DropdownMenu(expanded = classMenuExpanded, onDismissRequest = { classMenuExpanded = false }) {
            classes.forEach { cls ->
                DropdownMenuItem(text = { Text(cls.label) }, onClick = {
                    classMenuExpanded = false
                    if (selectedClass?.id != cls.id) {
                        selectedClass = cls
                        loadTimetable()
                    }
                })
            }
        }
        Spacer(Modifier.height(8.dp))
        // Row B: Week controls — ensure buttons remain visible with weighted, ellipsized label
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                if (weekId > 0) {
                    scheduleWeekChange(weekId - 1)
                }
            }) { Text("Week -") }
            val displayWeek = if (teacherMode) teacherWeek else timetable
            val week = displayWeek
            val df = DateTimeFormatter.ofPattern("d. M. yyyy")
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    weekLabel,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                if (week != null) {
                    Text(
                        "${week.weekStart.format(df)} - ${week.weekEnd.format(df)}",
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }

            OutlinedButton(onClick = {
                if (weekId < 52) {
                    scheduleWeekChange(weekId + 1)
                }
            }) { Text("Week +") }
        }

        Spacer(Modifier.height(8.dp))

        if (loading) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        error?.let { err ->
            Text(text = "Error: $err")
        }

        val displayWeek = if (teacherMode) teacherWeek else timetable
        if (displayWeek != null) {
            val week = displayWeek
            // Day selector: All + per-day buttons (horizontally scrollable)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    if (selectedDayIndex == null) {
                        Button(onClick = { selectedDayIndex = null }) { Text("All") }
                    } else {
                        OutlinedButton(onClick = { selectedDayIndex = null }) { Text("All") }
                    }
                }
                items(week.days.size) { idx ->
                    val day = week.days[idx]
                    val label = day.date.dayOfWeek.name.first().toString() + day.date.dayOfWeek.name.drop(1).lowercase()
                    val isSelected = selectedDayIndex == idx
                    if (isSelected) {
                        Button(onClick = { selectedDayIndex = idx }) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { selectedDayIndex = idx }) { Text(label) }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            val daysToShow = if (selectedDayIndex == null) week.days else listOfNotNull(week.days.getOrNull(selectedDayIndex!!))

            LazyColumn {
                itemsIndexed(daysToShow) { _, day ->
                    Text(text = day.date.toString(), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    day.lessonsByPeriod.forEach { cell ->
                        when (cell) {
                            is PeriodCell.Empty -> { /* skip */ }
                            is PeriodCell.WithLessons -> {
                                androidx.compose.material3.Card(modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        val time = "${cell.timeRange.start} - ${cell.timeRange.endInclusive}"
                                        Text(text = "${cell.periodNumber}. ura  •  $time", fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(4.dp))
                                        cell.lessons.forEachIndexed { idx, les ->
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                val title = les.subjectCode ?: les.subjectTitle ?: "?"
                                                val subtitleParts = buildList {
                                                    les.teacher?.let { add(it) }
                                                    les.room?.let { add(it) }
                                                    les.sourceClassLabel?.let { add(it) }
                                                    les.groupLabel?.let { add(it) }
                                                }
                                                Row(modifier = Modifier.fillMaxWidth()) {
                                                    Text(title, fontWeight = FontWeight.Medium)
                                                    Spacer(Modifier.width(8.dp))
                                                    if (les.isCancelled) {
                                                        Text("ODP", color = androidx.compose.ui.graphics.Color.Red)
                                                    }
                                                }
                                                if (subtitleParts.isNotEmpty()) {
                                                    Text(subtitleParts.joinToString(" • "))
                                                }
                                                if (idx < cell.lessons.lastIndex) Spacer(Modifier.height(6.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        } else {
            if (teacherMode) {
                Text("Select a teacher to view their timetable for the current week")
            }
        }
    }
}

@Preview(
    name = "407x904dp",
    widthDp = 407,
    heightDp = 904,
    showSystemUi = true,
    showBackground = true
)
@Composable
fun TimetablePreview() {
    IbuprofenTheme {
        TimetableScreen()
    }
}
