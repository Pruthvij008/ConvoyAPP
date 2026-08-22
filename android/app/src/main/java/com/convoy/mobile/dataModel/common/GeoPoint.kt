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
    val type: String? = "Point",

    /**
     * NULLABLE, and the default is a lie you must not rely on.
     *
     * Gson builds objects by unsafe allocation and never runs Kotlin
     * constructors, so `= emptyList()` is never executed for a field the
     * JSON omits — it stays null regardless. Declared non-null, every
     * accessor below would then throw NPE on a point the server sent
     * without coordinates, and it would throw deep inside the map draw
     * rather than anywhere near the response that caused it.
     *
     * Read it through [lat], [lng] and [isValid], which are null-safe.
     */
    @SerializedName("coordinates")
    val coordinates: List<Double>? = null,
) {
    val lng: Double? get() = coordinates?.getOrNull(0)
    val lat: Double? get() = coordinates?.getOrNull(1)

    val isValid: Boolean get() = coordinates?.size == 2

    /**
     * Both coordinates together, or null if either is missing.
     *
     * Exists to kill a pattern that was all over the app: check `lat != null
     * && lng != null`, then read them back with `!!`. Kotlin cannot smart-cast
     * `lat` and `lng` because they are computed properties, so the `!!` was
     * unavoidable — and it meant every position on the map was one malformed
     * point away from a crash, in code that LOOKED null-checked.
     *
     * Destructure it instead: `val (lat, lng) = point.latLng() ?: return`.
     */
    fun latLng(): Pair<Double, Double>? {
        val latitude = lat ?: return null
        val longitude = lng ?: return null
        return latitude to longitude
    }

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
