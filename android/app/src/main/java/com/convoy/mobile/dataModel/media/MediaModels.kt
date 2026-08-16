package com.convoy.mobile.dataModel.media

import com.google.gson.annotations.SerializedName

/**
 * A signed permission to upload one file, straight to Cloudinary.
 *
 * The bytes never pass through our server — which matters on a patchy
 * highway connection, where an extra hop is an extra thing to fail.
 */
data class UploadSignature(
    @SerializedName("signature") val signature: String = "",
    @SerializedName("timestamp") val timestamp: Long = 0L,
    @SerializedName("folder") val folder: String = "",
    @SerializedName("apiKey") val apiKey: String = "",
    @SerializedName("cloudName") val cloudName: String = "",
    @SerializedName("resourceType") val resourceType: String = "image",
    @SerializedName("uploadUrl") val uploadUrl: String = "",
    @SerializedName("maxBytes") val maxBytes: Long = 0L,
)

data class SignatureResponse(
    @SerializedName("status") val status: String = "",
    @SerializedName("data") val data: UploadSignature = UploadSignature(),
)

/** What Cloudinary hands back, and what we send to be verified. */
data class UploadedMedia(
    @SerializedName("publicId") val publicId: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("resourceType") val resourceType: String? = null,
    @SerializedName("format") val format: String? = null,
    @SerializedName("bytes") val bytes: Long? = null,
    @SerializedName("durationS") val durationS: Double? = null,
)

data class ConfirmRequest(
    @SerializedName("publicId") val publicId: String,
    @SerializedName("resourceType") val resourceType: String = "image",
)

data class ConfirmResponse(
    @SerializedName("status") val status: String = "",
    @SerializedName("data") val data: ConfirmData = ConfirmData(),
)

data class ConfirmData(
    @SerializedName("media") val media: UploadedMedia? = null,
)

data class SignatureRequest(
    @SerializedName("resourceType") val resourceType: String = "image",
)
