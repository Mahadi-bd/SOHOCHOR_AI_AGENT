package com.sahachor.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahachor.app.SahachorApp
import com.sahachor.app.agent.*
import com.sahachor.app.core.AppSettings
import com.sahachor.app.core.SessionLock
import com.sahachor.app.data.*
import com.sahachor.app.llm.*
import com.sahachor.app.vault.VaultItem
import com.sahachor.app.vault.VaultNames
import com.sahachor.app.vault.VaultStore
import com.sahachor.app.vault.readLimited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SetupUi(val busy: Boolean = false, val connected: Boolean = false, val errorMessage: String? = null, val errorFix: String? = null)
data class ChatUi(
    val messages: List<Message> = emptyList(), val sending: Boolean = false, val error: LlmError? = null,
    val hasProvider: Boolean = false, val keys: Int = 0, val dbReady: Boolean = true, val pending: PendingAction? = null, val notice: String? = null,
    val status: String? = null, val elapsedSec: Int = 0,
)
data class DocItem(val name: String, val sizeKb: Long, val modified: Long)

class MainViewModel(private val app: SahachorApp) : ViewModel() {
    val settings: AppSettings get() = app.settings
    val session: SessionLock get() = app.session

    private val _setup = MutableStateFlow(SetupUi())
    val setup: StateFlow<SetupUi> = _setup
    private val _chat = MutableStateFlow(ChatUi(hasProvider = app.router.hasProvider(), keys = keyCount()))
    val chat: StateFlow<ChatUi> = _chat
    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories
    private val _documents = MutableStateFlow<List<DocItem>>(emptyList())
    val documents: StateFlow<List<DocItem>> = _documents
    private val _keyList = MutableStateFlow(app.router.providers())
    val keyList: StateFlow<List<ProviderConfig>> = _keyList
    private val _vaultItems = MutableStateFlow<List<VaultItem>>(emptyList())
    val vaultItems: StateFlow<List<VaultItem>> = _vaultItems
    private val _vaultMessage = MutableStateFlow<String?>(null)
    val vaultMessage: StateFlow<String?> = _vaultMessage

    private var db: AppDatabase? = null
    private var engine: AgentEngine? = null
    private var cid: Long? = null
    private var sendJob: Job? = null
    private val ready = CompletableDeferred<Unit>() // completes once the DB state and agent are known
    private val localMessages = mutableListOf<Message>() // only used when the DB is unavailable

    init {
        viewModelScope.launch { bootstrap() }
        refreshDocuments()
    }

    private fun keyCount() = app.router.providers().count { it.valid }

    private suspend fun bootstrap() {
        val d = app.dbDeferred.await()
        db = d
        engine = buildEngine(d)
        if (d == null) { _chat.update { it.copy(dbReady = false) }; ready.complete(Unit); return }
        val id = try {
            d.chat().firstConversationId() ?: d.chat().insertConversation(Conversation(title = "প্রধান চ্যাট", createdAt = System.currentTimeMillis()))
        } catch (e: Exception) { _chat.update { it.copy(dbReady = false) }; db = null; engine = buildEngine(null); ready.complete(Unit); return }
        cid = id
        ready.complete(Unit)
        coroutineScope {
            launch { d.tasks().all().collect { _tasks.value = it } }
            launch { d.memory().all().collect { _memories.value = it } }
            d.chat().messages(id).collect { list -> _chat.update { it.copy(messages = list) } }
        }
    }

    private fun buildEngine(d: AppDatabase?): AgentEngine {
        val tools: List<Tool> = buildList {
            add(FetchUrlTool())
            add(CreateDocumentTool(app.documents) { app.settings.docTheme.value.takeIf { it != "auto" } })
            add(ZipDocumentsTool(app.documents)); addAll(PhoneTools.all(app))
            if (d != null) addAll(listOf(
                CreateReminderTool(d, app.scheduler), ListTasksTool(d), CompleteTaskTool(d, app.scheduler),
                DeleteTaskTool(d, app.scheduler), RememberTool(d), RecallTool(d),
            ))
        }
        return AgentEngine(
            chatter = app.router, tools = tools,
            memoryContext = { d?.memory()?.top(15)?.joinToString("\n") { "- ${it.type}/${it.key}: ${it.value}" }.orEmpty() },
            audit = { action, detail -> d?.audit()?.insert(AuditLog(ts = System.currentTimeMillis(), action = action, detail = detail)) },
            onStatus = { s -> _chat.update { it.copy(status = s) } },
        )
    }

