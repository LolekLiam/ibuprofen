package com.ravijol1.ibuprofen.data

import android.util.Base64
import com.google.gson.Gson

object JwtUtils {
    private val gson = Gson()

    fun parsePayload(token: String): JwtPayload? {
        return try {
            val parts = token.split('.')
            if (parts.size < 2) return null
            val payload = parts[1]
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            gson.fromJson(decoded, JwtPayload::class.java)
        } catch (_: Throwable) {
            null
        }
    }
}