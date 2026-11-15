package com.sahachor.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahachor.app.SahachorApp
import com.sahachor.app.data.AppDatabase
import com.sahachor.app.data.Message
import com.sahachor.app.llm.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SetupUi(val busy: Boolean = false, val connected: Boolean = false, val errorMessage: String? = null, val errorFix: String? = null)
data class ChatUi(val messages: List<Message> = emptyList(), val sending: Boolean = false, val error: LlmError? = null, val hasProvider: Boolean = false, val dbReady: Boolean = false)

class MainViewModel(private val app: SahachorApp) : ViewModel() {
    private val _setup = MutableStateFlow(SetupUi())
    val setup: StateFlow<SetupUi> = _setup

    private val _chat = MutableStateFlow(ChatUi(hasProvider = app.router.hasProvider(), dbReady = app.db != null))
    val chat: StateFlow<ChatUi> = _chat

    private var cid: Long? = null
    private val ready = CompletableDeferred<Unit>() // completes once the conversation row is known, so no message is mis-routed on a fast first send
    private val localMessages = mutableListOf<Message>() // used only when the DB is unavailable

    init { viewModelScope.launch { observeMessages() } }

    private suspend fun observeMessages() {
        val db: AppDatabase? = app.db
        if (db == null) { ready.complete(Unit); return }
        val id = try {
            db.chat().firstConversationId() ?: db.chat().insertConversation(com.sahachor.app.data.Conversation(title = "প্রধান চ্যাট", createdAt = System.currentTimeMillis()))
        } catch (e: Exception) { ready.complete(Unit); return }
        cid = id
        ready.complete(Unit)
        db.chat().messages(id).collect { list -> _chat.update { it.copy(messages = list) } }
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
        if (clean.isEmpty() || _chat.value.sending) return
        _chat.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            ready.await()
            val now = System.currentTimeMillis()
            val userMsg = Message(conversationId = cid ?: 0, role = "user", content = clean, createdAt = now)
            record(userMsg)
            val history = (app.db?.let { d -> cid?.let { d.chat().lastMessages(it, 20).reversed() } } ?: localMessages.takeLast(20))
                .map { ChatMsg(it.role, it.content) }
            val res = app.router.chat("তুমি সহচর, একজন সহায়ক ব্যক্তিগত সহকারী। ব্যবহারকারী যে ভাষায় লেখেন সেই ভাষায় সংক্ষিপ্ত ও স্পষ্ট উত্তর দাও।", history)
            res.onSuccess { record(Message(conversationId = cid ?: 0, role = "assistant", content = it, createdAt = System.currentTimeMillis())) }
            _chat.update { it.copy(sending = false, error = (res.exceptionOrNull() as? LlmException)?.error, hasProvider = app.router.hasProvider()) }
        }
    }

    private suspend fun record(m: Message) {
        val db = app.db
        val id = cid
        if (db != null && id != null) db.chat().insertMessage(m) else { localMessages += m; _chat.update { it.copy(messages = localMessages.toList()) } }
    }
}
