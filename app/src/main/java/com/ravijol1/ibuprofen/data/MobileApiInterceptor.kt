package com.ravijol1.ibuprofen.data

import okhttp3.Interceptor
import okhttp3.Response

/** Protocol headers from the supplied 2.16.0 mobile capture; no captured identities are reused. */
class MobileApiInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.newBuilder().setQueryParameter("wl", "true").build()
        return chain.proceed(request.newBuilder().url(url)
            .header("Accept", "application/json")
            .header("X-App-Name", "moj_asistent_parent")
            .header("X-Client-Version", "100000")
            .header("X-Client-Platform", "android")
            .header("User-Agent", "eA-MAS/2.16.0 (Android 16)")
            .build())
    }
}
