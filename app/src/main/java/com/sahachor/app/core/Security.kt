package com.sahachor.app.core

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow

/** Non-secret preferences (colours, switches). API keys and vault data never go through here. */
class AppSettings(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val themeMode = MutableStateFlow(prefs.getString("theme", "system") ?: "system") // system | light | dark
    val docTheme = MutableStateFlow(prefs.getString("doc_theme", "auto") ?: "auto")    // auto | blue | green | ...
    val appLock = MutableStateFlow(prefs.getBoolean("app_lock", false))
    val secureScreen = MutableStateFlow(prefs.getBoolean("secure_screen", false))

    fun setThemeMode(v: String) { prefs.edit().putString("theme", v).apply(); themeMode.value = v }
    fun setDocTheme(v: String) { prefs.edit().putString("doc_theme", v).apply(); docTheme.value = v }
    fun setAppLock(v: Boolean) { prefs.edit().putBoolean("app_lock", v).apply(); appLock.value = v }
    fun setSecureScreen(v: Boolean) { prefs.edit().putBoolean("secure_screen", v).apply(); secureScreen.value = v }
}

/** What is unlocked right now. Everything locks again when the app goes to the background (unless a file viewer was just opened). */
class SessionLock {
    val vaultUnlocked = MutableStateFlow(false)
    val appLocked = MutableStateFlow(false)
    @Volatile var backgroundedAt: Long = 0L
    @Volatile var skipLockUntil: Long = 0L

    fun lockVault() { vaultUnlocked.value = false }

    /** Call right before opening another app (a file picker or viewer) so that leaving our screen does not lock the vault. */
    fun holdLock(forMillis: Long = 8_000L) { skipLockUntil = android.os.SystemClock.elapsedRealtime() + forMillis }
}

enum class BioStatus { OK, NONE_ENROLLED, NO_HARDWARE, UNAVAILABLE }

/** Fingerprint/face, with the phone's PIN, pattern or password as the fallback, through the system's own secure prompt. */
object Biometrics {
    private val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun status(ctx: Context): BioStatus = when (BiometricManager.from(ctx).canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> BioStatus.OK
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BioStatus.NONE_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BioStatus.NO_HARDWARE
        else -> BioStatus.UNAVAILABLE
    }

    fun message(s: BioStatus): String = when (s) {
        BioStatus.OK -> ""
        BioStatus.NONE_ENROLLED -> "ফোনে আঙুলের ছাপ বা স্ক্রিন লক (পিন/প্যাটার্ন/পাসওয়ার্ড) সেট করা নেই। ফোনের সেটিংসে গিয়ে সেট করে আবার আসুন।"
        BioStatus.NO_HARDWARE -> "এই ফোনে আঙুলের ছাপ বা স্ক্রিন লক যাচাইয়ের সুবিধা নেই।"
        BioStatus.UNAVAILABLE -> "এখন যাচাই চালু করা যাচ্ছে না। একটু পরে চেষ্টা করুন।"
    }

    fun prompt(activity: FragmentActivity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onResult(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onResult(false) }
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(authenticators).build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}
