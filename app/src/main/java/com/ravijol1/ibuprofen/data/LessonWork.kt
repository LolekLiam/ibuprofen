package com.ravijol1.ibuprofen.data

import com.google.gson.annotations.SerializedName
import org.jsoup.Jsoup

data class LessonAssessment(
    @SerializedName("evaluation_id") val id: Long? = null,
    val title: String? = null,
    val summary: String? = null,
    @SerializedName("grade_type") val gradeType: String? = null
)
data class LessonHomework(
    @SerializedName("due_date") val dueDate: String? = null,
    val title: String? = null,
    val summary: String? = null
)
internal fun schoolText(value: String?): String? = value?.let { Jsoup.parseBodyFragment(it).text().trim() }?.takeIf(String::isNotBlank)
internal fun lessonWorkSummary(assessment: LessonAssessment?, homework: List<LessonHomework>): String =
    listOfNotNull("Assessment".takeIf { assessment != null },
        homework.takeIf { it.isNotEmpty() }?.let { "Homework${if (it.size > 1) " (${it.size})" else ""}" }).joinToString(" · ")
