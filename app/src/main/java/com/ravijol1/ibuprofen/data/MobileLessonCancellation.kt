package com.ravijol1.ibuprofen.data

import java.time.LocalTime
import java.util.Locale

internal data class MobileCancellations(val lessons: Set<String>, val consumedMarkers: Set<String>)

/** Cancellation events refer to existing lessons. Match subject and time, never the whole period blindly. */
internal fun resolveMobileCancellations(events: List<MobileEvent>): MobileCancellations {
    fun normalized(value: String?) = schoolText(value)?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")
    fun subjects(event: MobileEvent) = listOf(event.subject?.shortTitle, event.subject?.title, event.shortTitle, event.title)
        .mapNotNull(::normalized).toSet()
    fun overlaps(a: MobileEvent, b: MobileEvent) = runCatching {
        LocalTime.parse(a.from) < LocalTime.parse(b.to) && LocalTime.parse(b.from) < LocalTime.parse(a.to)
    }.getOrDefault(false)
    val lessons = events.filter { it.type == "school_hour" || mobileLessonChangeKind(it) != null }
    val cancelled = mutableSetOf<String>()
    val consumed = mutableSetOf<String>()
    events.filter { it.type != "school_hour" && it.isCancelled && mobileLessonChangeKind(it) == null }.forEach { marker ->
        val names = subjects(marker)
        val matches = lessons.filter { lesson ->
            marker.date == lesson.date && overlaps(marker, lesson) &&
                (if (marker.subject?.id != null && lesson.subject?.id != null) marker.subject.id == lesson.subject.id
                else names.intersect(subjects(lesson)).isNotEmpty()) &&
                (marker.teachers.orEmpty().isEmpty() || lesson.teachers.orEmpty().mapNotNull(::normalized)
                    .intersect(marker.teachers.orEmpty().mapNotNull(::normalized).toSet()).isNotEmpty()) &&
                (normalized(marker.classroom) == null || normalized(marker.classroom) == normalized(lesson.classroom))
        }
        // Keep ambiguous markers visible rather than cancelling another parallel group.
        val unambiguous = matches.groupBy { it.from to it.to }.values.filter { it.size == 1 }.flatten()
        cancelled += unambiguous.map { it.slug }
        if (unambiguous.isNotEmpty() && unambiguous.size == matches.size) consumed += marker.slug
    }
    return MobileCancellations(cancelled, consumed)
}
