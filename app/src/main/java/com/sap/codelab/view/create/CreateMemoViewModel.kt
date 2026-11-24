package com.sap.codelab.view.create

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sap.codelab.domain.model.Location
import com.sap.codelab.domain.model.ReminderReadiness
import com.sap.codelab.domain.usecase.CreateMemoUseCase
import com.sap.codelab.domain.usecase.CreateMemoUseCase.Outcome
import com.sap.codelab.domain.usecase.CreateMemoUseCase.ReminderRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Coordinates memo creation and any prerequisite screens for its reminder. */
internal class CreateMemoViewModel(
    private val savedState: SavedStateHandle,
    private val createMemo: CreateMemoUseCase,
    sdkInt: Int = Build.VERSION.SDK_INT,
) : ViewModel() {

    sealed interface SaveState {
        data object Ready : SaveState
        data class AwaitingReminder(val step: ReminderStep) : SaveState
        data object Saving : SaveState
        data class Saved(val result: SaveResult) : SaveState
        data object Failed : SaveState
    }

    enum class SaveResult { SAVED, WITH_REMINDER, WITHOUT_REMINDER }

    private val reminderFlow = ReminderFlow(sdkInt)
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Ready)
    val saveState = _saveState.asStateFlow()

    var draft: MemoDraft = restoreDraft()
        private set

    val selectedLocation: Location?
        get() = draft.location

    /** Updates the form fields while the draft is editable. */
    fun updateDraft(title: String, description: String, priority: Int) {
        if (_saveState.value != SaveState.Ready) return
        updateDraft(draft.copy(title = title, description = description, priority = priority))
    }

    /** Updates the draft with the point chosen on the map. */
    fun selectLocation(location: Location) {
        if (_saveState.value != SaveState.Ready) return
        updateDraft(draft.copy(location = location))
    }

    /** Starts saving a valid draft and checks reminder requirements when it has a location. */
    fun saveMemo(readiness: ReminderReadiness) {
        if (_saveState.value != SaveState.Ready || draft.title.isBlank() || draft.description.isBlank()) return

        if (draft.location == null) {
            persist(ReminderRequest.NONE)
        } else {
            applyDecision(reminderFlow.start(readiness))
        }
    }

    /** Applies the user's choice when it matches the current rationale. */
    fun onRationaleResult(requirement: ReminderRequirement, action: RationaleAction) {
        val step = currentStep() as? ReminderStep.ShowRationale ?: return
        if (step.requirement != requirement) return
        applyDecision(reminderFlow.onRationaleResult(requirement, action))
    }

    /** Claims the current launch so a recreated Activity does not open the screen again. */
    fun claimSystemLaunch(action: ReminderSystemAction): Boolean {
        val step = currentStep() as? ReminderStep.AwaitSystemResult ?: return false
        if (step.action != action || step.launched) return false

        _saveState.value = SaveState.AwaitingReminder(step.copy(launched = true))
        return true
    }

    /** Uses fresh readiness after the matching system action returns. */
    fun onSystemResult(action: ReminderSystemAction, readiness: ReminderReadiness) {
        val step = currentStep() as? ReminderStep.AwaitSystemResult ?: return
        if (step.action != action || !step.launched) return
        applyDecision(reminderFlow.onSystemResult(action, readiness))
    }

    /** Reenables editing after the Activity reports a write failure. */
    fun acknowledgeSaveFailure() {
        if (_saveState.value == SaveState.Failed) {
            _saveState.value = SaveState.Ready
        }
    }

    /** Returns the current reminder step, if a save is waiting for one. */
    private fun currentStep(): ReminderStep? =
        (_saveState.value as? SaveState.AwaitingReminder)?.step

    /** Publishes the next reminder step or starts writing the memo. */
    private fun applyDecision(decision: ReminderDecision) {
        when (decision) {
            is ReminderDecision.Wait -> _saveState.value = SaveState.AwaitingReminder(decision.step)
            is ReminderDecision.Save -> persist(decision.request)
        }
    }

    /** Writes the submitted draft and clears its saved-state copy on success. */
    private fun persist(request: ReminderRequest) {
        val draftToSave = draft
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            try {
                val result = when (val outcome = createMemo(draftToSave.toMemo(), request)) {
                    Outcome.Saved -> SaveResult.SAVED
                    Outcome.MonitoringActive -> SaveResult.WITH_REMINDER
                    Outcome.MonitoringSkipped -> SaveResult.WITHOUT_REMINDER
                    is Outcome.RegistrationFailed -> {
                        Log.w(TAG, "Memo saved without an active location reminder", outcome.error)
                        SaveResult.WITHOUT_REMINDER
                    }
                }
                savedState.remove<Bundle>(DRAFT_KEY)
                _saveState.value = SaveState.Saved(result)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Could not save memo", error)
                _saveState.value = SaveState.Failed
            }
        }
    }

    /** Updates the in-memory draft and its backup for process recreation. */
    private fun updateDraft(value: MemoDraft) {
        if (value == draft) return
        draft = value
        storeDraft(value)
    }

    /** Backs up only the editable fields and selected point. */
    private fun storeDraft(value: MemoDraft) {
        savedState[DRAFT_KEY] = Bundle().apply {
            putString("title", value.title)
            putString("description", value.description)
            putInt("priority", value.priority)
            value.location?.let { putDoubleArray("location", doubleArrayOf(it.lat, it.lon)) }
        }
    }

    /** Restores the saved memo fields or starts with an empty draft. */
    private fun restoreDraft(): MemoDraft {
        val data = savedState.get<Bundle>(DRAFT_KEY) ?: return MemoDraft()
        val location = data.getDoubleArray("location")
            ?.takeIf { it.size == 2 }
            ?.let { Location(it[0], it[1]) }

        return MemoDraft(
            title = data.getString("title").orEmpty(),
            description = data.getString("description").orEmpty(),
            priority = data.getInt("priority", MemoDraft.DEFAULT_PRIORITY),
            location = location,
        )
    }

    private companion object {
        const val TAG = "CreateMemo"
        const val DRAFT_KEY = "memoDraft"
    }
}
