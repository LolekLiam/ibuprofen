package com.ravijol1.ibuprofen

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

internal class VaultPromptCancelled : IllegalStateException("Unlock cancelled.")

internal fun vaultAuthenticators(): Int = if (Build.VERSION.SDK_INT >= 30)
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
else BiometricManager.Authenticators.BIOMETRIC_STRONG

internal fun canUnlockVault(activity: FragmentActivity): Boolean =
    BiometricManager.from(activity).canAuthenticate(vaultAuthenticators()) == BiometricManager.BIOMETRIC_SUCCESS

/** The returned cipher, not a UI boolean, carries Keystore's authorization. */
internal suspend fun unlockVault(activity: FragmentActivity, cipher: Cipher): Cipher = withContext(Dispatchers.Main.immediate) {
    check(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { "Open Account to unlock this login." }
    suspendCancellableCoroutine { continuation ->
        lateinit var observer: LifecycleEventObserver
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                activity.lifecycle.removeObserver(observer)
                if (continuation.isActive) {
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated != null) continuation.resumeWith(Result.success(authenticated))
                    else continuation.resumeWith(Result.failure(IllegalStateException("No authenticated cipher returned.")))
                }
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                activity.lifecycle.removeObserver(observer)
                if (continuation.isActive) continuation.resumeWith(Result.failure(
                    if (errorCode in listOf(BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON)) VaultPromptCancelled()
                    else IllegalStateException("Device unlock unavailable. Use manual sign-in.")))
            }
        })
        observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { prompt.cancelAuthentication(); continuation.cancel() }
        }
        activity.lifecycle.addObserver(observer)
        continuation.invokeOnCancellation {
            activity.runOnUiThread { activity.lifecycle.removeObserver(observer); prompt.cancelAuthentication() }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock saved login")
            .setSubtitle("Use your device authentication to access this login")
            .setAllowedAuthenticators(vaultAuthenticators())
        if (Build.VERSION.SDK_INT < 30) info.setNegativeButtonText("Cancel")
        try { prompt.authenticate(info.build(), BiometricPrompt.CryptoObject(cipher)) }
        catch (e: Exception) {
            activity.lifecycle.removeObserver(observer)
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
    }
}
