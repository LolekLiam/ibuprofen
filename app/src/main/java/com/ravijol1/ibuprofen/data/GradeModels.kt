package com.ravijol1.ibuprofen.data

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import org.jsoup.Jsoup
import java.time.LocalDate

/** Optional fields are nullable on the wire; final grades may be objects or scalar values. */
data class GradesResponse(val items: List<SubjectGrades>? = null)
data class SubjectGrades(
    val id: Long,
    val name: String?,
    @SerializedName("short_name") val shortName: String?,
    @SerializedName("grade_type") val gradeType: String? = null,
    @SerializedName("is_excused") val isExcused: Boolean = false,
    @SerializedName("final_grade") val finalGrade: JsonElement? = null,
    @SerializedName("average_grade") val averageGrade: String? = null,
    val semesters: List<SemesterGrades>? = null
)
data class SemesterGrades(
    val id: Int,
    @SerializedName("final_grade") val finalGrade: JsonElement? = null,
    val grades: List<GradeItem>? = null
)
data class GradeItem(
    val id: Long,
    val value: String?,
    val date: String?,
    @SerializedName("type_name") val typeName: String? = null,
    val comment: String? = null,
    @SerializedName("inserted_at") val insertedAt: String? = null,
    @SerializedName("overrides_ids") val overridesIds: List<Long>? = null
)
fun JsonElement?.gradeValue(): String? = when {
    this == null || isJsonNull -> null
    isJsonPrimitive -> asString
    isJsonObject -> asJsonObject.get("value").gradeValue()
    else -> null
}

data class NotificationsResponse(val items: List<GradeNotification>? = null)
data class GradeNotification(
    val id: Long,
    val type: String?,
    val title: String?,
    val message: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("meta_data") val metaData: GradeNotificationMeta? = null
)
data class GradeNotificationMeta(
    @SerializedName(value = "gradeId", alternate = ["grade_id"]) val gradeId: Long? = null,
    @SerializedName(value = "subjectId", alternate = ["subject_id"]) val subjectId: Long? = null,
    val date: String? = null
)
enum class GradeSource { Plus, Notifications }
internal data class NotificationGradeHistory(val subjects: List<SubjectGrades>, val complete: Boolean)

internal fun gradeSchoolYearStart(schoolYear: String?, today: LocalDate): LocalDate {
    val year = schoolYear?.substringBefore('/')?.toIntOrNull()
        ?: if (today.monthValue < 9) today.year - 1 else today.year
    return LocalDate.of(year, 9, 1)
}
internal fun notificationDate(value: String?): LocalDate? = value?.take(10)?.let {
    runCatching { LocalDate.parse(it) }.getOrNull()
}

internal object NotificationGradeParser {
    private val pattern = Regex("^\\s*([^\\s]+)\\s*[-–—]\\s*([^,]+)\\s*,\\s*(.+)$")
    fun parse(notifications: List<GradeNotification>, start: LocalDate): List<SubjectGrades> {
        data class Parsed(val subject: String, val subjectId: Long?, val grade: GradeItem)
        val end = start.plusYears(1)
        val parsed = notifications.distinctBy { it.id }.mapNotNull { item ->
            val title = Jsoup.parseBodyFragment(item.title.orEmpty()).text()
            if (!item.type.equals("ocena", true) && !title.startsWith("Nova ocena", true)) return@mapNotNull null
            val message = Jsoup.parseBodyFragment(item.message.orEmpty()).text()
            val match = pattern.matchEntire(message) ?: return@mapNotNull null
            val date = notificationDate(item.createdAt) ?: notificationDate(item.metaData?.date) ?: return@mapNotNull null
            if (date < start || date >= end) return@mapNotNull null
            val subject = match.groupValues[2].trim()
            Parsed(subject, item.metaData?.subjectId,
                GradeItem(item.metaData?.gradeId ?: item.id, match.groupValues[1], date.toString(),
                    match.groupValues[3].trim(), insertedAt = item.createdAt))
        }.distinctBy { it.subjectId to it.grade.id }
        // Labels are the only stable subject identifier available on some older notifications.
        return parsed.groupBy { it.subject }.map { (subject, entries) ->
            SubjectGrades(entries.firstNotNullOfOrNull { it.subjectId } ?: subject.hashCode().toLong(), subject, subject,
                semesters = listOf(SemesterGrades(0, grades = entries.map { it.grade }.distinctBy { it.id }.sortedBy { it.date })))
        }.sortedBy { it.name }
    }
}
