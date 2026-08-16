package com.convoy.mobile.dataModel.alert

import com.convoy.mobile.dataModel.common.GeoPoint
import com.google.gson.annotations.SerializedName

/**
 * Something the convoy should know about.
 *
 * Severity decides how loudly it lands: INFO stays in the list, WARN shows
 * a banner, CRITICAL takes over the screen. That tiering is why a gap alert
 * and an SOS can share one model without a breakdown reading like a
 * low-battery notice.
 */
data class Alert(

    @SerializedName("_id")
    val id: String,

    @SerializedName("tripId")
    val tripId: String? = null,

    @SerializedName("vehicleId")
    val vehicleId: String? = null,

    @SerializedName("participantId")
    val participantId: String? = null,

    @SerializedName("type")
    val type: String,

    @SerializedName("severity")
    val severity: String = "WARN",

    /** Written by the server so the client never rebuilds the sentence. */
    @SerializedName("message")
    val message: String? = null,

    @SerializedName("location")
    val location: GeoPoint? = null,

    @SerializedName("state")
    val state: String = "OPEN",

    @SerializedName("acknowledgedBy")
    val acknowledgedBy: List<AlertAck>? = null,

    @SerializedName("raisedAt")
    val raisedAt: String? = null,

    @SerializedName("resolvedAt")
    val resolvedAt: String? = null,

    @SerializedName("updateCount")
    val updateCount: Int = 0,
) {
    val isLive: Boolean get() = state == "OPEN" || state == "ACKNOWLEDGED"
    val isCritical: Boolean get() = severity == "CRITICAL"
    val isSos: Boolean get() = type == "SOS" || type == "CRASH"

    /** Only SOS and crash refuse to clear themselves. */
    val needsHuman: Boolean get() = isSos

    val glyph: String
        get() = when (type) {
            "SOS", "CRASH" -> "🆘"
            "GAP" -> "⚠️"
            "OFF_ROUTE" -> "🧭"
            "STALLED" -> "⏱️"
            "SIGNAL_LOST" -> "📡"
            "LOW_BATTERY" -> "🔋"
            "SPEEDING" -> "💨"
            else -> "⚠️"
        }
}

data class AlertAck(

    @SerializedName("participantId")
    val participantId: String? = null,

    @SerializedName("at")
    val at: String? = null,
)

data class AlertListResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("results")
    val results: Int = 0,

    @SerializedName("data")
    val data: AlertListData,
)

data class AlertListData(

    @SerializedName("alerts")
    val alerts: List<Alert>? = null,

    @SerializedName("critical")
    val critical: Int = 0,
)

data class AlertResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: AlertWrapper,
)

data class AlertWrapper(
    @SerializedName("alert")
    val alert: Alert,
)

data class SosRequest(

    @SerializedName("type")
    val type: String = "SOS",

    @SerializedName("lat")
    val lat: Double? = null,

    @SerializedName("lng")
    val lng: Double? = null,

    @SerializedName("note")
    val note: String? = null,
)

data class SosResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: SosData,
)

data class SosData(

    @SerializedName("alert")
    val alert: Alert,

    /**
     * Minted automatically with the SOS — someone with a blown tyre should
     * not have to go hunting through settings for a link to send family.
     */
    @SerializedName("shareUrl")
    val shareUrl: String? = null,

    @SerializedName("expiresAt")
    val expiresAt: String? = null,
)

data class ResolveAlertRequest(

    @SerializedName("reason")
    val reason: String? = null,

    @SerializedName("cancelled")
    val cancelled: Boolean = false,
)
