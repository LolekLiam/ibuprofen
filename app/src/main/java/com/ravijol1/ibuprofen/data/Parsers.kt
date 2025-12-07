package com.ravijol1.ibuprofen.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object SchoolPageParser {
    private val idRegex = Regex("var\\s+id_sola\\s*=\\s*'(?<id>\\d+)'")

    fun parse(html: String, schoolKey: String): SchoolMeta {
        val doc = Jsoup.parse(html)
        val scriptText = doc.select("script").joinToString("\n") { it.data() }
        val schoolId = idRegex.find(scriptText)?.groups?.get("id")?.value?.toInt()
            ?: error("id_sola not found in school page")

        val classes = doc.select("#id_parameter > option").mapNotNull { opt ->
            val id = opt.attr("value").toIntOrNull() ?: return@mapNotNull null
            ClassInfo(id = id, label = opt.text().trim())
        }

        return SchoolMeta(schoolKey = schoolKey, schoolId = schoolId, classes = classes)
    }
}

object TimetableParser {
    private const val US = '\u001F'
    private val dateFull = DateTimeFormatter.ofPattern("d. M. yyyy").withLocale(Locale("sl", "SI"))
    private val timeRangeRegex = Regex("(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})")
    private val timeHM = DateTimeFormatter.ofPattern("H:mm")

    private fun parseTimeOrNull(text: String): LocalTime? = try {
        LocalTime.parse(text.trim(), timeHM)
    } catch (t: Throwable) { null }

    fun parse(classId: Int, payload: String): TimetableWeek {
        val parts = payload.split(US)
        require(parts.size >= 4) { "Unexpected payload format: parts=${parts.size}" }
        val weekStart = LocalDate.parse(parts[1].trim(), dateFull)
        val weekEnd = LocalDate.parse(parts[2].trim(), dateFull)
        val html = parts[3]

        val doc = Jsoup.parseBodyFragment(html)
        val table = doc.selectFirst("table.ednevnik-seznam_ur_teden") ?: return TimetableWeek(classId, weekStart, weekEnd, emptyList())

        // Days headers
        val headers = table.select("thead th").drop(1)
        val dayDates: List<LocalDate> = headers.map { th ->
            val shortDate = th.selectFirst(".date")?.text()?.trim().orEmpty()
            val clean = shortDate.removeSuffix(".").trim()
            val dm = clean.split(". ").filter { it.isNotBlank() }
            val d = dm.getOrNull(0)?.toIntOrNull()
            val m = dm.getOrNull(1)?.toIntOrNull()
            val year = inferYearForDay(weekStart, weekEnd, d, m)
            LocalDate.of(year, m ?: weekStart.monthValue, d ?: weekStart.dayOfMonth)
        }

        val rows = table.select("> tbody > tr, > tr").toList()
        val daysByPeriod = mutableListOf<List<PeriodCell>>()

        for ((periodIdx, row) in rows.withIndex()) {
            val tds = row.select("> td")
            if (tds.isEmpty()) continue

            val left = tds.first()
            val periodNumber = left.selectFirst(".naziv-ure")?.text()?.substringBefore('.')?.trim()?.toIntOrNull()
                ?: (periodIdx + 1)
            val timeRangeText = left.selectFirst(".potek-ure")?.text()?.trim().orEmpty()
            val m = timeRangeRegex.find(timeRangeText)
            val timeRange = if (m != null) {
                val s = parseTimeOrNull(m.groupValues[1])
                val e = parseTimeOrNull(m.groupValues[2])
                if (s != null && e != null) s..e else (LocalTime.MIDNIGHT..LocalTime.MIDNIGHT)
            } else {
                (LocalTime.MIDNIGHT..LocalTime.MIDNIGHT)
            }

            val perDay = mutableListOf<PeriodCell>()
            for (dIdx in 1..5) {
                val cell = tds.getOrNull(dIdx)
                if (cell == null) { perDay += PeriodCell.Empty; continue }
                val isCancelledCell = cell.hasClass("ednevnik-seznam_ur_teden-td-odpadla-ura")

                val blocks = mutableListOf<Element>()
                blocks += cell.select("div.ednevnik-seznam_ur_teden-blok-wrap").toList()
                blocks += cell.select("div.hidden.teden-blok-wrapper div.ednevnik-seznam_ur_teden-blok-wrap").toList()

                val lessons = blocks.mapNotNull { block -> parseLesson(block, isCancelledCell) }

                perDay += if (lessons.isEmpty()) PeriodCell.Empty else PeriodCell.WithLessons(
                    periodNumber = periodNumber,
                    timeRange = timeRange,
                    lessons = lessons
                )
            }
            daysByPeriod += perDay
        }

        val dayCount = dayDates.size.coerceAtMost(5)
        val periodsPerDay = MutableList(dayCount) { mutableListOf<PeriodCell>() }
        for (per in daysByPeriod) {
            for (d in 0 until dayCount) periodsPerDay[d] += per.getOrNull(d) ?: PeriodCell.Empty
        }

        val days = (0 until dayCount).map { d ->
            TimetableDay(date = dayDates[d], lessonsByPeriod = periodsPerDay[d])
        }

        return TimetableWeek(classId, weekStart, weekEnd, days)
    }

    private fun parseLesson(block: Element, isCancelledCell: Boolean): Lesson? {
        val titleSpan = block.selectFirst(".ednevnik-title span")
        val subjectCode = titleSpan?.text()?.trim()?.ifBlank { null }
        val subjectTitle = titleSpan?.attr("title")?.trim()?.ifBlank { null }

        val subtitles = block.select(".ednevnik-subtitle").toList()
        val teacherSubtitle = subtitles.getOrNull(0)
        val teacherRoom = teacherSubtitle?.text()?.trim()
        val teacherFullName = teacherSubtitle?.attr("title")?.trim()?.ifBlank { null }
        val group = subtitles.drop(1).joinToString(" | ") { it.text().trim() }.ifBlank { null }

        val teacher = teacherRoom?.substringBefore(',')?.trim()?.ifBlank { null }
        val room = teacherRoom?.substringAfter(',')?.trim()?.ifBlank { null }

        val cancelled = isCancelledCell || block.selectFirst(".wl-tag-cancelled") != null

        if (subjectCode == null && subjectTitle == null && teacher == null && room == null && group == null) return null
        return Lesson(subjectCode, subjectTitle, teacher, room, group, cancelled, teacherFullName, null)
    }

    private fun inferYearForDay(weekStart: LocalDate, weekEnd: LocalDate, d: Int?, m: Int?): Int {
        if (d == null || m == null) return weekStart.year
        return listOf(weekStart, weekEnd).minBy { ld ->
            kotlin.math.abs(ld.monthValue - m) * 31 + kotlin.math.abs(ld.dayOfMonth - d)
        }.year
    }
}