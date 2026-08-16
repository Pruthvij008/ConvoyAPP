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
import com.convoy.mobile.dataModel.message.SendState
import com.convoy.mobile.repository.TripRepository
import com.convoy.mobile.repository.MessageRepository
import com.convoy.mobile.utility.PrefsManager
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import com.convoy.mobile.repository.MediaRepository
import com.convoy.mobile.utility.VoicePlayer
import com.convoy.mobile.utility.VoiceRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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
    private val tripRepository: TripRepository,
    private val mediaRepository: MediaRepository,
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

    /**
     * MY participant id for this trip.
     *
     * Messages carry a PARTICIPANT id, not a user id — the same person is a
     * different participant in every trip. Comparing against the user id
     * never matched, which is why every message looked like someone else's.
     */
    var myParticipantId by mutableStateOf<String?>(null)
        private set

    fun bind(tripId: String) {
        if (this.tripId == tripId) return
        this.tripId = tripId
        loadMyIdentity(tripId)
        loadHistory()
        loadQuickMessages()
        observeSocket()
    }

    private fun loadMyIdentity(tripId: String) {
        viewModelScope.launch {
            when (val r = tripRepository.getTrip(tripId)) {
                is NetworkResult.Success -> myParticipantId = r.data.me.id
                is NetworkResult.Error -> Log.w(TAG, "Identity fetch failed: ${r.message}")
                NetworkResult.Loading -> Unit
            }
        }
    }

    private fun observeSocket() {
        viewModelScope.launch {
            socketManager.events.collect { event ->
                if (event !is SocketEvent.MessageNew) return@collect

                val json = event.payload.optJSONObject("message") ?: return@collect
                val message = runCatching {
                    gson.fromJson(json.toString(), Message::class.java)
                }.getOrNull() ?: return@collect

                if (messages.any { it.id == message.id }) return@collect

                // Our own message echoes back over the socket, and it may
                // arrive BEFORE the REST call that created it returns. If it
                // does, it settles the optimistic copy rather than appearing
                // beside it as a duplicate.
                val mine = message.senderId != null && message.senderId == myParticipantId
                val pending = if (mine) {
                    messages.firstOrNull {
                        it.sendState == SendState.SENDING && it.body == message.body
                    }
                } else {
                    null
                }

                if (pending != null) {
                    replacePending(pending.id, message)
                } else {
                    messages = messages + message
                    if (!mine) unread += 1
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

        // Shown before the network is even touched. A driver watching an
        // empty screen after tapping send has no way to tell "it worked"
        // from "it did not", and will tap again.
        val pending = optimistic(kind = "TEXT", body = body)
        messages = messages + pending
        draft = ""

        viewModelScope.launch {
            isSending = true
            when (val r = repository.sendText(id, body, lat, lng)) {
                is NetworkResult.Success -> replacePending(pending.id, r.data)
                is NetworkResult.Error -> {
                    markFailed(pending.id)
                    errorMessage = r.message
                }
                NetworkResult.Loading -> Unit
            }
            isSending = false
        }
    }

    /**
     * A message that exists only on this phone until the server confirms it.
     *
     * The temporary id is prefixed so it can never collide with a real
     * ObjectId, and so the de-duplicator can recognise it.
     */
    private fun optimistic(
        kind: String,
        body: String? = null,
        quickKey: String? = null,
        severity: String = "INFO",
    ) = Message(
        id = "pending-" + System.nanoTime(),
        senderId = myParticipantId,
        senderName = prefs.displayName.orEmpty(),
        kind = kind,
        body = body,
        quickKey = quickKey,
        severity = severity,
        createdAt = null,
        sendStateOrNull = SendState.SENDING,
    )

    private fun replacePending(pendingId: String, confirmed: Message) {
        messages = messages.map { if (it.id == pendingId) confirmed else it }
            // The socket echoes our own message back, so the confirmed one
            // can already be here. Keeping both would show it twice.
            .distinctBy { it.id }
    }

    private fun markFailed(pendingId: String) {
        messages = messages.map {
            if (it.id == pendingId) it.copy(sendStateOrNull = SendState.FAILED) else it
        }
    }

    /** Sends a failed message again, in place. */
    fun retry(message: Message) {
        val id = tripId ?: return
        if (message.sendState != SendState.FAILED) return

        messages = messages.map {
            if (it.id == message.id) it.copy(sendStateOrNull = SendState.SENDING) else it
        }

        viewModelScope.launch {
            val result = if (message.isQuick && message.quickKey != null) {
                repository.sendQuick(id, message.quickKey)
            } else {
                repository.sendText(id, message.body.orEmpty())
            }
            when (result) {
                is NetworkResult.Success -> replacePending(message.id, result.data)
                is NetworkResult.Error -> markFailed(message.id)
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun sendQuick(quick: QuickMessage, lat: Double? = null, lng: Double? = null) {
        val id = tripId ?: return

        val pending = optimistic(
            kind = "QUICK",
            body = quick.label,
            quickKey = quick.key,
            severity = quick.severity,
        )
        messages = messages + pending

        viewModelScope.launch {
            isSending = true
            when (val r = repository.sendQuick(id, quick.key, lat, lng)) {
                is NetworkResult.Success -> replacePending(pending.id, r.data)
                is NetworkResult.Error -> {
                    markFailed(pending.id)
                    errorMessage = r.message
                }
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

    // ── Voice ───────────────────────────────────────────────────
    var isRecording by mutableStateOf(false)
        private set
    var recordingMs by mutableStateOf(0L)
        private set
    var isUploadingVoice by mutableStateOf(false)
        private set

    private var recorder: VoiceRecorder? = null
    private var tickJob: Job? = null

    fun startRecording(context: android.content.Context) {
        if (isRecording) return

        val rec = VoiceRecorder(context)
        if (!rec.start()) {
            // Distinguished, because the two causes need different actions
            // from the user and the old message blamed the wrong one.
            errorMessage = if (!rec.hasMicPermission()) {
                "Convoy needs permission to use the microphone."
            } else {
                "Couldn't start recording — another app may be using the microphone."
            }
            return
        }

        recorder = rec
        isRecording = true
        recordingMs = 0L

        tickJob = viewModelScope.launch {
            while (isRecording) {
                delay(100)
                recordingMs += 100
                // A button held down in a pocket must not record forever.
                if (recordingMs >= VoiceRecorder.MAX_DURATION_MS) {
                    stopRecordingAndSend()
                    break
                }
            }
        }
    }

    /** Released the button — send whatever was captured. */
    fun stopRecordingAndSend() {
        val id = tripId ?: return
        val rec = recorder ?: return

        isRecording = false
        tickJob?.cancel()
        recorder = null

        val clip = rec.stop()
        if (clip == null) {
            // Too short to be a message: a tap, not a hold. Said out loud
            // rather than silently dropped, so the user learns the gesture.
            errorMessage = "Hold the button to record."
            return
        }

        viewModelScope.launch {
            isUploadingVoice = true
            when (val up = mediaRepository.upload(id, clip.file, resourceType = "video")) {
                is NetworkResult.Success -> {
                    when (val sent = repository.sendVoice(id, up.data, clip.durationMs)) {
                        is NetworkResult.Success -> appendIfNew(sent.data)
                        is NetworkResult.Error -> errorMessage = sent.message
                        NetworkResult.Loading -> Unit
                    }
                }
                is NetworkResult.Error -> errorMessage = up.message
                NetworkResult.Loading -> Unit
            }
            // The local copy has served its purpose either way — it lives in
            // the cache directory and would otherwise accumulate.
            clip.file.delete()
            isUploadingVoice = false
        }
    }

    /** Slid away from the button — throw it away without sending. */
    /** The system prompt has been answered. */
    fun micPermissionResult(granted: Boolean) {
        errorMessage = if (granted) {
            null
        } else {
            "Without the microphone, voice notes can't be recorded."
        }
    }

    fun cancelRecording() {
        isRecording = false
        tickJob?.cancel()
        recorder?.cancel()
        recorder = null
        recordingMs = 0L
    }

    // ── Playback ────────────────────────────────────────────────
    private val player = VoicePlayer()

    var playingUrl by mutableStateOf<String?>(null)
        private set

    fun togglePlayback(url: String) {
        if (playingUrl == url) {
            player.stop()
            playingUrl = null
        } else {
            playingUrl = url
            player.play(url) { playingUrl = null }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // A clip still playing after the screen is gone would be a voice
        // from nowhere.
        player.stop()
        recorder?.cancel()
        tickJob?.cancel()
    }

    fun dismissError() { errorMessage = null }

    private companion object {
        const val TAG = "ChatViewModel"
    }
}
