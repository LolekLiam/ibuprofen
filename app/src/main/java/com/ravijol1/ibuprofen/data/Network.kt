package com.ravijol1.ibuprofen.data

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

object NetworkProvider {
    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .build()
    }

    private val proxyOkHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    val api: EAsistentApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://urniki.easistent.com/")
            .client(okHttp)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(EAsistentApi::class.java)
    }

    val repository: TimetableRepository by lazy { TimetableRepository(api) }

    fun getProxyApi(baseUrl: String): IbuprofenProxyApi? {
        if (baseUrl.isBlank()) return null
        var url = baseUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        if (!url.endsWith("/")) url = "$url/"
        return try {
            Retrofit.Builder()
                .baseUrl(url)
                .client(proxyOkHttp)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(IbuprofenProxyApi::class.java)
        } catch (e: Exception) {
            // Catches IllegalArgumentException for invalid URLs
            println("[DEBUG_LOG] NetworkProvider: Failed to create proxy API for $url: ${e.message}")
            null
        }
    }

    fun adminServerUrl(input: String): String {
        val value = input.trim().let { if (it.contains("://")) it else "https://$it" }
        val uri = java.net.URI(value)
        require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Enter a server URL without credentials, query parameters, or fragments." }
        require(uri.scheme == "https" || uri.scheme == "http") { "Enter an HTTP or HTTPS server URL." }
        return value.trimEnd('/') + "/"
    }

    fun getAdminApi(baseUrl: String): ProxyAdminApi = Retrofit.Builder()
        .baseUrl(adminServerUrl(baseUrl))
        .client(OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build())
        .addConverterFactory(GsonConverterFactory.create()).build().create(ProxyAdminApi::class.java)

    // Separate OkHttp client for auth with required headers
    private val authOkHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .addInterceptor(MobileApiInterceptor())
            .build()
    }

    // Auth retrofit for www.easistent.com JSON endpoints
    private val authRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://www.easistent.com/")
            .client(authOkHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val authApi: AuthApi by lazy { authRetrofit.create(AuthApi::class.java) }
}
