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

    val api: EAsistentApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://urniki.easistent.com/")
            .client(okHttp)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(EAsistentApi::class.java)
    }

    val repository: TimetableRepository by lazy { TimetableRepository(api) }

    // Separate OkHttp client for auth with required headers
    private val authOkHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                    .header("X-App-Name", "child")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("X-Device-Id", "child_device")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("X-client-version", "11102")
                    .header("x-client-platform", "android")
                    .header("User-Agent", "ibuprofen/0.4 (Android)")
                chain.proceed(builder.build())
            }
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
