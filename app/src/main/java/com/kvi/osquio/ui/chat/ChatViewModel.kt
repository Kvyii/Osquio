package com.kvi.osquio.ui.chat

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kvi.osquio.data.ChatRepository
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.data.model.Message
import com.kvi.osquio.data.model.User
import com.kvi.osquio.data.supabase
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Loaded(val messages: List<Message>, val users: Map<String, User>) : ChatUiState
    data class Error(val message: String) : ChatUiState
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val state = _state.asStateFlow()

    private val _hasUnread = MutableStateFlow(false)
    val hasUnread = _hasUnread.asStateFlow()

    private var realtimeJob: Job? = null
    private val channel = supabase.channel("chat")

    fun load() {
        if (_state.value is ChatUiState.Loaded) return
        viewModelScope.launch {
            try {
                val messages = ChatRepository.getMessages()
                val users = UserRepository.allUsers().associateBy { it.id }
                _state.value = ChatUiState.Loaded(messages, users)
                updateUnreadBadge(messages)
                subscribeToRealtime()
            } catch (e: Exception) {
                _state.value = ChatUiState.Error(e.message ?: "Failed to load chat")
            }
        }
    }

    private fun subscribeToRealtime() {
        if (realtimeJob?.isActive == true) return
        realtimeJob = viewModelScope.launch {
            launch {
                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "messages"
                }.collect {
                    try {
                        val messages = ChatRepository.getMessages()
                        val current = _state.value as? ChatUiState.Loaded ?: return@collect
                        _state.value = current.copy(messages = messages)
                        updateUnreadBadge(messages)
                    } catch (_: Exception) {}
                }
            }
            channel.subscribe()
        }
    }

    fun sendMessage(userId: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                ChatRepository.sendMessage(userId, trimmed)
                val messages = ChatRepository.getMessages()
                val current = _state.value as? ChatUiState.Loaded ?: return@launch
                _state.value = current.copy(messages = messages)
                markRead()
            } catch (_: Exception) {}
        }
    }

    fun markRead() {
        val current = _state.value as? ChatUiState.Loaded ?: return
        val latest = current.messages.lastOrNull() ?: return
        prefs.edit().putString("chat_last_read_at", latest.createdAt).apply()
        _hasUnread.value = false
    }

    private fun updateUnreadBadge(messages: List<Message>) {
        val lastReadAt = prefs.getString("chat_last_read_at", null)
        val latest = messages.lastOrNull() ?: return
        _hasUnread.value = if (lastReadAt == null) {
            messages.isNotEmpty()
        } else {
            latest.createdAt > lastReadAt
        }
    }

    override fun onCleared() {
        super.onCleared()
        realtimeJob?.cancel()
    }
}
