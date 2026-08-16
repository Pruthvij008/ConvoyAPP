package com.convoy.mobile.dataModel.trip

import com.convoy.mobile.dataModel.common.GeoPoint
import com.convoy.mobile.dataModel.vehicle.Participant
import com.convoy.mobile.dataModel.vehicle.Vehicle
import com.google.gson.annotations.SerializedName

/**
 * A trip, and the lifecycle that governs when location may be shared.
 *
 * Location leaves the device only while [status] is ACTIVE. That is
 * enforced on the server, but the app mirrors the rule so it never even
 * starts the tracking service for a trip that is not live.
 */
data class Trip(

    @SerializedName("_id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("hostId")
    val hostId: String? = null,

    @SerializedName("joinCode")
    val joinCode: String? = null,

    @SerializedName("status")
    val status: String = TripStatus.LOBBY,

    @SerializedName("origin")
    val origin: GeoPoint? = null,

    @SerializedName("originAddress")
    val originAddress: String? = null,

    @SerializedName("destination")
    val destination: GeoPoint? = null,

    @SerializedName("destinationAddress")
    val destinationAddress: String? = null,

    @SerializedName("settings")
    val settings: TripSettings = TripSettings(),

    @SerializedName("markerSet")
    val markerSet: List<TripMarker> = emptyList(),

    // Fetched once by the server and shared with everyone, so N members
    // never mean N routing calls.
    @SerializedName("routeCache")
    val routeCache: RouteCache? = null,

    @SerializedName("counts")
    val counts: TripCounts = TripCounts(),

    @SerializedName("plannedStartAt")
    val plannedStartAt: String? = null,

    @SerializedName("startedAt")
    val startedAt: String? = null,

    @SerializedName("endedAt")
    val endedAt: String? = null,

    @SerializedName("lastActivityAt")
    val lastActivityAt: String? = null,

    // Only present on the "my trips" list.
    @SerializedName("myRole")
    val myRole: String? = null,

    @SerializedName("myStatus")
    val myStatus: String? = null,
) {
    /** The one state in which the tracking service may run. */
    val isLive: Boolean get() = status == TripStatus.ACTIVE

    val isFinished: Boolean
        get() = status == TripStatus.ENDED || status == TripStatus.ABANDONED

    val canJoin: Boolean
        get() = (status == TripStatus.LOBBY || status == TripStatus.ACTIVE) && !settings.isLocked

    val amHost: Boolean get() = myRole == ParticipantRole.HOST || myRole == ParticipantRole.CO_HOST

    /** The 3-4 big buttons a driver can hit without looking. */
    val favouriteMarkers: List<TripMarker>
        get() = markerSet.filter { it.isFavourite }.sortedBy { it.order }

    val markersByCategory: Map<String, List<TripMarker>>
        get() = markerSet.sortedBy { it.order }.groupBy { it.category }
}

object TripStatus {
    const val DRAFT = "DRAFT"
    const val LOBBY = "LOBBY"
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val ENDED = "ENDED"
    const val ABANDONED = "ABANDONED"
}

object ParticipantRole {
    const val HOST = "HOST"
    const val CO_HOST = "CO_HOST"
    const val MEMBER = "MEMBER"
}

object ConvoyRole {
    const val LEAD = "LEAD"
    const val SWEEP = "SWEEP"
}

data class TripSettings(

    @SerializedName("requireApproval")
    val requireApproval: Boolean = false,

    @SerializedName("isLocked")
    val isLocked: Boolean = false,

    // ADAPTIVE converts a time threshold into a distance using the convoy's
    // rolling speed, so the same setting behaves sensibly on a highway and
    // in city traffic.
    @SerializedName("gapMode")
    val gapMode: String = "ADAPTIVE",

    @SerializedName("gapAlertMinutes")
    val gapAlertMinutes: Int = 4,

    @SerializedName("gapAlertKm")
    val gapAlertKm: Double = 5.0,

    @SerializedName("offRouteToleranceM")
    val offRouteToleranceM: Int = 500,

    @SerializedName("stalledAfterMin")
    val stalledAfterMin: Int = 5,

    @SerializedName("signalLostSec")
    val signalLostSec: Int = 180,

    @SerializedName("lowBatteryPct")
    val lowBatteryPct: Int = 20,

    @SerializedName("alertsEnabled")
    val alertsEnabled: Boolean = true,

    @SerializedName("sosEnabled")
    val sosEnabled: Boolean = true,

    // Off by default on purpose: a false crash alert destroys trust in the
    // feature far faster than a missed one builds it.
    @SerializedName("crashDetectionEnabled")
    val crashDetectionEnabled: Boolean = false,

    @SerializedName("allowMemberWaypoints")
    val allowMemberWaypoints: Boolean = true,

    @SerializedName("requireWaypointApproval")
    val requireWaypointApproval: Boolean = true,

    @SerializedName("locationPrecision")
    val locationPrecision: String = "exact",

    /** Baseline cadence; the device still adapts around it for battery. */
    @SerializedName("pingIntervalSec")
    val pingIntervalSec: Int = 15,
)

/**
 * A marker in this trip's curated set. It carries BEHAVIOUR, not just
 * appearance — which is what lets a custom marker a friend invents mid-trip
 * behave exactly like a built-in one.
 */
data class TripMarker(

    @SerializedName("key")
    val key: String,

    @SerializedName("label")
    val label: String,

    @SerializedName("icon")
    val icon: String? = null,

    @SerializedName("color")
    val color: String? = null,

    @SerializedName("category")
    val category: String = "ADMIN",

    @SerializedName("order")
    val order: Int = 0,

    @SerializedName("isFavourite")
    val isFavourite: Boolean = false,

    /** INFO stays silent, WARN chimes, CRITICAL takes over the screen. */
    @SerializedName("severity")
    val severity: String = "INFO",

    /** Breakdown: true. Toilet: false. The driver can still override. */
    @SerializedName("defaultWaitingForGroup")
    val defaultWaitingForGroup: Boolean = false,

    /** "Other" and "Medical" demand a word — an unexplained stop is worse. */
    @SerializedName("requiresNote")
    val requiresNote: Boolean = false,

    @SerializedName("isCustom")
    val isCustom: Boolean = false,
)

data class TripCounts(

    @SerializedName("participants")
    val participants: Int = 0,

    @SerializedName("vehicles")
    val vehicles: Int = 0,

    @SerializedName("activeVehicles")
    val activeVehicles: Int = 0,
)

// ── Requests ─────────────────────────────────────────────────────

data class CreateTripRequest(

    @SerializedName("name")
    val name: String,

    @SerializedName("destinationAddress")
    val destinationAddress: String? = null,

    @SerializedName("destination")
    val destination: GeoPoint? = null,

    @SerializedName("plannedStartAt")
    val plannedStartAt: String? = null,

    @SerializedName("deviceId")
    val deviceId: String? = null,

    /** Creating a vehicle here makes the host its tracker. */
    @SerializedName("vehicle")
    val vehicle: VehicleInput? = null,

    /** DRAFT is for planning days ahead; the default opens immediately. */
    @SerializedName("asDraft")
    val asDraft: Boolean = false,
)

data class VehicleInput(

    @SerializedName("label")
    val label: String,

    @SerializedName("type")
    val type: String = "CAR",

    @SerializedName("plate")
    val plate: String? = null,

    @SerializedName("capacity")
    val capacity: Int? = null,
)

data class PreviewRequest(

    @SerializedName("token")
    val token: String? = null,

    @SerializedName("code")
    val code: String? = null,
)

data class JoinTripRequest(

    @SerializedName("token")
    val token: String? = null,

    @SerializedName("code")
    val code: String? = null,

    @SerializedName("password")
    val password: String? = null,

    @SerializedName("deviceId")
    val deviceId: String? = null,

    /** Ride with someone… */
    @SerializedName("vehicleId")
    val vehicleId: String? = null,

    /** …or bring your own car and become its tracker. */
    @SerializedName("vehicle")
    val vehicle: VehicleInput? = null,
)

data class StatusRequest(

    @SerializedName("status")
    val status: String,

    /** Starts anyway when someone is not in a vehicle yet. */
    @SerializedName("force")
    val force: Boolean = false,
)

data class ReadyRequest(
    @SerializedName("ready")
    val ready: Boolean,
)

// ── Responses ────────────────────────────────────────────────────

data class CreateTripResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: CreateTripData,
)

