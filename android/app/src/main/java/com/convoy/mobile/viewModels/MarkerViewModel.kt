package com.convoy.mobile.viewModels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.dataModel.marker.Marker
import com.convoy.mobile.dataModel.marker.MarkerOption
import com.convoy.mobile.dataModel.media.UploadedMedia
import com.convoy.mobile.dataModel.trip.TripMarker
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.repository.MarkerRepository
import com.convoy.mobile.repository.MediaRepository
import com.convoy.mobile.utility.ImageFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The "why have you stopped?" flow.
 *
 * The picker is driven by the trip's curated marker set, so behaviour —
 * severity, wait-for-group, whether a note is required — comes from the
 * data rather than a hardcoded list of keys.
 */
@HiltViewModel
class MarkerViewModel @Inject constructor(
    private val repository: MarkerRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    private var tripId: String? = null

    var options by mutableStateOf<List<MarkerOption>>(emptyList())
        private set

    /**
     * Every vehicle's active stop, not just ours.
     *
     * Kept in full because a photo of someone else's breakdown is the whole
     * reason for attaching one — the convoy behind them needs to see it, and
     * the denormalized status on the vehicle carries only a label and an
     * icon.
     */
    var activeStops by mutableStateOf<List<Marker>>(emptyList())
        private set

    /** This vehicle's active stop, if it has one. */
    var activeStop by mutableStateOf<Marker?>(null)
        private set

    /** The active stop belonging to [vehicleId], for showing its photo. */
    fun stopFor(vehicleId: String?): Marker? =
        vehicleId?.let { id -> activeStops.firstOrNull { it.vehicleId == id } }

    var pickerOpen by mutableStateOf(false)
        private set

    /** The marker awaiting a note before it can be saved. */
    var pendingNoteFor by mutableStateOf<MarkerOption?>(null)
        private set
    var note by mutableStateOf("")
        private set

    var isSaving by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val favourites: List<MarkerOption>
        get() = options.filter { it.isFavourite }.sortedBy { it.order }

    /** Trouble markers get their own group — rare, but must be findable. */
    val trouble: List<MarkerOption>
        get() = options.filter { it.category == "TROUBLE" }.sortedBy { it.order }

    val others: List<MarkerOption>
        get() = options
            .filterNot { it.isFavourite || it.category == "TROUBLE" }
            .sortedBy { it.order }

    fun bind(tripId: String, markerSet: List<TripMarker>, myVehicleId: String?) {
        this.tripId = tripId
        options = markerSet.map { it.toOption() }
        refreshActiveStop(myVehicleId)
    }

    fun refreshActiveStop(myVehicleId: String?) {
        val id = tripId ?: return
        // Deliberately NOT gated on having a vehicle of our own. A passenger
        // riding in someone else's car has no vehicle id, and they are
        // exactly the person with a free pair of hands to look at why the
        // car ahead has stopped.

        viewModelScope.launch {
            when (val r = repository.listActive(id)) {
                is NetworkResult.Success -> {
                    activeStops = r.data.filter { it.kind == "STATUS" }
                    activeStop = activeStops.firstOrNull { it.vehicleId == myVehicleId }
                }
                is NetworkResult.Error -> Log.w(TAG, "Active stop fetch failed: ${r.message}")
                NetworkResult.Loading -> Unit
            }
        }
    }

    // ── Inventing a marker ──────────────────────────────────────
    // The built-in catalogue cannot cover what every group stops for. A
    // marker made here goes into this trip's set for everyone immediately,
    // and into this user's own library for next time.

    var creatingCustom by mutableStateOf(false)
        private set
    var customLabel by mutableStateOf("")
        private set
    var customIcon by mutableStateOf(CUSTOM_ICONS.first())
        private set

    fun openPicker() { pickerOpen = true; errorMessage = null }

    fun closePicker() {
        pickerOpen = false
        pendingNoteFor = null
        note = ""
        pendingPhoto = null
        cancelCustom()
    }

    /** Back out of the detail step without losing the picker underneath it. */
    fun cancelDetail() {
        pendingNoteFor = null
        note = ""
        pendingPhoto = null
        errorMessage = null
    }

    fun startCustom() {
        creatingCustom = true
        customLabel = ""
        customIcon = CUSTOM_ICONS.first()
        errorMessage = null
    }

    fun cancelCustom() {
        creatingCustom = false
        customLabel = ""
    }

    fun onCustomLabelChanged(value: String) { customLabel = value.take(24) }
    fun onCustomIconChanged(value: String) { customIcon = value }

    fun saveCustom() {
        val id = tripId ?: return
        val label = customLabel.trim()
        if (label.isBlank()) {
            errorMessage = "Give it a name so the group knows what it means."
            return
        }

        viewModelScope.launch {
            isSaving = true
            errorMessage = null

            when (val r = repository.createCustomMarker(id, label, customIcon, null)) {
                is NetworkResult.Success -> {
                    // The server returns the whole set, so the new marker
                    // appears in the picker without a refetch.
                    options = r.data.map { it.toOption() }
                    creatingCustom = false
                    customLabel = ""
                }
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
            isSaving = false
        }
    }

    // ── A photo of the stop ─────────────────────────────────────
    // "Flat tyre" and a photo of a shredded sidewall are two different
    // messages to the car behind deciding whether to turn back. The photo is
    // always optional — a stop that needs telling NOW must never wait on a
    // camera, so nothing here blocks saving.

    /** The picked photo, held locally until the stop is actually saved. */
    var pendingPhoto by mutableStateOf<Uri?>(null)
        private set

    /** True while the bytes are on their way to Cloudinary. */
    var isUploadingPhoto by mutableStateOf(false)
        private set

    fun attachPhoto(uri: Uri) {
        pendingPhoto = uri
        errorMessage = null
    }

    fun removePhoto() { pendingPhoto = null }

    /**
     * Shrinks, uploads and verifies the pending photo.
     *
     * Returns null when there is nothing to send OR when sending failed.
     * Failure is deliberately not fatal: the point of the marker is to tell
     * the convoy you have stopped, and refusing to do that because a photo
     * would not upload gets the priority exactly backwards. The stop goes
     * out either way and the user is told the photo did not.
     */
    private suspend fun uploadPendingPhoto(context: Context, tripId: String): List<UploadedMedia>? {
        val uri = pendingPhoto ?: return null

        isUploadingPhoto = true
        val file: File? = withContext(Dispatchers.IO) {
            ImageFiles.prepareForUpload(context, uri, prefix = "stop")
        }

        if (file == null) {
            isUploadingPhoto = false
            errorMessage = "Couldn't read that photo — the stop was sent without it."
            return null
        }

        try {
            return when (val r = mediaRepository.upload(tripId, file, resourceType = "image")) {
                is NetworkResult.Success -> listOf(r.data)
                is NetworkResult.Error -> {
                    errorMessage = "${r.message} The stop was sent without the photo."
                    null
                }
                NetworkResult.Loading -> null
            }
        } finally {
            // The shrunken copy is ours and has served its purpose. Left
            // behind it would accumulate in the cache, one per stop.
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            isUploadingPhoto = false
        }
    }

    fun onNoteChanged(value: String) { note = value.take(200) }

    /**
     * Picking a marker either saves immediately or asks for a note first —
     * decided by the marker's own `requiresNote`, not by its key.
     */
    fun choose(context: Context, option: MarkerOption, lat: Double?, lng: Double?) {
        // A photo is a reason to pause on the detail step even when the
        // marker itself needs no note — otherwise tapping "Breakdown" fires
        // the stop off instantly and there is never a moment to add one.
        if ((option.requiresNote && note.isBlank()) || pendingPhoto != null) {
            pendingNoteFor = option
            return
        }
        save(context, option, lat, lng)
    }

    /**
     * Opens the detail step for [option] without saving, so a photo or a
     * note can be added to a marker that would otherwise save on the tap.
     */
    fun addDetail(option: MarkerOption) {
        pendingNoteFor = option
        errorMessage = null
    }

    fun confirmNote(context: Context, lat: Double?, lng: Double?) {
        val option = pendingNoteFor ?: return
        if (option.requiresNote && note.isBlank()) {
            errorMessage = "Add a few words so the group knows what's happening."
            return
        }
        save(context, option, lat, lng)
    }

    private fun save(context: Context, option: MarkerOption, lat: Double?, lng: Double?) {
        val id = tripId ?: return

        viewModelScope.launch {
            isSaving = true
            errorMessage = null

            // The photo goes first, because the marker has to reference an
            // asset the server has already verified. A failure here leaves
            // `errorMessage` set and returns null, and the stop still goes.
            val media = uploadPendingPhoto(context, id)

            when (
                val r = repository.createStop(
                    tripId = id,
                    markerKey = option.key,
                    lat = lat,
                    lng = lng,
                    note = note.ifBlank { null },
                    waitingForGroup = option.defaultWaitingForGroup,
                    media = media,
                )
            ) {
                is NetworkResult.Success -> {
                    activeStop = r.data
                    activeStops = activeStops.filterNot { it.vehicleId == r.data.vehicleId } + r.data
                    // The photo's own failure message, if there was one, is
                    // kept — closePicker() must not silently swallow the
                    // only notice that the photo did not make it.
                    val photoProblem = errorMessage
                    closePicker()
                    errorMessage = photoProblem
                }
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
            isSaving = false
        }
    }

    /**
     * "Wait for me" versus "go ahead" — the toggle that actually decides
     * what the rest of the convoy does.
     */
    fun toggleWaiting() {
        val id = tripId ?: return
        val stop = activeStop ?: return

        viewModelScope.launch {
            when (val r = repository.setWaiting(id, stop.id, !stop.waitingForGroup)) {
                is NetworkResult.Success -> {
                    activeStop = r.data
                    activeStops = activeStops.map { if (it.id == r.data.id) r.data else it }
                }
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
        }
    }

    fun clearStop() {
        val id = tripId ?: return
        val stop = activeStop ?: return

        viewModelScope.launch {
            isSaving = true
            when (val r = repository.clear(id, stop.id)) {
                is NetworkResult.Success -> {
                    activeStop = null
                    activeStops = activeStops.filterNot { it.id == stop.id }
                }
                is NetworkResult.Error -> errorMessage = r.message
                NetworkResult.Loading -> Unit
            }
            isSaving = false
        }
    }

    fun dismissError() { errorMessage = null }

    private fun TripMarker.toOption() = MarkerOption(
        key = key,
        label = label,
        icon = icon,
        color = color,
        category = category,
        order = order,
        isFavourite = isFavourite,
        severity = severity,
        defaultWaitingForGroup = defaultWaitingForGroup,
        requiresNote = requiresNote,
        isCustom = isCustom,
    )

    companion object {
        private const val TAG = "MarkerViewModel"

        /**
         * A small palette rather than a full emoji keyboard.
         *
         * The system picker is a scrolling grid of thousands, which is a
         * terrible thing to hand someone naming a stop. These are the
         * shapes that plausibly mean "we stopped for this" and are not
         * already in the built-in catalogue.
         */
        val CUSTOM_ICONS = listOf(
            "📍", "🚻", "☕", "🍜", "🛕", "🏖️", "⛰️", "📷",
            "🛍️", "💊", "🐄", "🚬", "💧", "🔋", "🧊", "🎒",
        )
    }
}
