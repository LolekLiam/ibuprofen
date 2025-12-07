package com.ravijol1.ibuprofen.data

import okhttp3.OkHttpClient
import retrofit2.Retrofit
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
}