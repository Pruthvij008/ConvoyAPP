package com.convoy.mobile.dataModel.common

import com.google.gson.annotations.SerializedName

/**
 * GeoJSON point, exactly as the backend stores it.
 *
 * The coordinate array is [longitude, latitude] — longitude FIRST. That is
 * the opposite order to how every map UI, GPS reading and human says it,
 * and getting it backwards fails silently: the point simply lands in the
 * ocean. Never read `coordinates[0]` directly; use [lat] and [lng].
 */
data class GeoPoint(

    @SerializedName("type")
    val type: String = "Point",

    @SerializedName("coordinates")
    val coordinates: List<Double> = emptyList(),
) {
    val lng: Double? get() = coordinates.getOrNull(0)
    val lat: Double? get() = coordinates.getOrNull(1)

    val isValid: Boolean get() = coordinates.size == 2

    companion object {
        /** Builds a point from the lat/lng order humans and Android use. */
        fun of(lat: Double, lng: Double) = GeoPoint(coordinates = listOf(lng, lat))
    }
}

/** Envelope every backend response shares. */
data class SimpleResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("message")
    val message: String? = null,
)
