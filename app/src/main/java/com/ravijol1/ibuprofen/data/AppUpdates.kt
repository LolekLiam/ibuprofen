package com.ravijol1.ibuprofen.data

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

internal data class AppVersion(val major: Long, val minor: Long, val patch: Long, val pre: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val core = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
        if (core != 0) return core
        if (pre.isEmpty() || other.pre.isEmpty()) return when { pre.isEmpty() && other.pre.isEmpty() -> 0; pre.isEmpty() -> 1; else -> -1 }
        pre.zip(other.pre).forEach { (a, b) ->
            val aNumber = a.toLongOrNull(); val bNumber = b.toLongOrNull()
            val comparison = when {
                aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                aNumber != null -> -1
                bNumber != null -> 1
                else -> a.compareTo(b)
            }
            if (comparison != 0) return comparison
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(value: String?): AppVersion? {
            val match = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(value.orEmpty()) ?: return null
            if (value?.substringAfter('+', "")?.takeIf { it.isNotEmpty() }?.split('.')?.any { it.isEmpty() } == true) return null
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            if (pre.any { it.isEmpty() || (it.all(Char::isDigit) && (it.length > 1 && it.startsWith('0') || it.toLongOrNull() == null)) }) return null
            return AppVersion(match.groupValues[1].toLongOrNull() ?: return null, match.groupValues[2].toLongOrNull() ?: return null,
                match.groupValues[3].toLongOrNull() ?: return null, pre)
        }
    }
}
internal data class GithubRelease(@SerializedName("tag_name") val tag: String?, val draft: Boolean = false, val prerelease: Boolean = false)
internal data class AppUpdate(val tag: String) {
    val url: String get() = "https://github.com/LolekLiam/ibuprofen/releases/tag/$tag"
}
internal fun findAppUpdate(currentTag: String, releases: List<GithubRelease>): AppUpdate? {
    val current = AppVersion.parse(currentTag) ?: return null
    return releases.asSequence().filterNot { it.draft }.mapNotNull { release ->
        val version = AppVersion.parse(release.tag) ?: return@mapNotNull null
        val currentCanary = current.pre == listOf("dev")
        val candidateCanary = version.pre == listOf("dev")
        if (currentCanary != candidateCanary) return@mapNotNull null
        if ((release.prerelease || version.pre.isNotEmpty()) && current.pre.isEmpty()) return@mapNotNull null
        // Beta installations receive stable/beta/RC builds, not development alpha builds.
        if (current.pre.firstOrNull()?.startsWith("beta") == true && version.pre.firstOrNull()?.startsWith("alpha") == true) return@mapNotNull null
        if (version <= current) null else version to AppUpdate(requireNotNull(release.tag))
    }.maxByOrNull { it.first }?.second
}
internal interface GithubReleasesApi {
    @Headers("Accept: application/vnd.github+json", "X-GitHub-Api-Version: 2022-11-28")
    @GET("repos/LolekLiam/ibuprofen/releases?per_page=100")
    suspend fun releases(@Query("page") page: Int): List<GithubRelease>
}
internal object AppUpdateChecker {
    private val api by lazy {
        Retrofit.Builder().baseUrl("https://api.github.com/")
            .client(OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build())
            .addConverterFactory(GsonConverterFactory.create()).build().create(GithubReleasesApi::class.java)
    }
    suspend fun check(currentTag: String): AppUpdate? = withContext(Dispatchers.IO) {
        // Canary traffic must not hide regular releases beyond the first API page.
        val releases = mutableListOf<GithubRelease>()
        var page = 1
        do {
            val batch = api.releases(page++)
            releases += batch
        } while (batch.size == 100)
        findAppUpdate(currentTag, releases)
    }
}
