package com.convoy.mobile.dataModel.vehicle

import com.convoy.mobile.dataModel.common.GeoPoint
import com.convoy.mobile.utility.Constants
import com.google.gson.annotations.SerializedName

/**
 * A vehicle — THE UNIT THAT APPEARS ON THE MAP.
 *
 * Four friends in one car is one dot, not four. Location is emitted by a
 * single designated device per vehicle ([trackerParticipantId]); passengers
 * see the map and drop markers but never broadcast.
 */
data class Vehicle(

    @SerializedName("_id")
    val id: String,

    @SerializedName("tripId")
    val tripId: String? = null,

    @SerializedName("label")
    val label: String,

    @SerializedName("type")
    val type: String = "CAR",

    /** Assigned from a fixed palette so no two cars in a convoy match. */
    @SerializedName("color")
    val color: String? = null,

    @SerializedName("plate")
    val plate: String? = null,

    @SerializedName("capacity")
    val capacity: Int? = null,

    @SerializedName("trackerParticipantId")
    val trackerParticipantId: String? = null,

    @SerializedName("lastKnown")
    val lastKnown: LastKnown? = null,

    @SerializedName("currentStatus")
    val currentStatus: VehicleStatus? = null,

    @SerializedName("connectionState")
    val connectionState: String = ConnectionState.LOST,

    @SerializedName("lastFixAgeSec")
    val lastFixAgeSec: Int? = null,

    @SerializedName("totalDistanceM")
    val totalDistanceM: Double = 0.0,

    @SerializedName("movingTimeS")
    val movingTimeS: Long = 0,

    @SerializedName("stoppedTimeS")
    val stoppedTimeS: Long = 0,

    @SerializedName("maxSpeedKmh")
    val maxSpeedKmh: Double = 0.0,

    /**
     * Only present on the vehicle list endpoint.
     *
     * NULLABLE on purpose. Gson builds objects by unsafe allocation and never
     * runs Kotlin constructors, so a field the JSON omits stays null whatever
     * its declared default says — and a non-null declaration then makes
     * `copy()` throw at runtime. Read it through [people].
     */
    @SerializedName("occupants")
    val occupants: List<Participant>? = null,
) {
    /** Null-safe view of [occupants]; always use this. */
    val people: List<Participant> get() = occupants.orEmpty()

    /**
     * A frozen dot must never be drawn as a live one. "They stopped" and
     * "we lost signal" look identical otherwise, and on a mountain road
     * that difference decides whether you turn the car around.
     */
    val freshness: Freshness
        get() = when {
            lastFixAgeSec == null -> Freshness.LOST
            lastFixAgeSec <= Constants.STALE_AFTER_SEC -> Freshness.LIVE
            lastFixAgeSec <= Constants.LOST_AFTER_SEC -> Freshness.STALE
            else -> Freshness.LOST
        }

    val isStopped: Boolean
        get() = (lastKnown?.speedKmh ?: 0.0) <= Constants.STOPPED_SPEED_KMH

    val hasActiveStop: Boolean get() = currentStatus?.markerKey != null

    val position: GeoPoint? get() = lastKnown?.point
}

enum class Freshness { LIVE, STALE, LOST }

object ConnectionState {
    const val LIVE = "LIVE"
    const val STALE = "STALE"
    const val LOST = "LOST"
    const val PAUSED = "PAUSED"
    const val ENDED = "ENDED"
}

data class LastKnown(

    @SerializedName("point")
    val point: GeoPoint? = null,

    @SerializedName("heading")
    val heading: Double? = null,

    @SerializedName("speedKmh")
    val speedKmh: Double? = null,

    @SerializedName("accuracyM")
    val accuracyM: Double? = null,

    /** On the roster because you need to know who is about to go dark. */
    @SerializedName("batteryPct")
    val batteryPct: Int? = null,

    @SerializedName("at")
    val at: String? = null,
)

/** The vehicle's active stop, denormalized so the roster renders in one query. */
data class VehicleStatus(

    @SerializedName("markerId")
    val markerId: String? = null,

    @SerializedName("markerKey")
    val markerKey: String? = null,

    @SerializedName("label")
    val label: String? = null,

    @SerializedName("icon")
    val icon: String? = null,

    @SerializedName("since")
    val since: String? = null,

    /** Tells the convoy whether to pull over or carry on. */
    @SerializedName("waitingForGroup")
    val waitingForGroup: Boolean = false,
)

/**
 * A person's membership in one trip.
 *
 * Permission level ([role]) and convoy position ([convoyRole]) are separate
 * fields on purpose — the host can also be the lead vehicle, which one
 * combined enum could not express.
 */
data class Participant(

    @SerializedName("_id")
    val id: String,

    @SerializedName("tripId")
    val tripId: String? = null,

    @SerializedName("userId")
    val userId: String? = null,

    @SerializedName("vehicleId")
    val vehicleId: String? = null,

    @SerializedName("role")
    val role: String = "MEMBER",

    @SerializedName("convoyRole")
    val convoyRole: String? = null,

    /** Only the tracker broadcasts; passengers ride along. */
    @SerializedName("isDriver")
    val isDriver: Boolean = false,

    /**
     * A snapshot taken at join time, never a live reference. With no
     * sign-in people rename themselves freely, and a live reference would
     * rewrite their name across every past trip.
     */
    @SerializedName("displayName")
    val displayName: String,

    @SerializedName("photo")
    val photo: String? = null,

    @SerializedName("status")
    val status: String = "JOINED",

    @SerializedName("sharingState")
    val sharingState: String = "OFFLINE",

    @SerializedName("isReady")
    val isReady: Boolean = false,

    @SerializedName("joinedVia")
    val joinedVia: String? = null,

    @SerializedName("joinedAt")
    val joinedAt: String? = null,
) {
    val isHost: Boolean get() = role == "HOST"
    val canManageTrip: Boolean get() = role == "HOST" || role == "CO_HOST"
    val isPending: Boolean get() = status == "PENDING"
    val isSharing: Boolean get() = sharingState == "SHARING"
    val isPaused: Boolean get() = sharingState == "PAUSED"
    val hasVehicle: Boolean get() = !vehicleId.isNullOrBlank()
}

// ── Responses ────────────────────────────────────────────────────

data class VehicleListResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("results")
    val results: Int = 0,

    @SerializedName("data")
    val data: VehicleListData,
)

data class VehicleListData(
    @SerializedName("vehicles")
    val vehicles: List<Vehicle>? = null,
)

data class VehicleResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: VehicleWrapper,
)

data class VehicleWrapper(

    @SerializedName("vehicle")
    val vehicle: Vehicle,

    @SerializedName("participant")
    val participant: Participant? = null,
)

data class ParticipantResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: ParticipantWrapper,
)

data class ParticipantWrapper(
    @SerializedName("participant")
    val participant: Participant,
)
