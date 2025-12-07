package com.ravijol1.ibuprofen.data

class TimetableRepository(private val api: EAsistentApi) {
    suspend fun loadSchoolMeta(schoolKey: String): SchoolMeta {
        val res = api.getSchoolPage(schoolKey)
        val body = res.body() ?: error("Empty school page")
        return SchoolPageParser.parse(body, schoolKey)
    }

    suspend fun loadTimetableWeek(schoolId: Int, classId: Int, weekId: Int): TimetableWeek {
        require(weekId in 0..52) { "weekId out of range" }
        val res = api.getTimetable(schoolId, classId, weekId = weekId)
        val body = res.body() ?: error("Empty timetable body")
        return TimetableParser.parse(classId, body)
    }

    // Load timetables for all classes for a given week (sequential to stay polite to server)
    suspend fun loadAllTimetablesForWeek(
        schoolId: Int,
        classes: List<ClassInfo>,
        weekId: Int
    ): List<TimetableWeek> {
        val result = mutableListOf<TimetableWeek>()
        for (cls in classes) {
            try {
                val week = loadTimetableWeek(schoolId, cls.id, weekId)
                result += annotateWeekWithClassLabel(week, cls.label)
            } catch (_: Throwable) {
                // ignore individual failures
            }
        }
        return result
    }

    private fun annotateWeekWithClassLabel(week: TimetableWeek, classLabel: String): TimetableWeek {
        val newDays = week.days.map { day ->
            val newCells = day.lessonsByPeriod.map { cell ->
                when (cell) {
                    is PeriodCell.Empty -> cell
                    is PeriodCell.WithLessons -> cell.copy(
                        lessons = cell.lessons.map { les ->
                            if (les.sourceClassLabel == null) les.copy(sourceClassLabel = classLabel) else les
                        }
                    )
                }
            }
            day.copy(lessonsByPeriod = newCells)
        }
        return week.copy(days = newDays)
    }

    // Collect distinct teacher full names from a list of timetables
    fun collectTeachersFromTimetables(timetables: List<TimetableWeek>): List<String> {
        return timetables.asSequence()
            .flatMap { it.days.asSequence() }
            .flatMap { it.lessonsByPeriod.asSequence() }
            .filterIsInstance<PeriodCell.WithLessons>()
            .flatMap { it.lessons.asSequence() }
            .mapNotNull { it.teacherFullName?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toList()
    }

    // Build a synthetic teacher timetable for the current week (aggregated across classes)
    fun buildTeacherTimetable(
        allClassWeeks: List<TimetableWeek>,
        teacherFullName: String
    ): TimetableWeek? {
        if (allClassWeeks.isEmpty()) return null
        val base = allClassWeeks.first()
        val days = mutableListOf<TimetableDay>()

        // Helper to check if timeRange is a real parsed value
        fun hasRealTime(range: ClosedRange<java.time.LocalTime>): Boolean =
            !(range.start == java.time.LocalTime.MIDNIGHT && range.endInclusive == java.time.LocalTime.MIDNIGHT)

        // Build per-day slots keyed by time when available; fallback to periodNumber
        for (dayIdx in 0 until base.days.size.coerceAtMost(5)) {
            val date = base.days[dayIdx].date

            data class Slot(
                val keyStart: java.time.LocalTime?,
                val keyEnd: java.time.LocalTime?,
                val fallbackPeriod: Int?,
                val lessons: MutableList<Lesson> = mutableListOf(),
                val periodNumbers: MutableList<Int> = mutableListOf(),
                val ranges: MutableList<ClosedRange<java.time.LocalTime>> = mutableListOf()
            )

            // Use linked map to preserve insertion before sorting
            val slots = mutableMapOf<String, Slot>()

            allClassWeeks.forEach { w ->
                val day = w.days.getOrNull(dayIdx) ?: return@forEach
                day.lessonsByPeriod.forEach { cell ->
                    if (cell is PeriodCell.WithLessons) {
                        val matching = cell.lessons.filter { it.teacherFullName == teacherFullName }
                        if (matching.isEmpty()) return@forEach
                        val hasTime = hasRealTime(cell.timeRange)
                        val key: String
                        val ks: java.time.LocalTime?
                        val ke: java.time.LocalTime?
                        val fp: Int?
                        if (hasTime) {
                            ks = cell.timeRange.start
                            ke = cell.timeRange.endInclusive
                            fp = null
                            key = "T:${ks}-${ke}"
                        } else {
                            ks = null
                            ke = null
                            fp = cell.periodNumber
                            key = "P:${cell.periodNumber}"
                        }
                        val slot = slots.getOrPut(key) { Slot(ks, ke, fp) }
                        slot.lessons.addAll(matching)
                        slot.periodNumbers += cell.periodNumber
                        slot.ranges += cell.timeRange
                    }
                }
            }

            // Sort slots: by time when present, otherwise by fallback period number
            val ordered = slots.values.sortedWith(compareBy(
                { it.keyStart == null }, // time slots (false) come before period-only (true)
                { it.keyStart ?: java.time.LocalTime.MIDNIGHT },
                { it.keyEnd ?: java.time.LocalTime.MIDNIGHT },
                { it.fallbackPeriod ?: Int.MAX_VALUE }
            ))

            val periodCells = mutableListOf<PeriodCell>()
            ordered.forEachIndexed { idx, slot ->
                if (slot.lessons.isEmpty()) return@forEachIndexed
                // Choose period number: majority among collected numbers; fallback to min or sequential idx+1
                val periodNum = slot.periodNumbers.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: slot.periodNumbers.minOrNull() ?: (idx + 1)
                // Choose time range: prefer the real key time; else pick the most common or first
                val timeRange: ClosedRange<java.time.LocalTime> = if (slot.keyStart != null && slot.keyEnd != null) {
                    slot.keyStart..slot.keyEnd
                } else {
                    slot.ranges.firstOrNull() ?: (java.time.LocalTime.MIDNIGHT..java.time.LocalTime.MIDNIGHT)
                }
                periodCells += PeriodCell.WithLessons(
                    periodNumber = periodNum,
                    timeRange = timeRange,
                    lessons = slot.lessons.toList()
                )
            }

            days += TimetableDay(date = date, lessonsByPeriod = periodCells)
        }

        return TimetableWeek(classId = -1, weekStart = base.weekStart, weekEnd = base.weekEnd, days = days)
    }
}