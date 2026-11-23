package com.sap.codelab.view.create

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sap.codelab.domain.model.Location
import com.sap.codelab.domain.model.LocationPermission
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.usecase.CreateMemoUseCase
import com.sap.codelab.domain.usecase.CreateMemoUseCase.Outcome
import com.sap.codelab.domain.usecase.CreateMemoUseCase.ReminderRequest
import com.sap.codelab.utils.extensions.empty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for matching CreateMemo view. Handles user interactions.
 */
internal class CreateMemoViewModel(
    private val savedState: SavedStateHandle,
    private val createMemo: CreateMemoUseCase,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : ViewModel() {

    enum class SaveState {
        READY, BACKGROUND_RATIONALE, REQUESTING_FOREGROUND, REQUESTING_BACKGROUND,
        OPENING_SETTINGS, SAVING, SAVED, SAVED_WITH_REMINDER, SAVED_WITHOUT_REMINDER, FAILED
    }

    private val _saveState = MutableStateFlow(
        savedState.get<String>(PERMISSION_STATE_KEY)?.let(SaveState::valueOf) ?: SaveState.READY
    )
    val saveState = _saveState.asStateFlow()

    val selectedLocation: Location?
        get() = savedState.get<DoubleArray>(LOCATION_KEY)?.let { Location(it[0], it[1]) }

    fun selectLocation(location: Location) {
        savedState[LOCATION_KEY] = doubleArrayOf(location.lat, location.lon)
    }

    private var memo = Memo(
        id = 0,
        title = String.empty(),
        description = String.empty(),
        reminderDate = 0,
        reminderLatitude = 0,
        reminderLongitude = 0,
        createdAt = 0
    )

    /**
     * Saves the memo in it's current state.
     */
    fun saveMemo(permissions: LocationPermission) {
        if (_saveState.value != SaveState.READY) return
        savedState[DRAFT_KEY] = Bundle().apply {
            putString("title", memo.title)
            putString("description", memo.description)
            putInt("priority", memo.priority)
            putLong("createdAt", memo.createdAt)
            putLong("latitude", memo.reminderLatitude)
            putLong("longitude", memo.reminderLongitude)
        }
        when {
            selectedLocation == null -> persistMemo(ReminderRequest.NONE)
            permissions.canMonitor -> persistMemo(ReminderRequest.REGISTER)
            sdkInt >= Build.VERSION_CODES.R && !permissions.isBackgroundLocationEnabled ->
                awaitPermission(SaveState.BACKGROUND_RATIONALE)
            sdkInt == Build.VERSION_CODES.Q && !permissions.isPreciseLocationEnabled ->
                awaitPermission(SaveState.REQUESTING_FOREGROUND)
            sdkInt == Build.VERSION_CODES.Q -> awaitPermission(SaveState.REQUESTING_BACKGROUND)
            else -> persistMemo(ReminderRequest.SKIP)
        }
    }

    fun onBackgroundRationaleResult(openSettings: Boolean) {
        if (_saveState.value != SaveState.BACKGROUND_RATIONALE) return
        if (openSettings) {
            awaitPermission(SaveState.OPENING_SETTINGS)
        } else {
            persistMemo(ReminderRequest.SKIP)
        }
    }

    fun claimPermissionLaunch(state: SaveState): Boolean {
        if (_saveState.value != state || savedState.get<Boolean>(LAUNCH_STARTED_KEY) == true) return false
        savedState[LAUNCH_STARTED_KEY] = true
        return true
    }

    fun onPermissionResult(state: SaveState, permissions: LocationPermission?) {
        if (_saveState.value != state || savedState.get<Boolean>(LAUNCH_STARTED_KEY) != true) return
        when {
            permissions?.canMonitor == true -> persistMemo(ReminderRequest.REGISTER)
            state == SaveState.OPENING_SETTINGS && permissions != null ->
                awaitPermission(SaveState.BACKGROUND_RATIONALE)
            state == SaveState.REQUESTING_FOREGROUND && permissions?.isPreciseLocationEnabled == true ->
                awaitPermission(SaveState.REQUESTING_BACKGROUND)
            else -> persistMemo(ReminderRequest.SKIP)
        }
    }

    private val LocationPermission.canMonitor: Boolean
        get() = isPreciseLocationEnabled && isBackgroundLocationEnabled

    private fun awaitPermission(state: SaveState) {
        savedState[PERMISSION_STATE_KEY] = state.name
        savedState[LAUNCH_STARTED_KEY] = false
        _saveState.value = state
    }

    private fun persistMemo(reminder: ReminderRequest) {
        val draft = checkNotNull(savedState.remove<Bundle>(DRAFT_KEY))
        savedState.remove<String>(PERMISSION_STATE_KEY)
        savedState.remove<Boolean>(LAUNCH_STARTED_KEY)
        val memoToSave = Memo(
            id = 0,
            title = draft.getString("title").orEmpty(),
            description = draft.getString("description").orEmpty(),
            priority = draft.getInt("priority"),
            reminderDate = 0,
            reminderLatitude = draft.getLong("latitude"),
            reminderLongitude = draft.getLong("longitude"),
            createdAt = draft.getLong("createdAt")
        )
        _saveState.value = SaveState.SAVING
        viewModelScope.launch {
            try {
                _saveState.value = when (val outcome = createMemo(memoToSave, reminder)) {
                    Outcome.Saved -> SaveState.SAVED
                    Outcome.MonitoringActive -> SaveState.SAVED_WITH_REMINDER
                    Outcome.MonitoringSkipped -> SaveState.SAVED_WITHOUT_REMINDER
                    is Outcome.RegistrationFailed -> {
                        Log.w("CreateMemo", "Memo saved without an active location reminder", outcome.error)
                        SaveState.SAVED_WITHOUT_REMINDER
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e("CreateMemo", "Could not save memo", error)
                _saveState.value = SaveState.FAILED
            }
        }
    }

    fun acknowledgeSaveFailure() {
        _saveState.value = SaveState.READY
    }

    /**
     * Call this method to update the memo. This is usually needed when the user changed his input.
     */
    fun updateMemo(title: String, description: String, priority: Int) {
        val location = selectedLocation
        memo = memo.copy(
            title = title,
            description = description,
            priority = priority,
            reminderLatitude = location?.latitudeE7 ?: 0,
            reminderLongitude = location?.longitudeE7 ?: 0,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * @return true if the title and content are not blank; false otherwise.
     */
    fun isMemoValid(): Boolean = memo.title.isNotBlank() && memo.description.isNotBlank()

    /**
     * @return true if the memo text is blank, false otherwise.
     */
    fun hasTextError() = memo.description.isBlank()

    /**
     * @return true if the memo title is blank, false otherwise.
     */
    fun hasTitleError() = memo.title.isBlank()

    private companion object {
        const val LOCATION_KEY = "selectedMemoLocation"
        const val DRAFT_KEY = "pendingMemoDraft"
        const val PERMISSION_STATE_KEY = "pendingMemoPermissionState"
        const val LAUNCH_STARTED_KEY = "pendingMemoPermissionLaunchStarted"
    }
}
