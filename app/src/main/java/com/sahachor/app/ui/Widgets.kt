package com.sahachor.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sahachor.app.R

/**
 * Shown while the agent works: a 3D cube turning behind the hovering LIFE EASY bird, what it is doing right now,
 * how many seconds have passed, and a Stop button. Runs fully on the phone, so it also works offline.
 */
@Composable
fun ThinkingIndicator(status: String, elapsedSec: Int, onStop: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val bob by transition.animateFloat(
        initialValue = -4f, targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob",
    )
    val hint = when {
        elapsedSec >= 40 -> "এখনো কাজ চলছে। ধীর নেটওয়ার্ক বা ব্যস্ত মডেল হতে পারে; সাড়া না পেলে নিজে অন্য মডেলে যাবে।"
        elapsedSec >= 15 -> "একটু সময় লাগছে, কাজ চলছে…"
        else -> ""
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Spinner3D(Modifier.fillMaxSize(), MaterialTheme.colorScheme.primary)
                Image(painterResource(R.drawable.life_easy_logo), contentDescription = null, modifier = Modifier.size(26.dp).offset(y = bob.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(status, style = MaterialTheme.typography.titleSmall)
                Text("$elapsedSec সেকেন্ড", style = MaterialTheme.typography.bodySmall)
                if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onStop) { Text("থামান") }
        }
    }
}
