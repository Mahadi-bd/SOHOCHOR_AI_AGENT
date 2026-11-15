package com.sahachor.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sahachor.app.SahachorApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SahachorApp
        setContent {
            MaterialTheme {
                Surface {
                    val vm: MainViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app) as T
                    })
                    var onboarding by rememberSaveable { mutableStateOf(!app.router.hasProvider()) }
                    if (onboarding) OnboardingScreen(vm) { onboarding = false } else ChatScreen(vm) { onboarding = true }
                }
            }
        }
    }
}
