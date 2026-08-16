package com.convoy.mobile.dataModel.place

import com.google.gson.annotations.SerializedName

/**
 * A searchable place.
 *
 * `name` is what the user recognises ("Anjuna Beach"); `description` is the
 * context that tells two identically named places apart ("Bardez, Goa").
 */
data class Place(
    @SerializedName("name") val name: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("lat") val lat: Double = 0.0,
    @SerializedName("lng") val lng: Double = 0.0,
    @SerializedName("kind") val kind: String? = null,
) {
    /** What goes in the destination field once a result is chosen. */
    val displayLabel: String
        get() = if (description.isNullOrBlank()) name else "$name, $description"
}

data class PlaceSearchResponse(
    @SerializedName("status") val status: String = "",
    @SerializedName("data") val data: PlaceSearchData = PlaceSearchData(),
)

data class PlaceSearchData(
    // Nullable because Gson never runs Kotlin constructors — an omitted
    // field stays null however the default is written, and a non-null type
    // here would blow up at the first use rather than at parse time.
    @SerializedName("results") val results: List<Place>? = null,
)

data class PlaceReverseResponse(
    @SerializedName("status") val status: String = "",
    @SerializedName("data") val data: PlaceReverseData = PlaceReverseData(),
)

data class PlaceReverseData(
    /** Null when the provider has no name for that spot — a field, a new road. */
    @SerializedName("place") val place: Place? = null,
)
