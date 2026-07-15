package com.kvi.osquio.ui.chat

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kvi.osquio.data.ChatRepository
import com.kvi.osquio.data.UserRepository
import com.kvi.osquio.data.model.Message
import com.kvi.osquio.data.model.User
import com.kvi.osquio.data.supabase
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _mentionSuggestions = MutableStateFlow<List<User>>(emptyList())
    val mentionSuggestions = _mentionSuggestions.asStateFlow()

    private val _confirmedMentions = MutableStateFlow<Set<String>>(emptySet())
    val confirmedMentions = _confirmedMentions.asStateFlow()

    private val _imageSendError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val imageSendError = _imageSendError.asSharedFlow()

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
                listenForRefreshSignal()
            } catch (e: Exception) {
                _state.value = ChatUiState.Error(e.message ?: "Failed to load chat")
            }
        }
    }

    private fun listenForRefreshSignal() {
        viewModelScope.launch {
            ChatRepository.refreshSignal.collect {
                val current = _state.value as? ChatUiState.Loaded ?: return@collect
                try {
                    val messages = ChatRepository.getMessages()
                    _state.value = current.copy(messages = messages)
                    updateUnreadBadge(messages)
                } catch (_: Exception) {}
            }
        }
    }

    private fun subscribeToRealtime() {
        if (realtimeJob?.isActive == true) return
        realtimeJob = viewModelScope.launch {
            launch {
                channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    table = "messages"
                }.collect { action ->
                    val current = _state.value as? ChatUiState.Loaded ?: return@collect
                    val newMessage = runCatching {
                        when (action) {
                            is PostgresAction.Insert -> action.decodeRecord<Message>()
                            else -> null
                        }
                    }.getOrNull()
                    if (newMessage != null) {
                        if (current.messages.any { it.id == newMessage.id }) return@collect
                        val updated = current.messages + newMessage
                        _state.value = current.copy(messages = updated)
                        updateUnreadBadge(updated)
                    } else {
                        try {
                            val messages = ChatRepository.getMessages()
                            _state.value = current.copy(messages = messages)
                            updateUnreadBadge(messages)
                        } catch (_: Exception) {}
                    }
                }
            }
            channel.subscribe()
        }
    }

    fun onInputChanged(text: String) {
        val users = (_state.value as? ChatUiState.Loaded)?.users?.values?.toList() ?: return

        // Prune confirmed mentions no longer present in text
        val current = _confirmedMentions.value
        if (current.isNotEmpty()) {
            _confirmedMentions.value = current.filter { name ->
                text.contains("@$name")
            }.toSet()
        }

        val atIndex = text.lastIndexOf('@')
        if (atIndex == -1) {
            _mentionSuggestions.value = emptyList()
            return
        }
        val afterAt = text.substring(atIndex + 1)
        if (afterAt.isEmpty() || afterAt.contains(' ')) {
            _mentionSuggestions.value = emptyList()
            return
        }
        _mentionSuggestions.value = users.filter {
            it.displayName.startsWith(afterAt, ignoreCase = true)
        }
    }

    fun confirmMention(displayName: String) {
        _confirmedMentions.value = _confirmedMentions.value + displayName
    }

    fun dismissSuggestions() {
        _mentionSuggestions.value = emptyList()
    }

    fun sendMessage(userId: String, senderName: String, content: String, imageUri: Uri? = null) {
        val trimmed = content.trim()
        if (trimmed.isEmpty() && imageUri == null) return
        val users = (_state.value as? ChatUiState.Loaded)?.users?.values?.toList() ?: emptyList()
        val mentions = _confirmedMentions.value
        _confirmedMentions.value = emptySet()
        viewModelScope.launch {
            try {
                val imageUrl = imageUri?.let { uri -> uploadChatImage(userId, uri) }
                val message = ChatRepository.sendMessage(userId, trimmed.ifEmpty { null }, imageUrl)
                val current = _state.value as? ChatUiState.Loaded ?: return@launch
                if (current.messages.none { it.id == message.id }) {
                    _state.value = current.copy(messages = current.messages + message)
                }
                markRead()
                if (mentions.isNotEmpty()) {
                    val mentionedIds = if (mentions.contains("all")) {
                        listOf("all")
                    } else {
                        mentions.mapNotNull { name -> users.firstOrNull { it.displayName == name }?.id }
                    }
                    if (mentionedIds.isNotEmpty()) {
                        try {
                            ChatRepository.notifyMentions(userId, senderName, message.id, mentionedIds)
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (imageUri != null) {
                    val msg = if (e is RestException && e.statusCode == 413) {
                        "Photo is too large to send (max 12MB) - it was resized but is still over the limit."
                    } else {
                        "Couldn't send photo - check your connection and try again."
                    }
                    _imageSendError.tryEmit(msg)
                }
            }
        }
    }

    private suspend fun uploadChatImage(userId: String, uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        val originalBytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size) ?: return null
        val maxDim = 1080
        val smallSizeThreshold = 500 * 1024 // 500KB
        val alreadySmall = maxOf(bitmap.width, bitmap.height) <= maxDim &&
            originalBytes.size <= smallSizeThreshold
        val bytes: ByteArray
        val ext: String
        if (alreadySmall) {
            // Small, already-reasonable image (e.g. a meme, a small screenshot) - upload as-is.
            // Avoids a needless JPEG re-encode that would flatten PNG transparency and can
            // occasionally grow an already-compressed file.
            bytes = originalBytes
            ext = resolver.getType(uri)?.substringAfter("/") ?: "jpg"
        } else {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap
            bytes = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
            ext = "jpg"
        }
        return ChatRepository.uploadImage(userId, bytes, ext)
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
