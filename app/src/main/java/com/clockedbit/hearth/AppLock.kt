package com.clockedbit.hearth

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Optional app lock. Uses the phone's own fingerprint / face / PIN screen; Hearth never
 * sees or stores your PIN or fingerprint, and nothing leaves the device.
 */
object AppLock {
    val locked = MutableStateFlow(false)

    /** Set right before Hearth opens a system picker or share sheet, so coming back doesn't ask again. */
    @Volatile var skipNextLock = false

    fun deviceIsSecure(ctx: Context): Boolean =
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /** Shows the system unlock prompt. [legacy] is used on Android 8 (no BiometricPrompt there). */
    fun prompt(activity: Activity, legacy: () -> Unit) {
        if (!deviceIsSecure(activity)) { locked.value = false; return }
        if (Build.VERSION.SDK_INT < 28) { legacy(); return }
        val b = BiometricPrompt.Builder(activity).setTitle("Unlock Hearth").setSubtitle("Your library is locked")
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        }
        b.build().authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { locked.value = false }
        })
    }
}
