package com.sahachor.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.sahachor.app.google.GoogleAuth
import com.sahachor.app.voice.VoiceInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sahachor.app.R
import com.sahachor.app.llm.NetCheck
import com.sahachor.app.llm.ProviderType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtTime(ms: Long) = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun OnboardingScreen(vm: MainViewModel, onDone: () -> Unit) {
    val s by vm.setup.collectAsState()
    var text by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(ProviderType.GEMINI) }
    var report by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val clip = LocalClipboardManager.current

    LaunchedEffect(s.connected) { if (s.connected) onDone() }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.life_easy_logo), contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("LIFE EASY", style = MaterialTheme.typography.titleMedium)
                Text("AI যুক্ত করুন", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text("সহচর আপনার নিজের API কী দিয়ে কাজ করে। কীটি শুধু এই ফোনে এনক্রিপ্ট হয়ে থাকে। সবচেয়ে সহজ পথ: Google Gemini (ফ্রি)।")
        Text("১. প্রোভাইডার বেছে নিন", style = MaterialTheme.typography.titleSmall)
        ProviderType.values().forEach { p ->
            FilterChip(selected = chosen == p, onClick = { chosen = p }, label = { Text("${p.label} · ${p.note}") })
        }
        Text("২. কী তৈরি করুন", style = MaterialTheme.typography.titleSmall)
        Text("নিচের বোতামে ট্যাপ করুন, সাইটে লগইন করে \"Create API key\" চাপুন, তারপর কী-টি কপি করুন।")
        OutlinedButton(onClick = { runCatching { uri.openUri(chosen.keyPageUrl) } }) { Text("${chosen.label}-এর কী পেজ খুলুন") }
        Text("৩. এখানে পেস্ট করুন", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("API কী") }, visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clip.getText()?.text?.let { text = it } }) { Text("ক্লিপবোর্ড থেকে পেস্ট") }
            Button(enabled = text.isNotBlank() && !s.busy, onClick = { vm.connect(text, chosen) }) { Text("যাচাই ও সংযুক্ত") }
        }
        if (s.busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("যাচাই করা হচ্ছে…") }
        SelectionContainer {
            Column {
                s.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                s.errorFix?.let { Text(it) }
            }
        }
        OutlinedButton(enabled = !testing, onClick = { testing = true; scope.launch { report = NetCheck.run(text, chosen); testing = false } }) { Text(if (testing) "পরীক্ষা চলছে…" else "নেটওয়ার্ক পরীক্ষা") }
        report?.let { r ->
            SelectionContainer { Text(r, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { clip.setText(AnnotatedString(r)) }) { Text("ফলাফল কপি করুন") }
        }
        TextButton(onClick = onDone) { Text("পরে করব") }
    }
}

@Composable
fun ChatScreen(vm: MainViewModel, onAddKey: () -> Unit) {
    val state by vm.chat.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val voiceCtx = LocalContext.current
    val voice = remember { VoiceInput(voiceCtx) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    var listening by remember { mutableStateOf(false) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    val voiceAuto by vm.settings.voiceAutoSend.collectAsState()
    val voiceLang by vm.settings.voiceLang.collectAsState()
    fun startVoice() {
        voiceNote = null
        listening = true
        voice.start(
            voiceLang,
            onText = { t -> input = t },
            onFinalText = { t ->
                val busy = vm.chat.value.sending || vm.chat.value.pending != null
                if (voiceAuto && !busy) { vm.send(t); input = "" } else { input = t }
            },
            onProblem = { m -> voiceNote = m },
            onStopped = { listening = false },
        )
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVoice() else voiceNote = "মাইক্রোফোনের অনুমতি ছাড়া ভয়েস চলবে না।"
    }
    fun onMicClick() {
        if (listening) {
            voice.stop(); listening = false
        } else if (!voice.available()) {
            voiceNote = "এই ফোনে ভয়েস চেনার সেবা পাওয়া যায়নি। Google অ্যাপ আপডেট করুন।"
        } else if (voiceCtx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(state.messages.size, state.sending) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.life_easy_logo), contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.hasProvider) "AI সংযুক্ত (${state.keys}টি কী) · /help" else "AI যুক্ত নেই", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onAddKey) { Text(if (state.hasProvider) "কী যোগ" else "কী যোগ করুন") }
            }
        }
        if (!state.dbReady) Text("সতর্কতা: এনক্রিপ্টেড ডেটাবেস খোলা যায়নি; চ্যাট, কাজ ও স্মৃতি সংরক্ষণ হবে না।", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.messages) { m ->
                val mine = m.role == "user"
                Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Surface(shape = MaterialTheme.shapes.medium, color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        SelectionContainer { Text(m.content, Modifier.padding(10.dp).widthIn(max = 300.dp)) }
                    }
                }
            }
        }
        if (state.sending) ThinkingIndicator(state.status ?: "ভাবছি…", state.elapsedSec) { vm.stop() }
        state.error?.let { SelectionContainer { Text("${it.message} ${it.fix}", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) } }
        state.notice?.let { n -> SelectionContainer { Text(n, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) } }
        state.pending?.let { p ->
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("নিশ্চিত করুন", style = MaterialTheme.typography.titleSmall)
                    Text(p.summary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.confirm(true) }) { Text("হ্যাঁ, করুন") }
                        OutlinedButton(onClick = { vm.confirm(false) }) { Text("বাতিল") }
                    }
                }
            }
        }
        if (listening) Text("🎤 শুনছি… বলুন (${if (voiceLang.startsWith("bn")) "বাংলা" else "English"})", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        voiceNote?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("লিখুন বা 🎤 চেপে বলুন…") }, maxLines = 4)
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { onMicClick() }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(if (listening) "⏹" else "🎤") }
            Spacer(Modifier.width(6.dp))
            Button(enabled = input.isNotBlank() && !state.sending && state.pending == null, onClick = { vm.send(input); input = "" }) { Text(if (state.sending) "…" else "পাঠান") }
        }
    }
}

