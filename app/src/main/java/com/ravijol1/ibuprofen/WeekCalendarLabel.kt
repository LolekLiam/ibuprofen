package com.ravijol1.ibuprofen

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** Selecting any day navigates to its containing school week. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> WeekCalendarLabel(weeks: Map<LocalDate, T>, selected: LocalDate?, onSelect: (T) -> Unit, label: String) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = weeks.isNotEmpty()) { Text(label) }
    if (open) {
        fun weekStart(date: LocalDate) = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val choices = weeks.mapKeys { weekStart(it.key) }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = selected?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    weekStart(Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()) in choices
                override fun isSelectableYear(year: Int): Boolean = choices.keys.any { it.year == year || it.plusDays(6).year == year }
            })
        val choice = state.selectedDateMillis?.let {
            choices[weekStart(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())]
        }
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton(enabled = choice != null, onClick = { choice?.let(onSelect); open = false }) { Text("Choose week") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                DatePicker(state = state, title = null)
            }
        }
    }
}