data class CreateTripData(

    @SerializedName("trip")
    val trip: Trip,

    @SerializedName("participant")
    val participant: Participant,

    @SerializedName("vehicle")
    val vehicle: Vehicle? = null,

    /**
     * Shown once and never retrievable again — only its hash is stored on
     * the server, so a database dump yields no working links. Rotate to get
     * a fresh one.
     */
    @SerializedName("joinLink")
    val joinLink: String,

    @SerializedName("joinCode")
    val joinCode: String,
)

data class TripListResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("results")
    val results: Int = 0,

    @SerializedName("data")
    val data: TripListData,
)

data class TripListData(
    @SerializedName("trips")
    val trips: List<Trip> = emptyList(),
)

data class TripDetailResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: TripDetailData,
)

data class TripDetailData(

    @SerializedName("trip")
    val trip: Trip,

    @SerializedName("me")
    val me: Participant,

    @SerializedName("participants")
    val participants: List<Participant> = emptyList(),

    @SerializedName("vehicles")
    val vehicles: List<Vehicle> = emptyList(),
)

/** What you see after tapping a shared link, BEFORE committing to join. */
data class PreviewResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: PreviewData,
)

data class PreviewData(
    @SerializedName("trip")
    val trip: TripPreview,
)

data class TripPreview(

    @SerializedName("_id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("status")
    val status: String,

    @SerializedName("destinationAddress")
    val destinationAddress: String? = null,

    @SerializedName("plannedStartAt")
    val plannedStartAt: String? = null,

    @SerializedName("memberCount")
    val memberCount: Int = 0,

    @SerializedName("vehicleCount")
    val vehicleCount: Int = 0,

    @SerializedName("requiresApproval")
    val requiresApproval: Boolean = false,

    @SerializedName("requiresPassword")
    val requiresPassword: Boolean = false,

    @SerializedName("isLocked")
    val isLocked: Boolean = false,

    @SerializedName("hostName")
    val hostName: String? = null,
) {
    // Deliberately carries no location: nothing about where anyone is
    // leaves the server until you are actually in the trip.
    val notOpenYet: Boolean get() = status == TripStatus.DRAFT
}

data class JoinTripResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("data")
    val data: JoinTripData,
)