@Composable
fun TasksScreen(vm: MainViewModel) {
    val tasks by vm.tasks.collectAsState()
    if (tasks.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("কোনো কাজ নেই। চ্যাটে বলুন: \"কাল সকাল ৯টায় মনে করিয়ে দাও…\"") }; return }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(tasks, key = { it.id }) { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = t.done, onCheckedChange = { vm.toggleTask(t) })
                Column(Modifier.weight(1f)) {
                    Text(t.title)
                    t.dueAt?.let { Text(fmtTime(it), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
fun MemoryScreen(vm: MainViewModel) {
    val mem by vm.memories.collectAsState()
    if (mem.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("এখনো কিছু মনে রাখা হয়নি। বলুন: \"মনে রেখো, আমি …\"") }; return }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(mem, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("${m.key} (${m.type})", style = MaterialTheme.typography.titleSmall); Text(m.value) }
                    TextButton(onClick = { vm.deleteMemory(m.id) }) { Text("মুছুন") }
                }
            }
        }
    }
}

/** Which Google app suits a file type (package, label). Falls back to the system chooser when the app is not installed. */
private fun googleApp(name: String): Pair<String, String>? = when (name.substringAfterLast('.').lowercase()) {
    "xlsx", "ods", "csv" -> "com.google.android.apps.docs.editors.sheets" to "Google Sheets"
    "docx", "odt" -> "com.google.android.apps.docs.editors.docs" to "Google Docs"
    "pptx" -> "com.google.android.apps.docs.editors.slides" to "Google Slides"
    else -> null
}

private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
    "csv" -> "text/csv"
    "zip" -> "application/zip"
    else -> "*/*"
}

@Composable
fun DocumentsScreen(vm: MainViewModel) {
    val docs by vm.documents.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.refreshDocuments() }
    val driveMsg by vm.driveMessage.collectAsState()
    var pendingDrive by remember { mutableStateOf<String?>(null) }
    val activity = ctx as? android.app.Activity
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val name = pendingDrive
        val token = if (activity != null && r.resultCode == android.app.Activity.RESULT_OK) GoogleAuth.tokenFromResult(activity, r.data) else null
        if (name != null && token != null) vm.uploadToDrive(name, token) else vm.setDriveMessage("Google-এর অনুমতি দেওয়া হয়নি।")
    }
    fun sendToDrive(name: String) {
        val a = activity ?: return
        pendingDrive = name
        vm.setDriveMessage("Google-এর সাথে যোগাযোগ করছি…")
        GoogleAuth.authorize(a, onToken = { vm.uploadToDrive(name, it) }, onNeedsUi = { sender -> consent.launch(IntentSenderRequest.Builder(sender).build()) }, onError = { vm.setDriveMessage(it) })
    }
    if (docs.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("কোনো ফাইল নেই। চ্যাটে বলুন: \"/meeting …\" বা \"এই তথ্যগুলো দিয়ে একটা PDF বানাও\"") }; return }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        driveMsg?.let { m ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.text)
                        m.link?.let { link -> TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))) } }) { Text("Google-এ খুলুন") } }
                    }
                }
            }
        }
        item { OutlinedButton(onClick = { vm.zipAll() }) { Text("সব ফাইল ZIP করুন") } }
        items(docs, key = { it.name }) { d ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall)
                    Text("${d.sizeKb} KB · ${fmtTime(d.modified)}", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            vm.uriFor(d.name)?.let { uri ->
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(d.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                }
                            }
                        }) { Text("খুলুন") }
                        TextButton(onClick = {
                            vm.uriFor(d.name)?.let { uri ->
                                val send = Intent(Intent.ACTION_SEND).setType(mimeOf(d.name)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { ctx.startActivity(Intent.createChooser(send, "শেয়ার করুন")) }
                            }
                        }) { Text("শেয়ার") }
                        TextButton(onClick = { sendToDrive(d.name) }) { Text("Drive-এ পাঠান") }
                        TextButton(onClick = { vm.moveDocumentToVault(d.name) }) { Text("ভল্টে রাখুন") }
                        TextButton(onClick = { vm.deleteDocument(d.name) }) { Text("মুছুন") }
                    }
                    googleApp(d.name)?.let { (pkg, label) ->
                        TextButton(onClick = {
                            vm.uriFor(d.name)?.let { uri ->
                                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(d.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                val target = Intent(view).setPackage(pkg)
                                if (runCatching { ctx.startActivity(target) }.isFailure) runCatching { ctx.startActivity(Intent.createChooser(view, "খুলুন")) }
                            }
                        }) { Text("$label-এ খুলুন") }
                    }
                }
            }
        }
    }
}
