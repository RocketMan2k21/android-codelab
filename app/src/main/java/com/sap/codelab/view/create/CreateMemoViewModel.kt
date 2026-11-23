package com.sap.codelab.view.create

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sap.codelab.domain.model.Location
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.model.NotificationAccess
import com.sap.codelab.domain.model.ReminderReadiness
import com.sap.codelab.domain.usecase.CreateMemoUseCase
import com.sap.codelab.domain.usecase.CreateMemoUseCase.Outcome
import com.sap.codelab.domain.usecase.CreateMemoUseCase.ReminderRequest
import com.sap.codelab.utils.extensions.empty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coordinates memo creation and the prerequisites for activating its location reminder. */
internal class CreateMemoViewModel(
    private val savedState: SavedStateHandle,
    private val createMemo: CreateMemoUseCase,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : ViewModel() {

    enum class SaveState {
        READY, FOREGROUND_RATIONALE, REQUESTING_FOREGROUND, BACKGROUND_RATIONALE,
        REQUESTING_BACKGROUND, OPENING_SETTINGS, LOCATION_SERVICES_RATIONALE, OPENING_LOCATION_SERVICES,
        NOTIFICATION_PERMISSION_RATIONALE, APP_NOTIFICATIONS_RATIONALE, CHANNEL_NOTIFICATIONS_RATIONALE,
        REQUESTING_NOTIFICATIONS, OPENING_APP_NOTIFICATIONS, OPENING_CHANNEL_NOTIFICATIONS,
        SAVING, SAVED, SAVED_WITH_REMINDER, SAVED_WITHOUT_REMINDER, FAILED
    }

    enum class RationaleAction { CONTINUE, OPEN_SETTINGS, SKIP }

    private val _saveState = MutableStateFlow(
        savedState.get<String>(PERMISSION_STATE_KEY)?.let(SaveState::valueOf) ?: SaveState.READY
    )
    val saveState = _saveState.asStateFlow()

    val selectedLocation: Location?
        get() = savedState.get<DoubleArray>(LOCATION_KEY)?.let { Location(it[0], it[1]) }

    /** Stores the selected map point so it survives Activity recreation. */
    fun selectLocation(location: Location) {
        savedState[LOCATION_KEY] = doubleArrayOf(location.lat, location.lon)
    }

    private var memo = Memo(
        id = 0,
        title = String.empty(),
        description = String.empty(),
        reminderDate = 0,
        reminderLatitude = 0.0,
        reminderLongitude = 0.0,
        createdAt = 0
    )

    /** Captures the draft for this save attempt and starts the reminder checks if it has a location. */
    fun saveMemo(readiness: ReminderReadiness) {
        if (_saveState.value != SaveState.READY) return
        savedState[DRAFT_KEY] = Bundle().apply {
            putString("title", memo.title)
            putString("description", memo.description)
            putInt("priority", memo.priority)
            putLong("createdAt", memo.createdAt)
            putDouble("latitude", memo.reminderLatitude)
            putDouble("longitude", memo.reminderLongitude)
        }
        if (selectedLocation == null) {
            persistMemo(ReminderRequest.NONE)
        } else {
            advanceReminder(readiness)
        }
    }

    /** Advances to the requested system screen or finishes the save without monitoring. */
    fun onRationaleResult(state: SaveState, action: RationaleAction) {
        if (_saveState.value != state) return
        if (action == RationaleAction.SKIP) {
            persistMemo(ReminderRequest.SKIP)
            return
        }
        val next = when (state) {
            SaveState.FOREGROUND_RATIONALE -> SaveState.REQUESTING_FOREGROUND
            SaveState.BACKGROUND_RATIONALE -> SaveState.OPENING_SETTINGS
            SaveState.LOCATION_SERVICES_RATIONALE -> SaveState.OPENING_LOCATION_SERVICES
            SaveState.NOTIFICATION_PERMISSION_RATIONALE -> if (action == RationaleAction.OPEN_SETTINGS) {
                SaveState.OPENING_APP_NOTIFICATIONS
            } else {
                SaveState.REQUESTING_NOTIFICATIONS
            }
            SaveState.APP_NOTIFICATIONS_RATIONALE -> SaveState.OPENING_APP_NOTIFICATIONS
            SaveState.CHANNEL_NOTIFICATIONS_RATIONALE -> SaveState.OPENING_CHANNEL_NOTIFICATIONS
            else -> return
        }
        awaitPermission(next)
    }

    /** Marks the current system screen as launched so Activity recreation cannot launch it twice. */
    fun claimPermissionLaunch(state: SaveState): Boolean {
        if (_saveState.value != state || savedState.get<Boolean>(LAUNCH_STARTED_KEY) == true) return false
        savedState[LAUNCH_STARTED_KEY] = true
        return true
    }

    /** Applies the current Android state after a runtime request or a return from Settings. */
    fun onPermissionResult(state: SaveState, readiness: ReminderReadiness) {
        if (_saveState.value != state || savedState.get<Boolean>(LAUNCH_STARTED_KEY) != true) return

        val location = readiness.location
        val declined = when (state) {
            SaveState.REQUESTING_FOREGROUND -> !location.isPreciseLocationEnabled
            SaveState.REQUESTING_BACKGROUND -> !location.isBackgroundLocationEnabled
            SaveState.REQUESTING_NOTIFICATIONS -> readiness.notificationAccess == NotificationAccess.PERMISSION_REQUIRED
            else -> false
        }
        if (declined) {
            persistMemo(ReminderRequest.SKIP)
            return
        }

        val incompleteSettings = when (state) {
            SaveState.OPENING_SETTINGS -> SaveState.BACKGROUND_RATIONALE.takeIf {
                !location.isPreciseLocationEnabled || !location.isBackgroundLocationEnabled
            }
            SaveState.OPENING_LOCATION_SERVICES -> SaveState.LOCATION_SERVICES_RATIONALE.takeUnless {
                readiness.locationServicesEnabled
            }
            SaveState.OPENING_APP_NOTIFICATIONS, SaveState.OPENING_CHANNEL_NOTIFICATIONS ->
                notificationRationale(readiness.notificationAccess)
            else -> null
        }
        if (incompleteSettings == null) {
            advanceReminder(readiness)
        } else {
            awaitPermission(incompleteSettings)
        }
    }

    /** Finds the first unmet prerequisite, or saves with geofence registration when all are met. */
    private fun advanceReminder(readiness: ReminderReadiness) {
        val next = when {
            !readiness.location.isPreciseLocationEnabled -> SaveState.FOREGROUND_RATIONALE
            sdkInt == Build.VERSION_CODES.Q && !readiness.location.isBackgroundLocationEnabled ->
                SaveState.REQUESTING_BACKGROUND
            sdkInt >= Build.VERSION_CODES.R && !readiness.location.isBackgroundLocationEnabled ->
                SaveState.BACKGROUND_RATIONALE
            !readiness.locationServicesEnabled -> SaveState.LOCATION_SERVICES_RATIONALE
            else -> notificationRationale(readiness.notificationAccess)
        }
        if (next == null) {
            persistMemo(ReminderRequest.REGISTER)
        } else {
            awaitPermission(next)
        }
    }

    /** Selects the notification prompt for the blocked permission, app, or channel. */
    private fun notificationRationale(access: NotificationAccess): SaveState? = when (access) {
        NotificationAccess.ENABLED -> null
        NotificationAccess.PERMISSION_REQUIRED -> SaveState.NOTIFICATION_PERMISSION_RATIONALE
        NotificationAccess.APP_DISABLED -> SaveState.APP_NOTIFICATIONS_RATIONALE
        NotificationAccess.CHANNEL_DISABLED -> SaveState.CHANNEL_NOTIFICATIONS_RATIONALE
    }

    /** Saves the next step and clears the launch marker for that step. */
    private fun awaitPermission(state: SaveState) {
        savedState[PERMISSION_STATE_KEY] = state.name
        savedState[LAUNCH_STARTED_KEY] = false
        _saveState.value = state
    }

    /** Saves the captured draft and publishes the write or geofence registration outcome. */
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
            reminderLatitude = draft.getDouble("latitude"),
            reminderLongitude = draft.getDouble("longitude"),
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

    /** Allows another save attempt after the UI has reported a write failure. */
    fun acknowledgeSaveFailure() {
        _saveState.value = SaveState.READY
    }

    /** Updates the draft from the form and includes the currently selected map point. */
    fun updateMemo(title: String, description: String, priority: Int) {
        val location = selectedLocation
        memo = memo.copy(
            title = title,
            description = description,
            priority = priority,
            reminderLatitude = location?.lat ?: 0.0,
            reminderLongitude = location?.lon ?: 0.0,
            createdAt = System.currentTimeMillis()
        )
    }

    /** Returns whether the draft has a title and description. */
    fun isMemoValid(): Boolean = memo.title.isNotBlank() && memo.description.isNotBlank()

    /** Returns whether the description is blank. */
    fun hasTextError() = memo.description.isBlank()

    /** Returns whether the title is blank. */
    fun hasTitleError() = memo.title.isBlank()

    private companion object {
        const val LOCATION_KEY = "selectedMemoLocation"
        const val DRAFT_KEY = "pendingMemoDraft"
        const val PERMISSION_STATE_KEY = "pendingMemoPermissionState"
        const val LAUNCH_STARTED_KEY = "pendingMemoPermissionLaunchStarted"
    }
}