    fun resetSetup() { _setup.value = SetupUi() } // so the add-key screen does not close itself because of an earlier success

    fun connect(raw: String, forced: ProviderType) {
        if (_setup.value.busy) return
        _setup.value = SetupUi(busy = true)
        viewModelScope.launch {
            when (val r = app.keySetup.connect(raw, forced)) {
                is SetupResult.Success -> {
                    app.router.add(r.config)
                    refreshKeys()
                    _chat.update { it.copy(hasProvider = true, keys = keyCount(), notice = r.note) }
                    _setup.value = SetupUi(connected = true)
                }
                is SetupResult.Failure -> _setup.value = SetupUi(errorMessage = r.error.message, errorFix = r.error.fix)
            }
        }
    }

    fun refreshKeys() { _keyList.value = app.router.providers() }

    fun removeKey(cfg: ProviderConfig) {
        app.router.removeKey(cfg.type, cfg.apiKey)
        refreshKeys()
        _chat.update { it.copy(hasProvider = app.router.hasProvider(), keys = keyCount()) }
    }

    /** Runs agent work with a visible clock, and lets the user stop it. */
    private fun launchWork(block: suspend () -> Unit) {
        sendJob = viewModelScope.launch {
            val ticker = launch {
                var s = 0
                while (true) { delay(1000); s++; _chat.update { it.copy(elapsedSec = s) } }
            }
            try {
                block()
            } catch (e: CancellationException) {
                _chat.update { it.copy(sending = false, status = null, elapsedSec = 0, notice = "থামানো হয়েছে।") }
                throw e
            } finally {
                ticker.cancel()
            }
        }
    }

    fun stop() { sendJob?.cancel() }

    fun send(text: String) {
        val clean = text.trim()
        val cur = _chat.value
        if (clean.isEmpty() || cur.sending || cur.pending != null) return
        _chat.update { it.copy(sending = true, error = null, status = "ভাবছি…", elapsedSec = 0) }
        launchWork {
            ready.await()
            record(Message(conversationId = cid ?: 0, role = "user", content = clean, createdAt = System.currentTimeMillis()))
            if (clean.equals("/help", ignoreCase = true)) {
                record(Message(conversationId = cid ?: 0, role = "assistant", content = CommandLibrary.helpText(), createdAt = System.currentTimeMillis()))
                _chat.update { it.copy(sending = false, status = null, elapsedSec = 0) }
            } else {
                val stored = db?.let { d -> cid?.let { d.chat().lastMessages(it, 20).reversed() } } ?: localMessages.takeLast(20)
                val msgs = stored.map { ChatMsg(it.role, it.content) }.toMutableList()
                CommandLibrary.expand(clean)?.let { if (msgs.isNotEmpty()) msgs[msgs.lastIndex] = msgs.last().copy(content = it) }
                handle(engine!!.run(msgs))
            }
        }
    }

    fun confirm(approved: Boolean) {
        val p = _chat.value.pending ?: return
        _chat.update { it.copy(pending = null, sending = true, status = "কাজ করছি…", elapsedSec = 0) }
        launchWork { handle(engine!!.resume(p, approved)) }
    }

    private suspend fun handle(step: AgentStep) {
        _chat.update { it.copy(status = null, elapsedSec = 0) }
        when (step) {
            is AgentStep.Reply -> {
                record(Message(conversationId = cid ?: 0, role = "assistant", content = step.text, createdAt = System.currentTimeMillis()))
                _chat.update { it.copy(sending = false, notice = null, hasProvider = app.router.hasProvider(), keys = keyCount()) }
            }
            is AgentStep.Confirm -> _chat.update { it.copy(sending = false, pending = step.pending) }
            is AgentStep.Failed -> _chat.update { it.copy(sending = false, error = step.error, hasProvider = app.router.hasProvider(), keys = keyCount()) }
        }
        refreshKeys()
        refreshDocuments()
    }

    private suspend fun record(m: Message) {
        val d = db; val id = cid
        if (d != null && id != null) d.chat().insertMessage(m) else { localMessages += m; _chat.update { it.copy(messages = localMessages.toList()) } }
    }

