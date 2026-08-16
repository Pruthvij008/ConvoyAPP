package com.convoy.mobile.dataModel.marker

import com.convoy.mobile.dataModel.common.GeoPoint
import com.convoy.mobile.dataModel.trip.TripMarker
import com.google.gson.annotations.SerializedName

/**
 * A marker — something that happened, or something that is here.
 *
 * Every field that describes it is a SNAPSHOT copied at creation, not a
 * reference to a definition. Deleting or renaming the definition later
 * cannot change what a past stop said.
 */
data class Marker(

    @SerializedName("_id")
    val id: String,

    @SerializedName("tripId")
    val tripId: String? = null,

    /** Markers belong to VEHICLES — four friends in one car is one stop. */
    @SerializedName("vehicleId")
    val vehicleId: String? = null,

    @SerializedName("createdBy")
    val createdBy: String? = null,

    /** STATUS moves with a vehicle; PLACE is pinned to a coordinate. */
    @SerializedName("kind")
    val kind: String = "STATUS",

    @SerializedName("markerKey")
    val markerKey: String,

    @SerializedName("label")
    val label: String,

    @SerializedName("icon")
    val icon: String? = null,

    @SerializedName("color")
    val color: String? = null,

    @SerializedName("category")
    val category: String = "ADMIN",

    @SerializedName("severity")
    val severity: String = "INFO",

    @SerializedName("location")
    val location: GeoPoint? = null,

    @SerializedName("note")
    val note: String? = null,

    @SerializedName("state")
    val state: String = "ACTIVE",

    /** The decision that actually matters: pull over, or carry on? */
    @SerializedName("waitingForGroup")
    val waitingForGroup: Boolean = false,

    @SerializedName("autoDetected")
    val autoDetected: Boolean = false,

    @SerializedName("startedAt")
    val startedAt: String? = null,

    @SerializedName("endedAt")
    val endedAt: String? = null,

    @SerializedName("durationS")
    val durationS: Long? = null,
) {
    val isActive: Boolean get() = state == "ACTIVE"
    val isCritical: Boolean get() = severity == "CRITICAL"
}

/** An entry in the trip's curated set — what the picker draws. */
data class MarkerOption(

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

data class CreateMarkerRequest(

    @SerializedName("markerKey")
    val markerKey: String,

    @SerializedName("kind")
    val kind: String = "STATUS",

    @SerializedName("lat")
    val lat: Double? = null,

    @SerializedName("lng")
    val lng: Double? = null,

    @SerializedName("note")
    val note: String? = null,

    @SerializedName("waitingForGroup")
    val waitingForGroup: Boolean? = null,
)

data class UpdateMarkerRequest(

    @SerializedName("note")
    val note: String? = null,

    @SerializedName("waitingForGroup")
    val waitingForGroup: Boolean? = null,
)

data class MarkerListResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("results")
    val results: Int = 0,

    @SerializedName("data")
    val data: MarkerListData,
)

data class MarkerListData(
    @SerializedName("markers")
    val markers: List<Marker>? = null,
)

data class MarkerResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: MarkerWrapper,
)

data class MarkerWrapper(
    @SerializedName("marker")
    val marker: Marker,
)

// ── The trip's curated marker set ────────────────────────────────

/**
 * A marker the group invented themselves.
 *
 * Adding one puts it in this trip's set immediately AND in the user's own
 * library, so the crew that always stops for chai does not have to reinvent
 * a chai marker on every trip.
 */
data class AddMarkerSetRequest(

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

    @SerializedName("severity")
    val severity: String = "INFO",

    @SerializedName("isCustom")
    val isCustom: Boolean = true,
)

data class MarkerSetResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("data")
    val data: MarkerSetData,
)

data class MarkerSetData(
    // Nullable for the same reason every list in this app is: Gson skips
    // Kotlin constructors entirely, so a missing field stays null however
    // the default is declared.
    @SerializedName("markerSet")
    val markerSet: List<TripMarker>? = null,
)
