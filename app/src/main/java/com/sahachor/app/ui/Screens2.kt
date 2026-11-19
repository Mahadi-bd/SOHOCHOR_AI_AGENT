package com.sahachor.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.sahachor.app.R
import com.sahachor.app.core.BioStatus
import com.sahachor.app.core.Biometrics
import com.sahachor.app.llm.maskKey
import com.sahachor.app.docs.Entitlements
import com.sahachor.app.docs.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dateText(ms: Long) = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
private fun sizeText(bytes: Long) = if (bytes >= 1024 * 1024) "${bytes / (1024 * 1024)} MB" else "${bytes / 1024 + 1} KB"

private val paletteLabels = mapOf("blue" to "নীল", "green" to "সবুজ", "purple" to "বেগুনি", "orange" to "কমলা", "teal" to "টিল", "red" to "লাল")

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(desc, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Shown when "অ্যাপ লক" is on and the app was left for a while. */
@Composable
fun LockScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    val status = remember { Biometrics.status(ctx) }
    fun unlock() {
        activity?.let { a -> Biometrics.prompt(a, "সহচর আনলক করুন", "আঙুলের ছাপ বা স্ক্রিন লক দিয়ে নিশ্চিত করুন") { ok -> if (ok) vm.session.appLocked.value = false } }
    }
    LaunchedEffect(Unit) {
        if (status == BioStatus.OK) unlock() else vm.session.appLocked.value = false // no lock method left on the phone: do not trap the user
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.life_easy_logo), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(12.dp))
        Text("সহচর · LIFE EASY", style = MaterialTheme.typography.titleLarge)
        Text("অ্যাপ লক করা আছে", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { unlock() }) { Text("আনলক করুন") }
    }
}

