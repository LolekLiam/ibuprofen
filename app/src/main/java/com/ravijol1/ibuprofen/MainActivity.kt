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
import androidx.compose.ui.platform.LocalContext
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
import com.ravijol1.ibuprofen.data.TokenStore
import com.ravijol1.ibuprofen.data.AuthRepository
import com.ravijol1.ibuprofen.data.ChildProfile
import com.ravijol1.ibuprofen.ui.theme.IbuprofenTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

// App tabs
enum class AppTab { Login, Public, Child, Grades }

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
    // Auth/session
    val context = LocalContext.current
    val tokenStore = remember(context) { TokenStore(context.applicationContext) }
    val authRepo = remember { AuthRepository(NetworkProvider.authApi, tokenStore) }
    var session by remember { mutableStateOf(authRepo.currentSession()) }

    // Tabs
    var selectedTab by remember { mutableStateOf(AppTab.Public) }

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

    // Track if we already auto-selected the day for the current dataset (tab/mode+week+entity)
    var autoDayAppliedKey by remember { mutableStateOf<String?>(null) }

    // Child mode state
    var children by remember { mutableStateOf<List<ChildProfile>>(emptyList()) }
    var selectedChild by remember { mutableStateOf<ChildProfile?>(null) }
    var childMenuExpanded by remember { mutableStateOf(false) }
    var childTimetable by remember { mutableStateOf<TimetableWeek?>(null) }

    // Login form state
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf<String?>(null) }

    fun computeDefaultDayIndex(week: TimetableWeek?): Int? {
        if (week == null) return null
        val today = java.time.LocalDate.now()
        val dow = today.dayOfWeek
        val isWeekend = dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY
        if (isWeekend) return null // default to All on weekends
        val idx = week.days.indexOfFirst { it.date == today }
        return if (idx >= 0) idx else null
    }
    fun resetDayFilter() { selectedDayIndex = null }

    fun applyAutoDayFor(week: TimetableWeek?, datasetKey: String) {
        val currentKey = "$datasetKey|w=$weekId"
        if (autoDayAppliedKey == currentKey) return
        val idx = computeDefaultDayIndex(week)
        selectedDayIndex = idx
        autoDayAppliedKey = currentKey
    }

    // Centralized auto-logout that also clears child/grades state and caches
    fun performAutoLogout() {
        scope.launch {
            try { authRepo.logout() } catch (_: Throwable) {}
            session = null
            // Clear child-related state and caches
            children = emptyList()
            selectedChild = null
            childTimetable = null
            autoDayAppliedKey = null
            error = null
            NetworkProvider.repository.clearChildCache()
            // Clear auth-related caches (grades/notifications)
            authRepo.clearCaches()
        }
    }

    fun loadSchool() {
        loading = true
        error = null
        scope.launch {
            try {
                val meta = NetworkProvider.repository.loadSchoolMeta(schoolKey)
                schoolMeta = meta
                // Do not auto-select a class; require explicit selection by user
                selectedClass = null
                // Set week to current from page
                val targetWeek = meta.currentWeekId ?: 0
                if (weekId != targetWeek) weekId = targetWeek
                // Reset caches/state related to timetables and teacher aggregation
                teacherAggregateCache.clear()
                allWeeksCache = emptyList()
                allWeeksCacheWeekId = null
                timetable = null
                teacherWeek = null
                selectedTeacher = null
                selectedDayIndex = null
                error = null
            } catch (t: Throwable) {
                error = t.message
            } finally {
                loading = false
            }
        }
    }

    fun loadTimetable() {
        if (teacherMode) return
        val meta = schoolMeta ?: return
        val cls = selectedClass ?: return
        loading = true
        error = null
        scope.launch {
            try {
                val tw = NetworkProvider.repository.loadTimetableWeek(meta.schoolId, cls.id, weekId)
                timetable = tw
                applyAutoDayFor(tw, datasetKey = "public:${cls.id}")
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
        applyAutoDayFor(teacherWeek, datasetKey = "teacher:$sel")
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

    fun loadChildTimetable() {
        val s = session ?: return
        val child = selectedChild ?: return
        val sid = s.schoolId ?: return
        loading = true
        error = null
        scope.launch {
            try {
                val tw = NetworkProvider.repository.loadChildTimetableWeek(
                    accessToken = s.accessToken,
                    schoolId = sid,
                    studentId = child.studentId,
                    weekId = weekId,
                    classId = child.classId ?: 0
                )
                childTimetable = tw
                applyAutoDayFor(tw, datasetKey = "child:${child.studentId}")
            } catch (t: Throwable) {
                val msg = t.message ?: ""
                if (msg.contains("Unauthorized", ignoreCase = true)) {
                    // Try refresh once
                    val refreshed = try { authRepo.refreshIfNeeded() } catch (_: Throwable) { false }
                    if (refreshed) {
                        session = authRepo.currentSession()
                        val s2 = session
                        if (s2 != null) {
                            try {
                                val tw2 = NetworkProvider.repository.loadChildTimetableWeek(
                                    accessToken = s2.accessToken,
                                    schoolId = s2.schoolId ?: sid,
                                    studentId = child.studentId,
                                    weekId = weekId,
                                    classId = child.classId ?: 0
                                )
                                childTimetable = tw2
                                applyAutoDayFor(tw2, datasetKey = "child:${child.studentId}")
                            } catch (t2: Throwable) {
                                // Auto logout on persistent unauthorized
                                if ((t2.message ?: "").contains("Unauthorized", true)) {
                                    performAutoLogout()
                                    error = "Session expired. Please log in again."
                                } else {
                                    error = t2.message
                                }
                            } finally {
                                loading = false
                            }
                            return@launch
                        }
                    }
                    // If refresh failed
                    performAutoLogout()
                    error = "Session expired. Please log in again."
                } else {
                    error = t.message
                }
            } finally {
                loading = false
            }
        }
    }

    // Debounced week change
    fun scheduleWeekChange(newWeek: Int, debounceMs: Long = 200L) {
        val clamped = newWeek.coerceIn(0, 52)
        weekChangeJob?.cancel()
        weekChangeJob = scope.launch {
            delay(debounceMs)
            if (weekId == clamped) return@launch
            weekId = clamped
            when (selectedTab) {
                AppTab.Public -> if (teacherMode) loadTeacherAggregateForCurrentWeek() else loadTimetable()
                AppTab.Child -> loadChildTimetable()
                else -> {}
            }
        }
    }

    fun loadChildren() {
        val s = session ?: return
        loading = true
        error = null
        scope.launch {
            try {
                val list = authRepo.getChildrenProfiles()
                children = list
                if (selectedChild !in list) selectedChild = list.firstOrNull()
                if (selectedChild != null) {
                    loadChildTimetable()
                }
            } catch (t: Throwable) {
                val msg = t.message ?: ""
                if (msg.contains("Unauthorized", ignoreCase = true)) {
                    performAutoLogout()
                    error = "Session expired. Please log in again."
                } else {
                    error = t.message
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // Try to restore session (and refresh quietly)
        scope.launch { authRepo.refreshIfNeeded() }
        session = authRepo.currentSession()
        // Auto-load public page
        loadSchool()
    }

    Column(modifier = modifier.padding(16.dp)) {
        // Tabs
        androidx.compose.material3.TabRow(selectedTabIndex = selectedTab.ordinal) {
            listOf(AppTab.Login, AppTab.Public, AppTab.Child, AppTab.Grades).forEachIndexed { index, tab ->
                val label = when (tab) {
                    AppTab.Login -> if (session != null) "Account" else "Login"
                    AppTab.Public -> "Public"
                    AppTab.Child -> "Child"
                    AppTab.Grades -> "Grades"
                }
                androidx.compose.material3.Tab(
                    selected = selectedTab.ordinal == index,
                    onClick = { selectedTab = tab },
                    text = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when (selectedTab) {
            AppTab.Login -> {
                Text(text = schoolMeta?.schoolName ?: "eAsistent", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                if (session == null) {
                    androidx.compose.material3.OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Username") }
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Button(onClick = {
                            authError = null
                            scope.launch {
                                try {
                                    val s = authRepo.login(username.trim(), password)
                                    session = s
                                    // Reset child-related state and caches on new login to avoid stale data
                                    children = emptyList()
                                    selectedChild = null
                                    childTimetable = null
                                    autoDayAppliedKey = null
                                    error = null
                                    // Clear repository child cache
                                    NetworkProvider.repository.clearChildCache()
                                } catch (t: Throwable) {
                                    authError = t.message
                                }
                            }
                        }) { Text("Login") }
                    }
                    authError?.let { Text("Error: $it") }
                } else {
                    Text("Logged in as: ${session?.userName ?: session?.userId}")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Button(onClick = {
                            performAutoLogout()
                        }) { Text("Logout") }
                        androidx.compose.material3.OutlinedButton(onClick = {
                            scope.launch { authRepo.refreshIfNeeded() ; session = authRepo.currentSession() }
                        }) { Text("Refresh token") }
                    }
                }
            }
            AppTab.Public -> {
                Text(text = schoolMeta?.schoolName ?: "eAsistent Timetables", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                val classes = schoolMeta?.classes.orEmpty()
                val weekLabel = "Teden $weekId"
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { classMenuExpanded = true }, enabled = !teacherMode && classes.isNotEmpty()) {
                        Text(selectedClass?.label ?: "Select class")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = teacherMode, onCheckedChange = { enabled ->
                            teacherMode = enabled
                            if (enabled) {
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
                                            }
                                            // Do not auto-select a teacher; wait for explicit user selection
                                            if (selectedTeacher != null) {
                                                recomputeTeacherWeek()
                                            } else {
                                                teacherWeek = null
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        error = t.message
                                    } finally {
                                        loading = false
                                    }
                                }
                            } else {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val hasSelection = (teacherMode && selectedTeacher != null) || (!teacherMode && selectedClass != null)
                    OutlinedButton(onClick = { if (weekId > 0) scheduleWeekChange(weekId - 1) }, enabled = hasSelection) { Text("Week -") }
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
                    OutlinedButton(onClick = { if (weekId < 52) scheduleWeekChange(weekId + 1) }, enabled = hasSelection) { Text("Week +") }
                }

                Spacer(Modifier.height(8.dp))

                if (loading) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                error?.let { err -> Text(text = "Error: $err") }

                val displayWeek = if (teacherMode) teacherWeek else timetable
                if (displayWeek != null) {
                    val week = displayWeek
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
                                    is PeriodCell.Empty -> {}
                                    is PeriodCell.WithLessons -> {
                                        androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                                                        if (subtitleParts.isNotEmpty()) { Text(subtitleParts.joinToString(" • ")) }
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
                    } else {
                        Text("Select a class to view the timetable for the current week")
                    }
                }
            }
            AppTab.Child -> {
                val weekLabel = "Teden $weekId"
                Text(text = schoolMeta?.schoolName ?: "eAsistent — Child", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (session == null) {
                    Text("Please login to view the child timetable.")
                } else {
                    // Auto-load children when opening the tab
                    LaunchedEffect(selectedTab, session) {
                        if (selectedTab == AppTab.Child && children.isEmpty()) {
                            loadChildren()
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { childMenuExpanded = true }, enabled = children.isNotEmpty()) {
                            Text(selectedChild?.displayName ?: "Select child")
                        }
                        DropdownMenu(expanded = childMenuExpanded, onDismissRequest = { childMenuExpanded = false }) {
                            children.forEach { ch ->
                                DropdownMenuItem(text = { Text(ch.displayName ?: ch.uuid) }, onClick = {
                                    childMenuExpanded = false
                                    if (selectedChild?.uuid != ch.uuid) {
                                        selectedChild = ch
                                        loadChildTimetable()
                                    }
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { if (weekId > 0) scheduleWeekChange(weekId - 1) }) { Text("Week -") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {

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
                            OutlinedButton(onClick = { if (weekId < 52) scheduleWeekChange(weekId + 1) }) { Text("Week +") }
                        }

                    }
                    Spacer(Modifier.height(8.dp))
                    if (loading) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    }
                    error?.let { err -> Text(text = "Error: $err") }
                    val week = childTimetable
                    if (week != null) {
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
                                        is PeriodCell.Empty -> {}
                                        is PeriodCell.WithLessons -> {
                                            androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                                                                les.groupLabel?.let { add(it) }
                                                            }
                                                            Row(modifier = Modifier.fillMaxWidth()) {
                                                                Text(title, fontWeight = FontWeight.Medium)
                                                                Spacer(Modifier.width(8.dp))
                                                                if (les.isCancelled) {
                                                                    Text("ODP", color = androidx.compose.ui.graphics.Color.Red)
                                                                }
                                                            }
                                                            if (subtitleParts.isNotEmpty()) { Text(subtitleParts.joinToString(" • ")) }
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
                    }
                }
            }
            AppTab.Grades -> {
                // Paid + Free (placeholder) grades view
                Text(text = schoolMeta?.schoolName ?: "eAsistent — Grades", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                // Reuse login/session and children from the Child tab
                if (session == null) {
                    Text("Please login to view grades.")
                } else {
                    // Load children on first entry
                    LaunchedEffect(selectedTab, session) {
                        if (selectedTab == AppTab.Grades && children.isEmpty()) {
                            loadChildren()
                        }
                    }

                    // Child selector (if multiple)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { childMenuExpanded = true }, enabled = children.isNotEmpty()) {
                            Text(selectedChild?.displayName ?: "Select child")
                        }
                        DropdownMenu(expanded = childMenuExpanded, onDismissRequest = { childMenuExpanded = false }) {
                            children.forEach { ch ->
                                DropdownMenuItem(text = { Text(ch.displayName ?: ch.uuid) }, onClick = {
                                    childMenuExpanded = false
                                    if (selectedChild?.uuid != ch.uuid) {
                                        selectedChild = ch
                                        // invalidate auto day selection for grades view
                                        autoDayAppliedKey = null
                                    }
                                })
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Determine Plus status
                    val hasPlus = selectedChild?.subscriptionStatus?.equals("plus", ignoreCase = true) == true

                    if (!hasPlus) {
                        // Free grades via notifications
                        // Warning banner: notifications may miss retake results (redo after fail)
                        androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Opozorilo: Brezplačne ocene temeljijo na obvestilih. Ponovni preizkusi (po NPS ali 1) pogosto niso vključeni v obvestila, zato seznam morda ni popoln.",
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        var freeGrades by remember(selectedChild?.uuid, session) { mutableStateOf<List<com.ravijol1.ibuprofen.data.SubjectGrades>>(emptyList()) }
                        var freeLoaded by remember(selectedChild?.uuid, session) { mutableStateOf(false) }

                        LaunchedEffect(selectedChild, session) {
                            if (selectedTab == AppTab.Grades && selectedChild != null) {
                                loading = true
                                error = null
                                try {
                                    val childUuid = selectedChild?.uuid
                                    if (childUuid.isNullOrBlank()) throw IllegalStateException("Missing child UUID for grades")
                                    val list = authRepo.getFreeGrades(childUuid)
                                    freeGrades = list
                                    freeLoaded = true
                                } catch (t: Throwable) {
                                    val msg = t.message ?: ""
                                    if (msg.contains("Unauthorized", ignoreCase = true)) {
                                        performAutoLogout()
                                        error = "Session expired. Please log in again."
                                    } else {
                                        error = t.message
                                    }
                                } finally {
                                    loading = false
                                }
                            }
                        }

                        if (loading && !freeLoaded) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                        }
                        error?.let { err -> Text(text = "Error: $err") }

                        if (freeGrades.isNotEmpty()) {
                            LazyColumn {
                                itemsIndexed(freeGrades) { _, subj ->
                                    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(subj.name, fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            subj.semesters.forEach { sem ->
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    Text("Semester ${sem.id}", fontWeight = FontWeight.Medium)
                                                    if (sem.grades.isEmpty()) {
                                                        Text("No grades", fontSize = 13.sp)
                                                    } else {
                                                        sem.grades.forEach { g ->
                                                            val color = try { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(g.color ?: "#000000")) } catch (_: Throwable) { androidx.compose.ui.graphics.Color.Unspecified }
                                                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Text(g.value ?: "?", color = color, fontWeight = FontWeight.SemiBold)
                                                                    Spacer(Modifier.width(8.dp))
                                                                    Text(g.typeName ?: "", fontSize = 13.sp)
                                                                    Spacer(Modifier.width(8.dp))
                                                                    Text(g.date ?: "", fontSize = 12.sp)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(6.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (freeLoaded && freeGrades.isEmpty()) {
                            Text("No grades available.")
                        }
                    } else {
                        // Paid grades: load and show
                        var grades by remember(selectedChild?.uuid, session) { mutableStateOf<List<com.ravijol1.ibuprofen.data.SubjectGrades>>(emptyList()) }
                        var gradesLoaded by remember(selectedChild?.uuid, session) { mutableStateOf(false) }

                        LaunchedEffect(selectedChild, session) {
                            if (selectedTab == AppTab.Grades && selectedChild != null) {
                                loading = true
                                error = null
                                try {
                                    val childUuid = selectedChild?.uuid
                                    if (childUuid.isNullOrBlank()) throw IllegalStateException("Missing child UUID for grades")
                                    val list = authRepo.getGrades(childUuid)
                                    grades = list
                                    gradesLoaded = true
                                } catch (t: Throwable) {
                                    val msg = t.message ?: ""
                                    if (msg.contains("Unauthorized", ignoreCase = true)) {
                                        performAutoLogout()
                                        error = "Session expired. Please log in again."
                                    } else {
                                        error = t.message
                                    }
                                } finally {
                                    loading = false
                                }
                            }
                        }

                        if (loading && !gradesLoaded) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                        }
                        error?.let { err -> Text(text = "Error: $err") }

                        if (grades.isNotEmpty()) {
                            // Simple grades table
                            LazyColumn {
                                itemsIndexed(grades) { _, subj ->
                                    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(subj.name, fontWeight = FontWeight.SemiBold)
                                                Spacer(Modifier.width(8.dp))
                                                subj.averageGrade?.let { Text("Avg: $it", fontSize = 14.sp) }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            subj.semesters.forEach { sem ->
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    Text("Semester ${sem.id}", fontWeight = FontWeight.Medium)
                                                    if (sem.finalGrade != null) {
                                                        Text("Final: ${sem.finalGrade}", fontSize = 13.sp)
                                                    }
                                                    if (sem.grades.isEmpty()) {
                                                        Text("No grades", fontSize = 13.sp)
                                                    } else {
                                                        sem.grades.forEach { g ->
                                                            val color = try { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(g.color ?: "#000000")) } catch (_: Throwable) { androidx.compose.ui.graphics.Color.Unspecified }
                                                            val overridden = (g.overridesIds?.isNotEmpty() == true)
                                                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Text(g.value ?: "?", color = color, fontWeight = FontWeight.SemiBold)
                                                                    Spacer(Modifier.width(8.dp))
                                                                    Text(g.typeName ?: "", fontSize = 13.sp)
                                                                    Spacer(Modifier.width(8.dp))
                                                                    Text(g.date ?: "", fontSize = 12.sp)
                                                                }
                                                                val comment = g.comment
                                                                if (!comment.isNullOrBlank()) {
                                                                    Text(comment, fontSize = 12.sp)
                                                                }
                                                                if (overridden) {
                                                                    Text("Overrides: ${g.overridesIds?.joinToString()}", fontSize = 11.sp)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(6.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (gradesLoaded && grades.isEmpty()) {
                            Text("No grades available.")
                        }
                    }
                }
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
