package com.ravijol1.ibuprofen.data

import java.text.Normalizer
import java.util.Locale

/** Details supplied by the timetable, without guessing an original teacher or room. */
enum class LessonChangeKind(val label: String) { Substitution("Substitution"), Assignment("Zaposlitev") }
data class LessonChange(val kind: LessonChangeKind, val originalSubject: String? = null, val description: String? = null)

val Lesson.changeKind: LessonChangeKind?
    get() = change?.kind ?: LessonChangeKind.Substitution.takeIf { isSubstitution }

internal fun mobileLessonChangeKind(event: MobileEvent): LessonChangeKind? {
    val words = (listOfNotNull(event.type, event.typeLabel) + event.typeLabels.orEmpty().mapNotNull { it.label })
        .flatMap { Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).split(Regex("[\\s_-]+")) }
    return when {
        "zaposlitev" in words || "assignment" in words -> LessonChangeKind.Assignment
        event.isSubstitution || "substitution" in words || "nadomescanje" in words -> LessonChangeKind.Substitution
        else -> null
    }
}
