package com.sahachor.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahachor.app.SahachorApp
import com.sahachor.app.agent.*
import com.sahachor.app.data.*
import com.sahachor.app.llm.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SetupUi(val busy: Boolean = false, val connected: Boolean = false, val errorMessage: String? = null, val errorFix: String? = null)
data class ChatUi(
    val messages: List<Message> = emptyList(), val sending: Boolean = false, val error: LlmError? = null,
    val hasProvider: Boolean = false, val dbReady: Boolean = true, val pending: PendingAction? = null,
)

class MainViewModel(private val app: SahachorApp) : ViewModel() {
    private val _setup = MutableStateFlow(SetupUi())
    val setup: StateFlow<SetupUi> = _setup
    private val _chat = MutableStateFlow(ChatUi(hasProvider = app.router.hasProvider()))
    val chat: StateFlow<ChatUi> = _chat
    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories

    private var db: AppDatabase? = null
    private var engine: AgentEngine? = null
    private var cid: Long? = null
    private val ready = CompletableDeferred<Unit>() // completes once the DB state and agent are known
    private val localMessages = mutableListOf<Message>() // only used when the DB is unavailable

    init { viewModelScope.launch { bootstrap() } }

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
        val tools: List<Tool> = if (d == null) emptyList() else listOf(
            CreateReminderTool(d, app.scheduler), ListTasksTool(d), CompleteTaskTool(d, app.scheduler),
            DeleteTaskTool(d, app.scheduler), RememberTool(d), RecallTool(d),
        )
        return AgentEngine(
            chatter = app.router, tools = tools,
            memoryContext = { d?.memory()?.top(15)?.joinToString("\n") { "- ${it.type}/${it.key}: ${it.value}" }.orEmpty() },
            audit = { action, detail -> d?.audit()?.insert(AuditLog(ts = System.currentTimeMillis(), action = action, detail = detail)) },
        )
    }

    fun connect(raw: String, forced: ProviderType) {
        if (_setup.value.busy) return
        _setup.value = SetupUi(busy = true)
        viewModelScope.launch {
            when (val r = app.keySetup.connect(raw, forced)) {
                is SetupResult.Success -> {
                    app.router.add(r.config)
                    _chat.update { it.copy(hasProvider = true) }
                    _setup.value = SetupUi(connected = true)
                }
                is SetupResult.Failure -> _setup.value = SetupUi(errorMessage = r.error.message, errorFix = r.error.fix)
            }
        }
    }

    fun send(text: String) {
        val clean = text.trim()
        val cur = _chat.value
        if (clean.isEmpty() || cur.sending || cur.pending != null) return
        _chat.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            ready.await()
            record(Message(conversationId = cid ?: 0, role = "user", content = clean, createdAt = System.currentTimeMillis()))
            val stored = db?.let { d -> cid?.let { d.chat().lastMessages(it, 20).reversed() } } ?: localMessages.takeLast(20)
            handle(engine!!.run(stored.map { ChatMsg(it.role, it.content) }))
        }
    }

    fun confirm(approved: Boolean) {
        val p = _chat.value.pending ?: return
        _chat.update { it.copy(pending = null, sending = true) }
        viewModelScope.launch { handle(engine!!.resume(p, approved)) }
    }

    private suspend fun handle(step: AgentStep) {
        when (step) {
            is AgentStep.Reply -> {
                record(Message(conversationId = cid ?: 0, role = "assistant", content = step.text, createdAt = System.currentTimeMillis()))
                _chat.update { it.copy(sending = false, hasProvider = app.router.hasProvider()) }
            }
            is AgentStep.Confirm -> _chat.update { it.copy(sending = false, pending = step.pending) }
            is AgentStep.Failed -> _chat.update { it.copy(sending = false, error = step.error, hasProvider = app.router.hasProvider()) }
        }
    }

    private suspend fun record(m: Message) {
        val d = db; val id = cid
        if (d != null && id != null) d.chat().insertMessage(m) else { localMessages += m; _chat.update { it.copy(messages = localMessages.toList()) } }
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