    fun clearChat() {
        viewModelScope.launch {
            ready.await()
            localMessages.clear()
            val d = db
            if (d != null) d.chat().clearMessages() else _chat.update { it.copy(messages = emptyList()) } // with a database the message list updates itself
            _chat.update { it.copy(error = null, notice = null) }
        }
    }

    fun refreshDocuments() {
        viewModelScope.launch {
            _documents.value = withContext(Dispatchers.IO) { app.documents.list().map { DocItem(it.name, it.length() / 1024 + 1, it.lastModified()) } }
        }
    }

    fun zipAll() { viewModelScope.launch(Dispatchers.IO) { app.documents.zip("LIFE_EASY_files", null); refreshDocuments() } }
    fun uriFor(name: String): Uri? = app.documents.uriFor(name)
    fun deleteDocument(name: String) { viewModelScope.launch(Dispatchers.IO) { app.documents.delete(name); refreshDocuments() } }

    // ---- Vault ----
    fun refreshVault() {
        viewModelScope.launch { _vaultItems.value = withContext(Dispatchers.IO) { app.vault?.list().orEmpty() } }
    }

    fun vaultReady(): Boolean = app.vault != null

    fun lockVault() {
        app.session.lockVault()
        viewModelScope.launch(Dispatchers.IO) { app.vault?.wipeTemp() }
    }

    fun clearVaultMessage() { _vaultMessage.value = null }

    fun importToVault(uri: Uri) {
        val vault = app.vault ?: run { _vaultMessage.value = "ভল্ট চালু করা যায়নি।"; return }
        viewModelScope.launch {
            _vaultMessage.value = withContext(Dispatchers.IO) {
                try {
                    val cr = app.contentResolver
                    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
                    val mime = cr.getType(uri) ?: VaultNames.mimeFor(name)
                    val data = cr.openInputStream(uri)?.use { readLimited(it, VaultStore.MAX_BYTES) }
                    if (data == null) "ফাইল পড়া যায়নি, অথবা ২৫ MB-এর বেশি বড়।"
                    else { vault.add(name, mime, data); "\"$name\" ভল্টে এনক্রিপ্ট হয়ে রাখা হয়েছে।" }
                } catch (e: Exception) { "ফাইল রাখা যায়নি। (${e.javaClass.simpleName})" }
            }
            refreshVault()
        }
    }

    /** Encrypts a generated document into the vault and removes the plain copy. */
    fun moveDocumentToVault(name: String) {
        val vault = app.vault ?: run { _vaultMessage.value = "ভল্ট চালু করা যায়নি।"; return }
        viewModelScope.launch {
            _vaultMessage.value = withContext(Dispatchers.IO) {
                try {
                    val f = app.documents.fileOf(name)
                    if (f == null) "ফাইল পাওয়া যায়নি।"
                    else {
                        vault.add(f.name, VaultNames.mimeFor(f.name), f.readBytes())
                        app.documents.delete(f.name)
                        "\"${f.name}\" ভল্টে সরানো হয়েছে। এখন শুধু আনলক করে দেখা যাবে।"
                    }
                } catch (e: Exception) { "ভল্টে রাখা যায়নি। (${e.javaClass.simpleName})" }
            }
            refreshDocuments(); refreshVault()
        }
    }

    /** Decrypts one vault file to a temporary copy and hands back a content URI for a viewer app. */
    fun prepareVaultOpen(item: VaultItem, done: (Uri?) -> Unit) {
        val vault = app.vault
        if (vault == null) { done(null); return }
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { vault.exportTemp(item.id)?.let { vault.uriFor(it) } }
            if (uri == null) _vaultMessage.value = "ফাইল খোলা যায়নি।"
            done(uri)
        }
    }

    fun deleteFromVault(id: String) {
        val vault = app.vault ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { try { vault.delete(id) } catch (e: Exception) { /* keeps the file if the index cannot be read */ } }
            refreshVault()
        }
    }

    fun toggleTask(t: TaskItem) {
        val d = db ?: return
        viewModelScope.launch {
            val done = !t.done
            d.tasks().update(t.copy(done = done))
            if (done) app.scheduler.cancel(t.id)
            else t.dueAt?.takeIf { it > System.currentTimeMillis() }?.let { app.scheduler.schedule(t.id, t.title, it) }
        }
    }

    fun deleteMemory(id: Long) { val d = db ?: return; viewModelScope.launch { d.memory().delete(id) } }
}
