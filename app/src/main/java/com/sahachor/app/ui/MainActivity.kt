package com.sahachor.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sahachor.app.SahachorApp

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* reminders still saved if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SahachorApp
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    val vm: MainViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
                    })
                    var onboarding by rememberSaveable { mutableStateOf(!app.router.hasProvider()) }
                    var tab by rememberSaveable { mutableStateOf(0) }
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        Box(Modifier.weight(1f)) {
                            if (onboarding) OnboardingScreen(vm) { onboarding = false }
                            else when (tab) {
                                0 -> ChatScreen(vm) { vm.resetSetup(); onboarding = true }
                                1 -> TasksScreen(vm)
                                2 -> DocumentsScreen(vm)
                                else -> MemoryScreen(vm)
                            }
                        }
                        if (!onboarding) NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                            listOf("💬" to "চ্যাট", "✅" to "কাজ", "📄" to "ডকুমেন্ট", "🧠" to "স্মৃতি").forEachIndexed { i, (icon, label) ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon) }, label = { Text(label) })
                            }
                        }
                    }
                }
            }
        }
    }
}
