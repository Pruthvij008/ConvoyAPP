package com.convoy.mobile.dataModel.auth

import com.google.gson.annotations.SerializedName

/**
 * Convoy's entire sign-in payload. No email, no password, no OTP — the
 * device key IS the identity, and the name is only so friends know which
 * dot is which.
 */
data class DeviceAuthRequest(

    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("platform")
    val platform: String = "android",

    @SerializedName("pushToken")
    val pushToken: String? = null,
)

/** Creating an account. No email, so nothing to verify afterwards. */
data class RegisterRequest(

    @SerializedName("username")
    val username: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("password")
    val password: String,

    @SerializedName("passwordConfirm")
    val passwordConfirm: String,

    /** Recorded so a device-level ban survives creating a new account. */
    @SerializedName("deviceId")
    val deviceId: String? = null,
)

data class LoginRequest(

    @SerializedName("username")
    val username: String,

    @SerializedName("password")
    val password: String,
)

data class DeviceAuthResponse(

    @SerializedName("status")
    val status: String,

    @SerializedName("token")
    val token: String,

    @SerializedName("data")
    val data: AuthData,
)

data class AuthData(
    @SerializedName("user")
    val user: User,
)

data class UserResponse(
    @SerializedName("status")
    val status: String = "",

    // Nullable because Gson allocates without running Kotlin constructors:
    // a response missing this field leaves it null whatever the declared
    // type says, and a non-null type would crash at the first read.
    @SerializedName("data")
    val data: AuthData? = null,
)

data class User(

    @SerializedName("_id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("username")
    val username: String? = null,

    @SerializedName("photo")
    val photo: String? = null,

    @SerializedName("phone")
    val phone: String? = null,

    @SerializedName("authProvider")
    val authProvider: String? = null,

    @SerializedName("savedVehicles")
    val savedVehicles: List<SavedVehicle> = emptyList(),

    @SerializedName("emergencyContacts")
    val emergencyContacts: List<EmergencyContact> = emptyList(),

    @SerializedName("preferences")
    val preferences: UserPreferences? = null,
) {
    /** True when this account has never been upgraded past the device key. */
    val isAnonymous: Boolean get() = authProvider == "device"

    /** SOS needs a number; nothing else in the app does. */
    val canUseSos: Boolean get() = !phone.isNullOrBlank() || emergencyContacts.isNotEmpty()
}

data class SavedVehicle(

    @SerializedName("label")
    val label: String,

    @SerializedName("type")
    val type: String = "CAR",

    @SerializedName("color")
    val color: String? = null,

    @SerializedName("plate")
    val plate: String? = null,
)

data class EmergencyContact(

    @SerializedName("name")
    val name: String,

    @SerializedName("phone")
    val phone: String,

    @SerializedName("relation")
    val relation: String? = null,
)

data class UserPreferences(

    @SerializedName("units")
    val units: String = "metric",

    @SerializedName("batteryMode")
    val batteryMode: String = "balanced",

    @SerializedName("language")
    val language: String = "en",

    @SerializedName("mapStyle")
    val mapStyle: String = "default",
)
