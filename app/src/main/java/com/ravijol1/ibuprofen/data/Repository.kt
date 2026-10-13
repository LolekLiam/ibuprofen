package com.ravijol1.ibuprofen.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

class TimetableRepository(private val api: EAsistentApi) {
    private val schoolKeys = ConcurrentHashMap<Int, String>()
    private var proxyApi: IbuprofenProxyApi? = null

    private suspend fun resolveSchoolKey(schoolId: Int): String {
        schoolKeys[schoolId]?.let { return it }
        // Background reminders may start in a fresh process before the screen loads.
        val meta = loadSchoolMeta(DEFAULT_SCHOOL_KEY)
        require(meta.schoolId == schoolId) { "Load the matching school before requesting its timetable" }
        return meta.schoolKey
    }

    companion object {
        const val DEFAULT_SCHOOL_KEY = "5738623c4f3588f82583378c44ceb026102d6bae"
    }

    fun setProxy(api: IbuprofenProxyApi?) {
        this.proxyApi = api
    }

    // In-memory cache: (schoolId, classId, weekId) -> TimetableWeek
    private val weekCache = ConcurrentHashMap<Triple<Int, Int, Int>, TimetableWeek>()
    fun clearAllCaches() {
        weekCache.clear()
    }

    suspend fun loadSchoolMeta(schoolKey: String, useProxy: Boolean = true): SchoolMeta {
        println("[DEBUG_LOG] TimetableRepository.loadSchoolMeta: schoolKey=$schoolKey, proxyApi=$proxyApi")
        proxyApi?.takeIf { useProxy }?.let { proxy ->
            try {
                val res = proxy.getSchoolMeta(schoolKey)
                println("[DEBUG_LOG] TimetableRepository.loadSchoolMeta: proxy response code=${res.code()}")
                if (res.isSuccessful) {
                    return (res.body()?.data ?: error("Empty school meta from proxy")).also { schoolKeys[it.schoolId] = schoolKey }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                println("[DEBUG_LOG] TimetableRepository.loadSchoolMeta: proxy error=${e.message}")
            }
        }
        val res = api.getSchoolPage(schoolKey)
        val body = res.body() ?: error("Empty school page")
        if (!res.isSuccessful) error("School page: HTTP ${res.code()}")
        return SchoolPageParser.parse(body, schoolKey).also { schoolKeys[it.schoolId] = it.schoolKey }
    }

    suspend fun loadTimetableWeek(schoolId: Int, classId: Int, weekId: Int, useProxy: Boolean = true): TimetableWeek {
        require(weekId in 0..52) { "weekId out of range" }
        val key = Triple(schoolId, classId, weekId)
        weekCache[key]?.let { return it }

        println("[DEBUG_LOG] TimetableRepository.loadTimetableWeek: key=$key, proxyApi=$proxyApi")
        proxyApi?.takeIf { useProxy }?.let { proxy ->
            try {
                val res = proxy.getClassTimetable(resolveSchoolKey(schoolId), classId, weekId)
                println("[DEBUG_LOG] TimetableRepository.loadTimetableWeek: proxy response code=${res.code()}")
                if (res.isSuccessful) {
                    val body = res.body()?.data ?: error("Empty timetable from proxy")
                    val parsed = body.toDomain(classId)
                    weekCache[key] = parsed
                    return parsed
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                println("[DEBUG_LOG] TimetableRepository.loadTimetableWeek: proxy error=${e.message}")
            }
        }

        val res = api.getTimetable(resolveSchoolKey(schoolId), classId, weekId = weekId)
        if (!res.isSuccessful) error("Timetable: HTTP ${res.code()}")
        val body = res.body() ?: error("Empty timetable body")
        val parsed = withContext(Dispatchers.Default) { TimetableParser.parse(classId, body) }
        weekCache[key] = parsed
        return parsed
    }

    // Aggregate directly from the public source while the proxy is being updated.
    suspend fun loadSchoolTimetables(
        schoolId: Int,
        classes: List<ClassInfo>,
        weekId: Int,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): SchoolTimetables = coroutineScope {
        val ready = mutableMapOf<Int, TimetableWeek>()
        proxyApi?.let { proxy ->
            try {
                val response = proxy.getSchoolTimetables(resolveSchoolKey(schoolId), weekId)
                if (response.isSuccessful) response.body()?.let { envelope ->
                    val missing = envelope.meta?.missing_class_ids.orEmpty().toSet()
                    envelope.data.filter { it.classId !in missing }.forEach { cached ->
                        val cls = classes.firstOrNull { it.id == cached.classId } ?: return@forEach
                        val week = cached.toDomain(cls.id)
                        ready[cls.id] = annotateWeekWithClassLabel(week, cls.label)
                        weekCache[Triple(schoolId, cls.id, weekId)] = week
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        val sem = Semaphore(4)
        val completed = java.util.concurrent.atomic.AtomicInteger()
        val results = classes.map { cls ->
            async {
                val week = try {
                    sem.withPermit {
                        ready[cls.id] ?: annotateWeekWithClassLabel(loadTimetableWeek(schoolId, cls.id, weekId), cls.label)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
                onProgress(completed.incrementAndGet(), classes.size)
                cls to week
            }
        }.awaitAll()
        SchoolTimetables(results.mapNotNull { it.second }, results.filter { it.second == null }.map { it.first })
    }

    suspend fun loadAllTimetablesForWeek(schoolId: Int, classes: List<ClassInfo>, weekId: Int): List<TimetableWeek> =
        loadSchoolTimetables(schoolId, classes, weekId).weeks

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

    fun collectTeachersFromTimetables(timetables: List<TimetableWeek>): List<String> =
        TimetableAggregation.teachers(timetables)

    suspend fun buildTeacherTimetable(allClassWeeks: List<TimetableWeek>, teacherFullName: String): TimetableWeek? =
        TimetableAggregation.forTeacher(allClassWeeks, teacherFullName)
}
