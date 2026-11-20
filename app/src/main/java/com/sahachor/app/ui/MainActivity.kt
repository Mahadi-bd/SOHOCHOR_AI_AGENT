package com.sahachor.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sahachor.app.SahachorApp
import com.sahachor.app.core.BioStatus
import com.sahachor.app.core.Biometrics
import kotlinx.coroutines.launch

/** FragmentActivity (not just ComponentActivity) because the system fingerprint prompt needs it. */
class MainActivity : FragmentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* reminders still saved if denied */ }
    private lateinit var app: SahachorApp

    /** Blocks screenshots and screen recording of this app when the user asks for it in Settings. */
    fun applySecure(on: Boolean) {
        if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as SahachorApp
        applySecure(app.settings.secureScreen.value)
        if (app.settings.appLock.value && Biometrics.status(this) == BioStatus.OK) app.session.appLocked.value = true
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val mode by app.settings.themeMode.collectAsState()
            val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    val vm: MainViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
                    })
                    val locked by app.session.appLocked.collectAsState()
                    var onboarding by rememberSaveable { mutableStateOf(!app.router.hasProvider()) }
                    var tab by rememberSaveable { mutableStateOf(0) } // 0 chat, 1 tasks, 2 documents, 3 vault, 4 settings, 5 memory (inside settings)
                    LaunchedEffect(tab) { if (tab != 3) vm.lockVault() } // leaving the vault locks it again

                    if (locked) {
                        LockScreen(vm)
                    } else {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            Box(Modifier.weight(1f)) {
                                if (onboarding) OnboardingScreen(vm) { onboarding = false }
                                else when (tab) {
                                    0 -> ChatScreen(vm) { vm.resetSetup(); onboarding = true }
                                    1 -> TasksHub(vm)
                                    2 -> DocumentsScreen(vm)
                                    3 -> VaultScreen(vm)
                                    4 -> SettingsScreen(vm, onAddKey = { vm.resetSetup(); onboarding = true }, onOpenMemory = { tab = 5 })
                                    else -> Column(Modifier.fillMaxSize()) {
                                        TextButton(onClick = { tab = 4 }) { Text("← সেটিংসে ফিরুন") }
                                        Box(Modifier.weight(1f)) { MemoryScreen(vm) }
                                    }
                                }
                            }
                            if (!onboarding) NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                                listOf("💬" to "চ্যাট", "✅" to "কাজ", "📄" to "ডকুমেন্ট", "🔒" to "ভল্ট", "⚙️" to "সেটিংস").forEachIndexed { i, (icon, label) ->
                                    NavigationBarItem(selected = tab == i || (i == 4 && tab == 5), onClick = { tab = i }, icon = { Text(icon) }, label = { Text(label) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val now = SystemClock.elapsedRealtime()
        app.session.backgroundedAt = now
        if (now >= app.session.skipLockUntil) { // not just opening a file picker or viewer
            app.session.lockVault()
            app.appScope.launch { app.vault?.wipeTemp() }
        }
    }

    override fun onStart() {
        super.onStart()
        val s = app.session
        val away = SystemClock.elapsedRealtime() - s.backgroundedAt
        if (app.settings.appLock.value && s.backgroundedAt != 0L && away > 30_000L && Biometrics.status(this) == BioStatus.OK) s.appLocked.value = true
    }
}