data class JoinTripData(

    @SerializedName("tripId")
    val tripId: String,

    @SerializedName("participant")
    val participant: Participant,
)

data class TripResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: TripWrapper,
)

data class TripWrapper(
    @SerializedName("trip")
    val trip: Trip,
)

/** What the host stares at before pressing Start. */
data class LobbyResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: LobbyData,
)

data class LobbyData(

    @SerializedName("status")
    val status: String,

    @SerializedName("participants")
    val participants: List<Participant> = emptyList(),

    @SerializedName("vehicles")
    val vehicles: List<Vehicle> = emptyList(),

    @SerializedName("pendingRequests")
    val pendingRequests: Int = 0,

    @SerializedName("readyCount")
    val readyCount: Int = 0,

    @SerializedName("total")
    val total: Int = 0,

    @SerializedName("blockers")
    val blockers: LobbyBlockers = LobbyBlockers(),

    @SerializedName("canStart")
    val canStart: Boolean = false,
)

data class LobbyBlockers(

    @SerializedName("noVehicles")
    val noVehicles: Boolean = false,

    /**
     * Named, not counted. Someone not in a vehicle cannot be tracked and
     * would silently vanish from the convoy, so the warning says "Sneha"
     * rather than "1 participant has no vehicle".
     */
    @SerializedName("unassigned")
    val unassigned: List<String> = emptyList(),

    @SerializedName("notReady")
    val notReady: List<String> = emptyList(),
)

data class ReadyResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: ReadyData,
)

data class ReadyData(

    @SerializedName("participant")
    val participant: Participant,

    @SerializedName("ready")
    val ready: Int = 0,

    @SerializedName("total")
    val total: Int = 0,
)

data class InviteResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("data")
    val data: InviteData,
)

data class InviteData(

    @SerializedName("joinLink")
    val joinLink: String,

    @SerializedName("joinCode")
    val joinCode: String? = null,
)


/**
 * The trip's route, computed once server-side.
 *
 * [coordinates] arrives as GeoJSON [lng, lat] pairs — the order Mongo and
 * every geo tool uses, and the opposite of how people say it aloud. The
 * accessor below converts rather than leaving it to each caller to
 * remember, because getting it backwards puts the line in the ocean.
 */
data class RouteCache(

    @SerializedName("coordinates")
    val coordinates: List<List<Double>>? = null,

    @SerializedName("distanceM")
    val distanceM: Long? = null,

    @SerializedName("durationS")
    val durationS: Long? = null,

    /** "google" when traffic-aware, "osrm" when the free fallback answered. */
    @SerializedName("provider")
    val provider: String? = null,
) {
    /** [lat, lng] pairs, ready for the map. Malformed points are dropped. */
    val points: List<Pair<Double, Double>>
        get() = coordinates.orEmpty()
            .filter { it.size >= 2 }
            .map { it[1] to it[0] }

    val isTrafficAware: Boolean get() = provider == "google"
}
