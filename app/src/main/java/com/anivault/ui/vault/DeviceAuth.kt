package com.anivault.ui.vault

import android.content.Context
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Asks for fingerprint/face or the screen lock. [onSuccess] runs only after a successful check. */
fun authenticateDevice(context: Context, title: String, subtitle: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
    val activity = context as? FragmentActivity
    if (activity == null) { onFailure("Can't ask for authentication here."); return }
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(code: Int, msg: CharSequence) { onFailure("Not exported: $msg") }
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL).build()
    )
}
