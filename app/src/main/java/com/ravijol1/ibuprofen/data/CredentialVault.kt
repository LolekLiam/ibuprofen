package com.ravijol1.ibuprofen.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SavedAccount(val id: String, val userId: String, val username: String, val displayName: String)
internal class VaultSecret(val username: String, val password: String) {
    override fun toString(): String = "VaultSecret([redacted])"
}
internal class VaultUnavailableException : IllegalStateException("Saved login could not be unlocked. Sign in manually to replace it, or forget it.")

/** AES-GCM binds each record to its opaque identifier and format version. */
internal object VaultCipher {
    fun aad(id: String): ByteArray = "ibuprofen.saved-login:1:$id".toByteArray(Charsets.UTF_8)
    fun encrypt(cipher: Cipher, id: String, plaintext: ByteArray): ByteArray {
        cipher.updateAAD(aad(id))
        return cipher.doFinal(plaintext)
    }
    fun decrypt(cipher: Cipher, id: String, encrypted: ByteArray): ByteArray {
        cipher.updateAAD(aad(id))
        return cipher.doFinal(encrypted)
    }
}

/** Separate from session tokens; logout and background workers never unlock this vault. */
internal class CredentialVault(context: Context) {
    private val app = context.applicationContext
    private val gson = Gson()
    private val directory = File(app.noBackupFilesDir, "saved-logins")
    private val prefs by lazy { SecurePreferences.open(app, "saved_login_index") }
    private data class Envelope(val version: Int, val iv: ByteArray, val ciphertext: ByteArray)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(id: String) = "ibuprofen.saved-login.$id"
    private fun file(id: String): AtomicFile {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        return AtomicFile(File(directory, "$id.json"))
    }
    private fun entries(): List<SavedAccount> = prefs.getString("accounts", null)?.let {
        gson.fromJson(it, Array<SavedAccount>::class.java).toList()
    }.orEmpty()
    private fun writeIndex(accounts: List<SavedAccount>) {
        check(prefs.edit().putString("accounts", gson.toJson(accounts)).commit()) { "Could not save login index." }
    }
    suspend fun list(): List<SavedAccount> = withContext(Dispatchers.IO) { synchronized(this@CredentialVault) { entries() } }
    suspend fun forget(id: String) = withContext(Dispatchers.IO) {
        synchronized(this@CredentialVault) {
            file(id).delete()
            keyStore().deleteEntry(alias(id))
            writeIndex(entries().filterNot { it.id == id })
        }
    }
    suspend fun save(account: AuthRepository.Session, username: String, password: String,
        authenticate: suspend (Cipher) -> Cipher) {
        val userId = requireNotNull(account.userId) { "This account cannot be saved without its user ID." }
        val id = UUID.randomUUID().toString()
        try {
            val cipher = withContext(Dispatchers.IO) {
                val spec = KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                else spec.setInvalidatedByBiometricEnrollment(true)
                val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
                Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            }
            val unlocked = authenticate(cipher)
            withContext(Dispatchers.IO) {
                val plain = gson.toJson(VaultSecret(username.trim(), password)).toByteArray(Charsets.UTF_8)
                val encrypted = try { VaultCipher.encrypt(unlocked, id, plain) } finally { plain.fill(0) }
                synchronized(this@CredentialVault) {
                    directory.mkdirs()
                    val target = file(id)
                    val stream = target.startWrite()
                    try {
                        stream.write(gson.toJson(Envelope(1, unlocked.iv, encrypted)).toByteArray(Charsets.UTF_8))
                        target.finishWrite(stream)
                    } catch (e: Exception) { target.failWrite(stream); throw e }
                    val previous = entries()
                    val replacement = SavedAccount(id, userId, username.trim(), account.userName ?: username.trim())
                    writeIndex(previous.filterNot { it.userId == userId } + replacement)
                    previous.filter { it.userId == userId }.forEach { old ->
                        file(old.id).delete(); keyStore().deleteEntry(alias(old.id))
                    }
                }
            }
        } catch (e: Exception) {
            // A cancelled/failed replacement must not delete the old working entry.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                synchronized(this@CredentialVault) {
                    if (entries().none { it.id == id }) { file(id).delete(); keyStore().deleteEntry(alias(id)) }
                }
            }
            throw e
        }
    }
    suspend fun <T> use(id: String, authenticate: suspend (Cipher) -> Cipher, action: suspend (VaultSecret) -> T): T {
        val prepared = withContext(Dispatchers.IO) {
            try {
                val envelope = gson.fromJson(file(id).readFully().toString(Charsets.UTF_8), Envelope::class.java)
                require(envelope.version == 1 && envelope.iv.size == 12)
                val key = keyStore().getKey(alias(id), null) as? SecretKey ?: throw VaultUnavailableException()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
                }
                cipher to envelope
            } catch (_: Exception) { throw VaultUnavailableException() }
        }
        val unlocked = authenticate(prepared.first)
        var secret: VaultSecret? = null
        try {
            secret = withContext(Dispatchers.IO) {
                val plain = try { VaultCipher.decrypt(unlocked, id, prepared.second.ciphertext) }
                    catch (_: Exception) { throw VaultUnavailableException() }
                try { gson.fromJson(plain.toString(Charsets.UTF_8), VaultSecret::class.java) } finally { plain.fill(0) }
            }
            return action(requireNotNull(secret))
        } finally { secret = null }
    }
}
