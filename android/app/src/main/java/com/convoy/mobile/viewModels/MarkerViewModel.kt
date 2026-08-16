package com.convoy.mobile.viewModels

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoy.mobile.dataModel.marker.Marker
import com.convoy.mobile.dataModel.marker.MarkerOption
import com.convoy.mobile.dataModel.trip.TripMarker
import com.convoy.mobile.network.NetworkResult
import com.convoy.mobile.repository.MarkerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private var tripId: String? = null

    var options by mutableStateOf<List<MarkerOption>>(emptyList())
        private set

    /** This vehicle's active stop, if it has one. */
    var activeStop by mutableStateOf<Marker?>(null)
        private set

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
        if (myVehicleId.isNullOrBlank()) return

        viewModelScope.launch {
            when (val r = repository.listActive(id)) {
                is NetworkResult.Success ->
                    activeStop = r.data.firstOrNull {
                        it.kind == "STATUS" && it.vehicleId == myVehicleId
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
        cancelCustom()
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

    fun onNoteChanged(value: String) { note = value.take(200) }

    /**
     * Picking a marker either saves immediately or asks for a note first —
     * decided by the marker's own `requiresNote`, not by its key.
     */
    fun choose(option: MarkerOption, lat: Double?, lng: Double?) {
        if (option.requiresNote && note.isBlank()) {
            pendingNoteFor = option
            return
        }
        save(option, lat, lng)
    }

    fun confirmNote(lat: Double?, lng: Double?) {
        val option = pendingNoteFor ?: return
        if (note.isBlank()) {
            errorMessage = "Add a few words so the group knows what's happening."
            return
        }
        save(option, lat, lng)
    }

    private fun save(option: MarkerOption, lat: Double?, lng: Double?) {
        val id = tripId ?: return

        viewModelScope.launch {
            isSaving = true
            errorMessage = null

            when (
                val r = repository.createStop(
                    tripId = id,
                    markerKey = option.key,
                    lat = lat,
                    lng = lng,
                    note = note.ifBlank { null },
                    waitingForGroup = option.defaultWaitingForGroup,
                )
            ) {
                is NetworkResult.Success -> {
                    activeStop = r.data
                    closePicker()
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
                is NetworkResult.Success -> activeStop = r.data
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
                is NetworkResult.Success -> activeStop = null
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
