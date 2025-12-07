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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ravijol1.ibuprofen.data.ClassInfo
import com.ravijol1.ibuprofen.data.NetworkProvider
import com.ravijol1.ibuprofen.data.PeriodCell
import com.ravijol1.ibuprofen.data.SchoolMeta
import com.ravijol1.ibuprofen.data.TimetableWeek
import com.ravijol1.ibuprofen.ui.theme.IbuprofenTheme
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
    var teacherWeek by remember { mutableStateOf<TimetableWeek?>(null) }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadSchool() {
        loading = true
        error = null
        scope.launch {
            try {
                val meta = NetworkProvider.repository.loadSchoolMeta(schoolKey)
                schoolMeta = meta
                selectedClass = meta.classes.firstOrNull()
                // After loading classes, also load timetable for current week (weekId may be 0 initially)
                selectedClass?.let { cls ->
                    val tw = NetworkProvider.repository.loadTimetableWeek(meta.schoolId, cls.id, weekId)
                    timetable = tw
                }
                // Preload all class timetables for current week (0) and collect teachers
                val allWeeks = NetworkProvider.repository.loadAllTimetablesForWeek(meta.schoolId, meta.classes, 0)
                allWeeksCache = allWeeks
                teachers = NetworkProvider.repository.collectTeachersFromTimetables(allWeeks)
                // Reset teacher selections when reloading another school
                selectedTeacher = teachers.firstOrNull()
                teacherWeek = selectedTeacher?.let { sel ->
                    NetworkProvider.repository.buildTeacherTimetable(allWeeksCache, sel)
                }
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
        teacherWeek = NetworkProvider.repository.buildTeacherTimetable(allWeeksCache, sel)
    }

    LaunchedEffect(Unit) {
        // Auto-load on first composition
        loadSchool()
    }

    Column(modifier = modifier.padding(16.dp)) {
        Text(text = "eAsistent Timetables", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                value = schoolKey,
                onValueChange = { schoolKey = it },
                label = { Text("School key") }
            )
            Button(onClick = { loadSchool() }) { Text("Load") }
        }
        Spacer(Modifier.height(8.dp))

        // Class picker
        val classes = schoolMeta?.classes.orEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Teacher mode toggle
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = teacherMode, onCheckedChange = { enabled ->
                    teacherMode = enabled
                    if (enabled) {
                        // Teacher mode uses current week (0); recompute from cache
                        weekId = 0
                        recomputeTeacherWeek()
                    } else {
                        // Back to class mode: ensure a class timetable is loaded
                        loadTimetable()
                    }
                })
                Spacer(Modifier.width(4.dp))
                Text("Teacher mode")
            }

            // Teacher dropdown (enabled only in teacher mode)
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

            // Class controls (disabled in teacher mode)
            OutlinedButton(onClick = { classMenuExpanded = true }, enabled = !teacherMode && classes.isNotEmpty()) {
                Text(selectedClass?.label ?: "Select class")
            }
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

            Row {
                OutlinedButton(onClick = {
                    if (!teacherMode && weekId > 0) { weekId -= 1; loadTimetable() }
                }, enabled = !teacherMode) { Text("Week -") }
                Spacer(Modifier.width(8.dp))
                Text("Week: $weekId")
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    if (!teacherMode && weekId < 52) { weekId += 1; loadTimetable() }
                }, enabled = !teacherMode) { Text("Week +") }
            }
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
        if (teacherMode) {
            Text(text = "Teacher mode: ${selectedTeacher ?: "No teacher selected"}")
        }
        if (displayWeek != null) {
            val week = displayWeek
            val df = DateTimeFormatter.ofPattern("d. M. yyyy")
            Text("${week.weekStart.format(df)} - ${week.weekEnd.format(df)}")
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                itemsIndexed(week.days) { idx, day ->
                    Text(text = day.date.toString(), fontWeight = FontWeight.SemiBold)
                    day.lessonsByPeriod.forEach { cell ->
                        when (cell) {
                            is PeriodCell.Empty -> {
                                // skip empty periods for brevity
                            }
                            is PeriodCell.WithLessons -> {
                                val time = "${cell.timeRange.start} - ${cell.timeRange.endInclusive}"
                                val summary = cell.lessons.joinToString(" | ") { les ->
                                    buildString {
                                        if (les.isCancelled) append("[ODP] ")
                                        append(les.subjectCode ?: les.subjectTitle ?: "?")
                                        les.teacher?.let { append(" • ").append(it) }
                                        les.room?.let { append(" • ").append(it) }
                                        les.sourceClassLabel?.let { append(" • ").append(it) }
                                        les.groupLabel?.let { append(" • ").append(it) }
                                    }
                                }
                                Text(text = "${cell.periodNumber}. ($time): $summary")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            if (teacherMode) {
                Text("Select a teacher to view their timetable for the current week")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TimetablePreview() {
    IbuprofenTheme {
        TimetableScreen()
    }
}