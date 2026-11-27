package com.ravijol1.ibuprofen

import com.ravijol1.ibuprofen.data.VaultCipher
import com.ravijol1.ibuprofen.data.VaultSecret
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class CredentialVaultTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun cipher() = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    private fun read(iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }

    @Test fun roundTripAndIndependentEncryptionUseFreshIvs() {
        val a = cipher(); val b = cipher(); val plain = "synthetic-login-secret".toByteArray()
        val encrypted = VaultCipher.encrypt(a, "account-a", plain)
        VaultCipher.encrypt(b, "account-a", plain)
        assertFalse(a.iv.contentEquals(b.iv))
        assertArrayEquals(plain, VaultCipher.decrypt(read(a.iv), "account-a", encrypted))
        assertFalse(encrypted.contentEquals(plain))
    }
    @Test fun swappingAccountOrTamperingCiphertextIsRejected() {
        val cipher = cipher(); val encrypted = VaultCipher.encrypt(cipher, "account-a", "synthetic".toByteArray())
        assertThrows(java.security.GeneralSecurityException::class.java) { VaultCipher.decrypt(read(cipher.iv), "account-b", encrypted) }
        encrypted[0] = (encrypted[0].toInt() xor 1).toByte()
        assertThrows(java.security.GeneralSecurityException::class.java) { VaultCipher.decrypt(read(cipher.iv), "account-a", encrypted) }
    }
    @Test fun copiedRecordCannotBeReadUsingADifferentKey() {
        val cipher = cipher(); val encrypted = VaultCipher.encrypt(cipher, "account-a", "synthetic".toByteArray())
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, other, GCMParameterSpec(128, cipher.iv)) }
        assertThrows(java.security.GeneralSecurityException::class.java) { VaultCipher.decrypt(decrypt, "account-a", encrypted) }
    }
    @Test fun secretDebugRepresentationNeverContainsCredentials() {
        val secret = VaultSecret("synthetic-user", "synthetic-password")
        assertFalse(secret.toString().contains(secret.password))
        assertFalse(secret.toString().contains(secret.username))
    }
}
