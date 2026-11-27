package com.ravijol1.ibuprofen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.ZonedDateTime
import com.ravijol1.ibuprofen.data.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jsoup.Jsoup

private val weeklyClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun timeLabel(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
private fun WeeklyActivity.title(): String = when (val value = entry) {
    is PersonalScheduleEntry.Lesson -> value.cell.lessons.joinToString(" / ") { it.subjectTitle ?: it.subjectCode ?: "Lesson" }
    is PersonalScheduleEntry.Event -> value.event.title ?: value.event.shortTitle ?: "School event"
}
private fun WeeklyActivity.shortTitle(): String = when (val value = entry) {
    is PersonalScheduleEntry.Lesson -> value.cell.lessons.joinToString("/") {
        it.subjectCode?.takeIf(String::isNotBlank) ?: it.subjectTitle?.split(Regex("\\s+"))?.mapNotNull { word -> word.firstOrNull() }
            ?.joinToString("")?.take(5)?.uppercase(Locale.ROOT) ?: "Lesson"
    }
    is PersonalScheduleEntry.Event -> value.event.shortTitle ?: value.event.title ?: "Event"
}
private fun WeeklyActivity.room(): String? = when (val value = entry) {
    is PersonalScheduleEntry.Lesson -> value.cell.lessons.mapNotNull { it.room }.distinct().joinToString(", ").takeIf(String::isNotBlank)
    is PersonalScheduleEntry.Event -> value.event.classroom
}
private fun WeeklyActivity.cancelled(): Boolean = when (val value = entry) {
    is PersonalScheduleEntry.Lesson -> value.cell.lessons.isNotEmpty() && value.cell.lessons.all { it.isCancelled }
    is PersonalScheduleEntry.Event -> value.event.isCancelled
}

@Composable
internal fun WeeklyScheduleGrid(schedule: PersonalSchedule, modifier: Modifier = Modifier, showClasses: Boolean = false,
    currentTime: ZonedDateTime? = null, onDay: (LocalDate) -> Unit) {
    val layout = remember(schedule) { weeklyScheduleLayout(schedule) }
    var selection by remember(schedule) { mutableStateOf<List<WeeklyActivity>?>(null) }
    val now = currentTime ?: rememberSchoolClock()
    val today = now.withZoneSameInstant(SCHOOL_ZONE).toLocalDate()
    val indicator = weeklyNowIndicator(layout, now)
    val indicatorColor = MaterialTheme.colorScheme.error
    val hourHeight = (if (showClasses) 100.dp else 80.dp) * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val minuteHeight = hourHeight / 60
    val gutter = 40.dp
    // This surface owns horizontal gestures, even though it only scrolls vertically.
    Column(modifier.fillMaxWidth().pointerInput(Unit) {
        detectHorizontalDragGestures { change, _ -> change.consume() }
    }) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(gutter))
            layout.columns.forEach { day ->
                val isToday = day.date == today
                Surface(color = if (isToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f)) {
                    Text(day.date.format(DateTimeFormatter.ofPattern("EEE")),
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Open ${day.date}") { onDay(day.date) }
                            .padding(vertical = 12.dp).semantics { contentDescription = day.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) },
                        style = MaterialTheme.typography.labelMedium, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (layout.columns.any { it.untimed.isNotEmpty() }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("All day / other", Modifier.width(gutter).padding(end = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    layout.columns.forEach { day ->
                        Column(Modifier.weight(1f).padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            day.untimed.forEach { activity ->
                                WeeklyTile(listOf(activity), Modifier.fillMaxWidth().heightIn(min = 48.dp), showClasses) { selection = listOf(activity) }
                            }
                        }
                    }
                }
            }
            if (!layout.hasTimedActivities) {
                EmptyNotice("No lessons scheduled", "No timed activities for these weekdays.")
            } else {
                val lastBottom = layout.columns.flatMap { it.blocks }.maxOf { block ->
                    minuteHeight * (block.startMinute - layout.startMinute) +
                        (minuteHeight * (block.endMinute - block.startMinute)).coerceAtLeast(52.dp)
                }
                val height = maxOf(minuteHeight * (layout.endMinute - layout.startMinute) + 24.dp, lastBottom + 8.dp)
                Row(Modifier.fillMaxWidth().height(height)) {
                    Box(Modifier.width(gutter).fillMaxHeight()) {
                        for (minute in layout.startMinute..layout.endMinute step 60) {
                            Text(timeLabel(minute), Modifier.offset(y = minuteHeight * (minute - layout.startMinute)).padding(end = 2.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        indicator?.let { marker ->
                            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier
                                .offset(y = (minuteHeight * (marker.minute - layout.startMinute) - 8.dp).coerceAtLeast(0.dp))) {
                                Text(timeLabel(marker.minute), color = indicatorColor, style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                    layout.columns.forEach { day ->
                        Box(Modifier.weight(1f).fillMaxHeight().background(
                            if (day.date == today) MaterialTheme.colorScheme.primary.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface)) {
                            VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                            for (minute in layout.startMinute..layout.endMinute step 60) {
                                HorizontalDivider(Modifier.offset(y = minuteHeight * (minute - layout.startMinute)), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            day.blocks.forEach { block ->
                                WeeklyTile(block.activities,
                                    Modifier.offset(y = minuteHeight * (block.startMinute - layout.startMinute))
                                        .fillMaxWidth().height((minuteHeight * (block.endMinute - block.startMinute)).coerceAtLeast(52.dp))
                                        .padding(horizontal = 2.dp, vertical = 2.dp), showClasses) { selection = block.activities }
                            }
                            if (indicator?.date == day.date) {
                                Canvas(Modifier.fillMaxWidth().height(8.dp)
                                    .offset(y = minuteHeight * (indicator.minute - layout.startMinute) - 4.dp)
                                    .semantics { contentDescription = "Current time ${timeLabel(indicator.minute)}" }) {
                                    drawLine(indicatorColor, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 2.dp.toPx())
                                    drawCircle(indicatorColor, 3.dp.toPx(), Offset(3.dp.toPx(), size.height / 2f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selection?.let { activities -> WeeklyActivityDetails(activities, showClasses) { selection = null } }
}

@Composable
private fun WeeklyTile(activities: List<WeeklyActivity>, modifier: Modifier, showClasses: Boolean, onClick: () -> Unit) {
    val single = activities.singleOrNull()
    val changed = activities.flatMap { (it.entry as? PersonalScheduleEntry.Lesson)?.cell?.lessons.orEmpty() }.mapNotNull { it.changeKind }
    val work = activities.flatMap { activity -> when (val entry = activity.entry) {
        is PersonalScheduleEntry.Lesson -> entry.cell.lessons.map { lessonWorkSummary(it.assessment, it.homework.orEmpty()) }
        is PersonalScheduleEntry.Event -> listOf(lessonWorkSummary(entry.event.evaluation, entry.event.homework.orEmpty()))
    } }.filter(String::isNotBlank).distinct().joinToString(" · ")
    val marker = when { LessonChangeKind.Assignment in changed -> "Z · "; changed.isNotEmpty() -> "S · "; else -> "" }
    val event = single?.entry is PersonalScheduleEntry.Event
    val color = if (event) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(onClick = onClick, modifier = modifier.semantics {
        contentDescription = activities.joinToString("; ") {
            "${it.date}: ${it.title()}, ${if (it.allDay) "all day" else it.startMinute?.let(::timeLabel) ?: "time unavailable"}${if (it.cancelled()) ", cancelled" else ""}"
        } + work.takeIf(String::isNotBlank)?.let { "; $it" }.orEmpty() + changed.distinct().joinToString(prefix = if (changed.isEmpty()) "" else "; ") { it.label }
    }, shape = MaterialTheme.shapes.extraSmall, color = color,
        border = if (changed.isNotEmpty()) BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary) else null) {
        Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(marker + (single?.shortTitle() ?: "${activities.size} activities"), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, maxLines = if (single == null) 2 else 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (activities.all { it.cancelled() }) TextDecoration.LineThrough else TextDecoration.None)
            if (work.isNotBlank()) Text(work, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
            single?.room()?.takeIf { it.isNotBlank() }?.let { room ->
                Text(room, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showClasses) single?.let { activity ->
                (activity.entry as? PersonalScheduleEntry.Lesson)?.cell?.lessons.orEmpty().mapNotNull { it.sourceClassLabel }
                    .distinct().joinToString(" / ").takeIf(String::isNotBlank)?.let { classes ->
                        Text(classes, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeeklyActivityDetails(activities: List<WeeklyActivity>, showClasses: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text(if (activities.size > 1) "Activities" else "Activity details", style = MaterialTheme.typography.titleLarge) }
            activities.forEach { activity ->
                item(key = activity.key) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(activity.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), style = MaterialTheme.typography.labelLarge)
                        when (val entry = activity.entry) {
                            is PersonalScheduleEntry.Lesson -> {
                                Text("Period ${entry.cell.periodNumber} · ${entry.cell.timeRange.start.format(weeklyClock)} – ${entry.cell.timeRange.endInclusive.format(weeklyClock)}",
                                    style = MaterialTheme.typography.bodyMedium)
                                entry.cell.lessons.forEachIndexed { index, lesson ->
                                    if (index > 0) HorizontalDivider()
                                    Text(lesson.subjectTitle ?: lesson.subjectCode ?: "Lesson", style = MaterialTheme.typography.titleMedium)
                                    lesson.changeKind?.let { kind ->
                                        Text(kind.label, color = MaterialTheme.colorScheme.tertiary)
                                        Text(lesson.change?.originalSubject?.let { "Originally scheduled: $it" } ?: "Original lesson details are not provided.",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    (lesson.teacherFullName ?: lesson.teacher)?.takeIf(String::isNotBlank)?.let { Text("Teacher: $it") }
                                    lesson.room?.takeIf(String::isNotBlank)?.let { Text("Room: $it") }
                                    if (showClasses) lesson.sourceClassLabel?.takeIf(String::isNotBlank)?.let { Text("Class: $it") }
                                    lesson.groupLabel?.takeIf(String::isNotBlank)?.let { Text(it) }
                                    (lesson.notes + listOfNotNull(lesson.change?.description)).filter(String::isNotBlank).distinct().forEach { Text(it) }
                                    if (lesson.isCancelled) Text("Cancelled", color = MaterialTheme.colorScheme.error)
                                    LessonWorkDetails(lesson.assessment, lesson.homework.orEmpty())
                                }
                            }
                            is PersonalScheduleEntry.Event -> {
                                val event = entry.event
                                Text(activity.title(), style = MaterialTheme.typography.titleMedium)
                                Text(if (activity.allDay) "All day" else if (activity.startMinute == null) "Time unavailable" else "${event.from} – ${event.to}")
                                event.classroom?.takeIf(String::isNotBlank)?.let { Text("Room: $it") }
                                event.teachers.orEmpty().takeIf { it.isNotEmpty() }?.let { Text("Teacher: ${it.joinToString(", ")}") }
                                event.description?.takeIf(String::isNotBlank)?.let { Text(remember(it) { Jsoup.parseBodyFragment(it).text() }) }
                                event.notes.orEmpty().mapNotNull { note ->
                                    if (note.isJsonPrimitive && note.asJsonPrimitive.isString) note.asString else null
                                }.distinct().forEach { Text(it) }
                                if (event.isCancelled) Text("Cancelled", color = MaterialTheme.colorScheme.error)
                                LessonWorkDetails(event.evaluation, event.homework.orEmpty())
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSchoolClock(): ZonedDateTime {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(ZonedDateTime.now(SCHOOL_ZONE), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = ZonedDateTime.now(SCHOOL_ZONE)
                value = now
                delay(60_000L - now.second * 1_000L - now.nano / 1_000_000L)
            }
        }
    }.value
}
