package com.convoy.mobile.dataModel.message

import com.google.gson.annotations.SerializedName

/**
 * A message in the trip.
 *
 * [senderName] is a snapshot taken when the message was sent, never a live
 * reference — otherwise someone renaming themselves would rewrite every
 * line they had ever said.
 */
data class Message(

    @SerializedName("_id")
    val id: String,

    @SerializedName("tripId")
    val tripId: String? = null,

    @SerializedName("senderId")
    val senderId: String? = null,

    @SerializedName("senderName")
    val senderName: String,

    @SerializedName("kind")
    val kind: String = "TEXT",

    @SerializedName("body")
    val body: String? = null,

    /** Which canned phrase, when kind is QUICK. */
    @SerializedName("quickKey")
    val quickKey: String? = null,

    @SerializedName("severity")
    val severity: String = "INFO",

    @SerializedName("durationMs")
    val durationMs: Long? = null,

    @SerializedName("readBy")
    val readBy: List<ReadReceipt>? = null,

    @SerializedName("createdAt")
    val createdAt: String? = null,

    /** URL of the recorded clip, when kind is VOICE. */
    @SerializedName("mediaUrl")
    val mediaUrl: String? = null,

    /**
     * Local-only delivery state.
     *
     * A message is shown the instant it is written, before the server has
     * confirmed anything — a driver must never be left wondering whether
     * their "pulling over" went out. Not serialised: the server has no
     * opinion about a message that has not reached it yet.
     */
    @Transient
    val sendState: SendState = SendState.SENT,
) {
    val isQuick: Boolean get() = kind == "QUICK"
    val isVoice: Boolean get() = kind == "VOICE"
    val isSystem: Boolean get() = kind == "SYSTEM"
    val isCritical: Boolean get() = severity == "CRITICAL"
    val readCount: Int get() = readBy?.size ?: 0
}

data class ReadReceipt(

    @SerializedName("participantId")
    val participantId: String? = null,

    @SerializedName("at")
    val at: String? = null,
)

/**
 * A one-tap phrase.
 *
 * The only genuinely usable chat input while driving — a keyboard at
 * 90 km/h is a feature nobody touches.
 */
data class QuickMessage(

    @SerializedName("key")
    val key: String,

    @SerializedName("label")
    val label: String,

    @SerializedName("icon")
    val icon: String? = null,

    @SerializedName("severity")
    val severity: String = "INFO",
)

data class SendMessageRequest(

    @SerializedName("kind")
    val kind: String = "TEXT",

    @SerializedName("body")
    val body: String? = null,

    @SerializedName("quickKey")
    val quickKey: String? = null,

    @SerializedName("lat")
    val lat: Double? = null,

    @SerializedName("lng")
    val lng: Double? = null,
)

data class MarkReadRequest(
    @SerializedName("messageIds")
    val messageIds: List<String>,
)

data class MessageListResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("results")
    val results: Int = 0,

    @SerializedName("data")
    val data: MessageListData,
)

data class MessageListData(

    @SerializedName("messages")
    val messages: List<Message>? = null,

    /** Cursor for the next page — the oldest message in this batch. */
    @SerializedName("nextBefore")
    val nextBefore: String? = null,
)

data class MessageResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: MessageWrapper,
)

data class MessageWrapper(
    @SerializedName("message")
    val message: Message,
)

data class QuickMessageResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: QuickMessageData,
)

data class QuickMessageData(
    @SerializedName("quickMessages")
    val quickMessages: List<QuickMessage>? = null,
)


/** Where an outgoing message has got to. */
enum class SendState {
    /** Written locally, not yet acknowledged by the server. */
    SENDING,

    /** The server has it. Everyone else will get it.  */
    SENT,

    /** It did not go. Shown with a retry rather than silently dropped. */
    FAILED,
}
