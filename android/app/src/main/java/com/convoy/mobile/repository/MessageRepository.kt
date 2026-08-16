package com.convoy.mobile.repository

import android.util.Log
import com.convoy.mobile.dataModel.message.MarkReadRequest
import com.convoy.mobile.dataModel.media.UploadedMedia
import com.convoy.mobile.dataModel.message.Message
import com.convoy.mobile.dataModel.message.QuickMessage
import com.convoy.mobile.dataModel.message.SendMessageRequest
import com.convoy.mobile.interfaces.MessageInterface
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.network.safeApiCall
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageApi: MessageInterface,
) {

    suspend fun history(
        tripId: String,
        before: String? = null,
    ): NetworkResult<Pair<List<Message>, String?>> {
        return when (val r = safeApiCall { messageApi.listMessages(tripId, before) }) {
            is NetworkResult.Success ->
                NetworkResult.Success(
                    r.data.data.messages.orEmpty() to r.data.data.nextBefore
                )
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun sendText(
        tripId: String,
        body: String,
        lat: Double? = null,
        lng: Double? = null,
    ): NetworkResult<Message> {
        val request = SendMessageRequest(kind = "TEXT", body = body.trim(), lat = lat, lng = lng)
        return send(tripId, request)
    }

    /** One tap, no keyboard — the only input that works at speed. */
    suspend fun sendQuick(
        tripId: String,
        quickKey: String,
        lat: Double? = null,
        lng: Double? = null,
    ): NetworkResult<Message> {
        Log.d(TAG, "Quick message: $quickKey")
        val request = SendMessageRequest(kind = "QUICK", quickKey = quickKey, lat = lat, lng = lng)
        return send(tripId, request)
    }

    private suspend fun send(
        tripId: String,
        request: SendMessageRequest,
    ): NetworkResult<Message> {
        return when (val r = safeApiCall { messageApi.sendMessage(tripId, request) }) {
            is NetworkResult.Success -> NetworkResult.Success(r.data.data.message)
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun markRead(tripId: String, ids: List<String>): NetworkResult<Unit> {
        if (ids.isEmpty()) return NetworkResult.Success(Unit)
        return when (safeApiCall { messageApi.markRead(tripId, MarkReadRequest(ids)) }) {
            is NetworkResult.Success -> NetworkResult.Success(Unit)
            is NetworkResult.Error -> NetworkResult.Success(Unit) // receipts are best-effort
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    suspend fun quickMessages(): NetworkResult<List<QuickMessage>> {
        return when (val r = safeApiCall { messageApi.getQuickMessages() }) {
            is NetworkResult.Success ->
                NetworkResult.Success(r.data.data.quickMessages.orEmpty())
            is NetworkResult.Error -> r
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Sends a voice note whose clip has already been uploaded and verified.
     *
     * The media object comes from the confirm step rather than from the
     * client's own report of what it uploaded — that is the whole point of
     * confirming.
     */
    suspend fun sendVoice(
        tripId: String,
        media: UploadedMedia,
        durationMs: Long,
    ): NetworkResult<Message> {
        Log.d(TAG, "Voice note: ${durationMs}ms")
        val request = SendMessageRequest(
            kind = "VOICE",
            media = media,
            durationMs = durationMs,
        )
        return send(tripId, request)
    }

    private companion object {
        const val TAG = "MessageRepository"
    }
}
