package com.convoy.mobile.viewModels

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.dataModel.message.Message
import com.convoy.mobile.dataModel.message.QuickMessage
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.SocketEvent
import com.convoy.mobile.network.SocketManager
import com.convoy.mobile.repository.MessageRepository
import com.convoy.mobile.utility.PrefsManager
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Trip chat.
 *
 * Incoming messages arrive over the socket; sending goes over REST so a
 * message that matters is durably written before anyone is told about it.
 * A socket emit is fire-and-forget, which is fine for a position that will
 * be superseded in fifteen seconds and wrong for "I've broken down".
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val socketManager: SocketManager,
    private val prefs: PrefsManager,
    private val gson: Gson,
) : ViewModel() {

    private var tripId: String? = null
    private var nextBefore: String? = null

    /** Oldest first, so the list appends naturally at the bottom. */
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set

    var quickMessages by mutableStateOf<List<QuickMessage>>(emptyList())
        private set

    var draft by mutableStateOf("")
        private set

    var isLoading by mutableStateOf(false)
        private set
    var isSending by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Unread count for the badge on the map screen. */
    var unread by mutableStateOf(0)
        private set

    val myParticipantId: String? get() = prefs.userId

    fun bind(tripId: String) {
        if (this.tripId == tripId) return
        this.tripId = tripId
        loadHistory()
        loadQuickMessages()
        observeSocket()
    }

    private fun observeSocket() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                if (event !is SocketEvent.MessageNew) return@collect

                val json = event.payload.optJSONObject("message") ?: return@collect
                val message = runCatching {
                    gson.fromJson(json.toString(), Message::class.java)
                }.getOrNull() ?: return@collect

                // The sender receives its own message back too, so the list
                // is de-duplicated by id rather than trusting arrival order.
                if (messages.none { it.id == message.id }) {
                    messages = messages + message
                    unread += 1
                }
            }
        }
    }

    fun loadHistory() {
        val id = tripId ?: return
        viewModelScope.launch {
            isLoading = true
            when (val r = repository.history(id)) {
                is NetworkResult.Success -> {
                    messages = r.data.first
                    nextBefore = r.data.second
                }
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
            isLoading = false
        }
    }

    /** Cursor paging, so scrolling back never re-fetches the same page. */
    fun loadOlder() {
        val id = tripId ?: return
        val cursor = nextBefore ?: return
        viewModelScope.launch {
            when (val r = repository.history(id, before = cursor)) {
                is NetworkResult.Success -> {
                    messages = r.data.first + messages
                    nextBefore = r.data.second
                }
                is NetworkResult.Error -> Log.w(TAG, "Older page failed: ${r.message}")
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun loadQuickMessages() {
        viewModelScope.launch {
            when (val r = repository.quickMessages()) {
                is NetworkResult.Success -> quickMessages = r.data
                is NetworkResult.Error -> Log.w(TAG, "Quick messages failed: ${r.message}")
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun onDraftChanged(value: String) { draft = value.take(1000) }

    fun sendDraft(lat: Double? = null, lng: Double? = null) {
        val id = tripId ?: return
        val body = draft.trim()
        if (body.isEmpty()) return

        viewModelScope.launch {
            isSending = true
            // Cleared immediately: a driver should never be left wondering
            // whether their message went, and a failure is reported below.
            draft = ""
            when (val r = repository.sendText(id, body, lat, lng)) {
                is NetworkResult.Success -> appendIfNew(r.data)
                is NetworkResult.Error -> {
                    errorMessage = r.message
                    draft = body // hand it back so nothing is lost
                }
                NetworkResult.Loading -> Unit
            }
            isSending = false
        }
    }

    fun sendQuick(quick: QuickMessage, lat: Double? = null, lng: Double? = null) {
        val id = tripId ?: return
        viewModelScope.launch {
            isSending = true
            when (val r = repository.sendQuick(id, quick.key, lat, lng)) {
                is NetworkResult.Success -> appendIfNew(r.data)
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
            isSending = false
        }
    }

    private fun appendIfNew(message: Message) {
        if (messages.none { it.id == message.id }) messages = messages + message
    }

    /** Called when the chat screen is open — the sender wants to know. */
    fun markAllRead() {
        val id = tripId ?: return
        unread = 0
        val ids = messages.takeLast(30).map { it.id }
        viewModelScope.launch { repository.markRead(id, ids) }
    }

    fun dismissError() { errorMessage = null }

    private companion object {
        const val TAG = "ChatViewModel"
    }
}
