package com.ravijol1.ibuprofen.data

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID

/** Opens existing storage without changing its key or healthy data. */
internal object SecurePreferences {
    @Synchronized
    fun open(context: Context, name: String): SharedPreferences {
        val app = context.applicationContext
        val marker = File(app.noBackupFilesDir, "$name.active")
        val activeName = if (marker.exists()) marker.readText().trim().also {
            require(it.startsWith("${name}_recovered_") && it.matches(Regex("[a-zA-Z0-9_-]+")))
        } else name

        fun create(fileName: String): SharedPreferences {
            val alias = if (fileName == name) MasterKey.DEFAULT_MASTER_KEY_ALIAS else fileName
            val masterKey = MasterKey.Builder(app, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(app, fileName, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM).also {
                // Validate values too, so entry decryption cannot crash later in a getter.
                it.all
            }
        }

        return recoverUnreadablePreferences(
            open = { create(activeName) },
            canRecover = { app.getSystemService(UserManager::class.java).isUserUnlocked },
            recover = {
                // Retain the unreadable file and key. Never wipe app data or fall back to plaintext.
                val replacement = "${name}_recovered_${UUID.randomUUID()}"
                val prefs = create(replacement)
                marker.writeText(replacement)
                Log.w("SecurePreferences", "Recovered unreadable $name storage; sign-in may be required.")
                prefs
            }
        )
    }
}

internal fun <T> recoverUnreadablePreferences(open: () -> T, canRecover: () -> Boolean, recover: () -> T): T {
    return try {
        open()
    } catch (error: Exception) {
        if (error !is GeneralSecurityException && error !is SecurityException && error !is IOException) throw error
        // Locked-device access is temporary, not evidence that stored credentials are corrupt.
        if (!canRecover()) throw error
        recover()
    }
}
