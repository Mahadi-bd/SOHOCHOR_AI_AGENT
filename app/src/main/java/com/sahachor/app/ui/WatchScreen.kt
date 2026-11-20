package com.sahachor.app.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun whenText(ms: Long?) = if (ms == null) "এখনো হয়নি" else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

private fun everyText(min: Int) = when {
    min >= 1440 -> "প্রতি ${min / 1440} দিনে"
    min >= 60 -> "প্রতি ${min / 60} ঘণ্টায়"
    else -> "প্রতি $min মিনিটে"
}

/** Tasks and site watching share one tab for now. */
@Composable
fun TasksHub(vm: MainViewModel) {
    var section by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text("✅ কাজ") })
            FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text("👁 সাইট নজরদারি") })
        }
        Box(Modifier.weight(1f)) { if (section == 0) TasksScreen(vm) else WatchScreen(vm) }
    }
}

@Composable
fun WatchScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val sites by vm.watchSites.collectAsState()
    val found by vm.watchItems.collectAsState()
    val msg by vm.watchMessage.collectAsState()
    var url by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var keywords by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf(180) }
    var showForm by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("কোনো সাইটের ঠিকানা দিন, কী খুঁজবে বলুন। এজেন্ট ব্যাকগ্রাউন্ডে নিজে দেখবে, নতুন ডকুমেন্ট এলে পড়ে সারাংশসহ জানাবে। চ্যাটে বা ভয়েসেও বলতে পারেন: \"এই সাইটে নতুন আদেশ এলে জানাও\"।", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showForm = !showForm }) { Text(if (showForm) "বন্ধ করুন" else "＋ সাইট যোগ করুন") }
                if (sites.isNotEmpty()) OutlinedButton(onClick = { vm.checkWatchNow() }) { Text("এখনই চেক") }
            }
        }
        msg?.let { m ->
            item {
                Text(m, style = MaterialTheme.typography.bodyMedium)
                LaunchedEffect(m) { kotlinx.coroutines.delay(7000); vm.clearWatchMessage() }
            }
        }
        if (showForm) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("সাইটের ঠিকানা (https://…)") })
                        OutlinedTextField(value = goal, onValueChange = { goal = it }, modifier = Modifier.fillMaxWidth(), label = { Text("কী খুঁজবে? যেমন: নতুন অফিস আদেশ") })
                        OutlinedTextField(value = keywords, onValueChange = { keywords = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("শব্দ (ঐচ্ছিক, কমা দিয়ে)") })
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30 to "৩০ মিনিট", 60 to "১ ঘণ্টা", 180 to "৩ ঘণ্টা", 360 to "৬ ঘণ্টা", 1440 to "১ দিন").forEach { (v, label) ->
                                FilterChip(selected = interval == v, onClick = { interval = v }, label = { Text(label) })
                            }
                        }
                        Button(onClick = { vm.addWatch(url, goal, keywords, interval); if (url.isNotBlank() && goal.isNotBlank()) { url = ""; goal = ""; keywords = ""; showForm = false } }) { Text("যোগ করুন") }
                        Text("ফোন ব্যাকগ্রাউন্ডে সর্বনিম্ন ১৫ মিনিট পরপর চেক চালাতে পারে; সময় একটু এদিক-ওদিক হতে পারে। শুধু https সাইট চলে।", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (sites.isNotEmpty()) item { Text("নজরে থাকা সাইট", style = MaterialTheme.typography.titleMedium) }
        items(sites, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                    Text(s.url, style = MaterialTheme.typography.bodySmall)
                    Text("লক্ষ্য: ${s.goal}", style = MaterialTheme.typography.bodySmall)
                    Text("${everyText(s.intervalMin)} · শেষ চেক: ${whenText(s.lastChecked)}" + if (s.baselined) "" else " · প্রথম স্ক্যান বাকি", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.removeWatch(s.id) }) { Text("মুছুন") }
                }
            }
        }
        if (found.isNotEmpty()) item { Text("পাওয়া নতুন জিনিস", style = MaterialTheme.typography.titleMedium) }
        items(found, key = { it.id }) { it2 ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(it2.subject ?: it2.title, style = MaterialTheme.typography.titleSmall)
                    Text("${whenText(it2.discoveredAt)}" + (it2.itemDate?.let { d -> " · তারিখ: $d" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    it2.summary?.let { s -> Text(s.take(300), style = MaterialTheme.typography.bodyMedium) }
                    TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it2.url))) } }) { Text("খুলুন") }
                }
            }
        }
        if (sites.isEmpty() && !showForm) item { Text("এখনো কোনো সাইট নেই। ওপরের বোতামে যোগ করুন।", style = MaterialTheme.typography.bodyMedium) }
    }
}
