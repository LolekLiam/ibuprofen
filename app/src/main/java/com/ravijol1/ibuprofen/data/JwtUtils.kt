package com.ravijol1.ibuprofen.data

import java.util.Base64
import com.google.gson.Gson

object JwtUtils {
    private val gson = Gson()

    fun parsePayload(token: String): JwtPayload? {
        return try {
            val parts = token.split('.')
            if (parts.size < 2) return null
            val payload = parts[1]
            val decoded = String(Base64.getUrlDecoder().decode(payload))
            gson.fromJson(decoded, JwtPayload::class.java)
        } catch (_: Exception) {
            null
        }
    }
}