@Composable
fun VaultScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    val unlocked by vm.session.vaultUnlocked.collectAsState()
    val items by vm.vaultItems.collectAsState()
    val message by vm.vaultMessage.collectAsState()
    val status = remember { Biometrics.status(ctx) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importToVault(uri) }

    fun unlock() {
        activity?.let { a ->
            Biometrics.prompt(a, "ভল্ট খুলুন", "আঙুলের ছাপ বা স্ক্রিন লক দিয়ে নিশ্চিত করুন") { ok ->
                if (ok) { vm.session.vaultUnlocked.value = true; vm.refreshVault() }
            }
        }
    }
    LaunchedEffect(Unit) { if (!vm.session.vaultUnlocked.value && status == BioStatus.OK && vm.vaultReady()) unlock() else vm.refreshVault() }

    if (!vm.vaultReady()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("এই ফোনে ভল্টের এনক্রিপশন চালু করা যায়নি।", textAlign = TextAlign.Center) }
        return
    }
    if (!unlocked) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒", style = MaterialTheme.typography.displayMedium)
            Text("ভল্ট লক করা আছে", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("এখানকার গোপন ফাইল এনক্রিপ্ট হয়ে থাকে এবং কখনো AI বা ইন্টারনেটে পাঠানো হয় না।", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            if (status == BioStatus.OK) Button(onClick = { unlock() }) { Text("আঙুলের ছাপ / স্ক্রিন লক দিয়ে খুলুন") }
            else Text(Biometrics.message(status), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔓 ভল্ট খোলা", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.lockVault() }) { Text("লক করুন") }
        }
        Button(onClick = { vm.session.holdLock(); picker.launch(arrayOf("*/*")) }) { Text("ফাইল যোগ করুন (২৫ MB পর্যন্ত)") }
        message?.let { m -> Text(m, style = MaterialTheme.typography.bodySmall); LaunchedEffect(m) { kotlinx.coroutines.delay(6000); vm.clearVaultMessage() } }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("ভল্টে কোনো ফাইল নেই। ডকুমেন্ট ট্যাব থেকে \"ভল্টে রাখুন\" চাপুন, বা ওপরের বোতামে ফাইল যোগ করুন।", textAlign = TextAlign.Center) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("🔒 ${item.name}", style = MaterialTheme.typography.titleSmall)
                            Text("${sizeText(item.size)} · ${dateText(item.addedAt)}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    vm.session.holdLock(15_000)
                                    vm.prepareVaultOpen(item) { uri ->
                                        if (uri != null) runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                        }
                                    }
                                }) { Text("খুলুন") }
                                TextButton(onClick = { vm.deleteFromVault(item.id) }) { Text("মুছুন") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onAddKey: () -> Unit, onOpenMemory: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? FragmentActivity
    val s = vm.settings
    val themeMode by s.themeMode.collectAsState()
    val docTheme by s.docTheme.collectAsState()
    val appLock by s.appLock.collectAsState()
    val secure by s.secureScreen.collectAsState()
    val keys by vm.keyList.collectAsState()
    val pro by Entitlements.proState.collectAsState()
    val bill by vm.billing.state.collectAsState()
    val voiceAuto by s.voiceAutoSend.collectAsState()
    val speak by s.speakReplies.collectAsState()
    val voiceLang by s.voiceLang.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    LaunchedEffect(Unit) { vm.refreshKeys() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("AI কী")
        if (keys.isEmpty()) Text("কোনো কী যোগ করা নেই।", style = MaterialTheme.typography.bodySmall)
        keys.forEach { cfg ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${cfg.type.label} · ${maskKey(cfg.apiKey)}", style = MaterialTheme.typography.titleSmall)
                        Text("মডেল: ${cfg.model}" + if (cfg.valid) "" else " · কী কাজ করছে না", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.removeKey(cfg) }) { Text("মুছুন") }
                }
            }
        }
        OutlinedButton(onClick = onAddKey) { Text("কী যোগ করুন") }

        SectionTitle("LIFE EASY Pro")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pro) {
                    Text("✅ চালু আছে: ডকুমেন্টে LIFE EASY লোগো ও নাম থাকে না।")
                } else {
                    Text("ডকুমেন্ট (PDF, Word, স্লাইড…) থেকে LIFE EASY লোগো ও নাম সরান।")
                    if (bill.loading) Text("লোড হচ্ছে…", style = MaterialTheme.typography.bodySmall)
                    else if (!bill.available) Text("Google Play সংযোগ পাওয়া যাচ্ছে না। Play Store থেকে ইনস্টল করা অ্যাপে এটা চালু হবে।", style = MaterialTheme.typography.bodySmall)
                    else if (!bill.productFound) Text("সাবস্ক্রিপশন প্ল্যান এখনো Play Console-এ তৈরি হয়নি।", style = MaterialTheme.typography.bodySmall)
                    else Button(onClick = { activity?.let { vm.billing.buy(it) } }) { Text("সাবস্ক্রাইব করুন" + (bill.price?.let { " · $it" } ?: "")) }
                }
                bill.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { vm.billing.refresh() }) { Text("কেনা পুনরুদ্ধার / আবার দেখুন") }
            }
        }

        SectionTitle("ভয়েস")
        SwitchRow("কথা শেষ হলেই পাঠান", "মাইকে বলা শেষ হলে নিজে থেকে এজেন্টকে পাঠাবে", voiceAuto) { s.setVoiceAutoSend(it) }
        SwitchRow("উত্তর পড়ে শোনান", "এজেন্টের উত্তর ফোনের ভয়েসে পড়বে (বাংলা ভয়েস ইনস্টল থাকলে বাংলায়)", speak) { s.setSpeakReplies(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("bn-BD" to "বাংলা", "en-US" to "English").forEach { (v, label) ->
                FilterChip(selected = voiceLang == v, onClick = { s.setVoiceLang(v) }, label = { Text(label) })
            }
        }

        SectionTitle("চেহারা")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "সিস্টেম", "light" to "হালকা", "dark" to "গাঢ়").forEach { (v, label) ->
                FilterChip(selected = themeMode == v, onClick = { s.setThemeMode(v) }, label = { Text(label) })
            }
        }
        Text("ডকুমেন্টের রং (PDF, Word, স্লাইড…)", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = docTheme == "auto", onClick = { s.setDocTheme("auto") }, label = { Text("অটো") })
            Palette.names.forEach { name ->
                FilterChip(selected = docTheme == name, onClick = { s.setDocTheme(name) }, label = { Text(paletteLabels[name] ?: name) })
            }
        }

        SectionTitle("নিরাপত্তা")
        SwitchRow("অ্যাপ লক", "অ্যাপে ফিরলে আঙুলের ছাপ বা স্ক্রিন লক চাইবে", appLock) { want ->
            val st = Biometrics.status(ctx)
            if (st != BioStatus.OK) note = Biometrics.message(st)
            else activity?.let { a ->
                Biometrics.prompt(a, if (want) "অ্যাপ লক চালু করুন" else "অ্যাপ লক বন্ধ করুন", "আপনিই কি না নিশ্চিত করুন") { ok -> if (ok) s.setAppLock(want) }
            }
        }
        SwitchRow("স্ক্রিনশট ও স্ক্রিন রেকর্ড বন্ধ", "অ্যাপের স্ক্রিন ছবি তোলা বা রেকর্ড করা যাবে না", secure) { v ->
            s.setSecureScreen(v); (activity as? MainActivity)?.applySecure(v)
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        SectionTitle("ডেটা")
        OutlinedButton(onClick = onOpenMemory) { Text("AI যা মনে রেখেছে দেখুন") }
        OutlinedButton(onClick = { confirmClear = true }) { Text("চ্যাটের ইতিহাস মুছুন") }

        SectionTitle("Google")
        Text("ডকুমেন্ট ট্যাবে প্রতিটা ফাইলের পাশে \"Drive-এ পাঠান\" চাপলে আপনার Google Drive-এ আসল Google Docs/Sheets/Slides ফাইল তৈরি হয়। অ্যাপ শুধু নিজের তৈরি ফাইলই দেখতে পারে।", style = MaterialTheme.typography.bodySmall)

        SectionTitle("সম্পর্কে")
        Text("সহচর · LIFE EASY\nসংস্করণ $version\nআপনার API কী ও ডেটা শুধু এই ফোনে এনক্রিপ্টেড থাকে।", style = MaterialTheme.typography.bodySmall)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("চ্যাট মুছবেন?") },
            text = { Text("সব মেসেজ মুছে যাবে। কাজ, স্মৃতি, ডকুমেন্ট ও ভল্ট থেকে যাবে।") },
            confirmButton = { TextButton(onClick = { vm.clearChat(); confirmClear = false }) { Text("মুছুন") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("বাতিল") } },
        )
    }
}
