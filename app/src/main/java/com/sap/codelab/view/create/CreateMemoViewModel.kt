package com.sap.codelab.view.create

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sap.codelab.domain.model.Location
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.usecase.CreateMemoUseCase
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
    private val createMemo: CreateMemoUseCase
) : ViewModel() {

    enum class SaveState { READY, SAVING, SAVED, SAVED_WITHOUT_REMINDER, FAILED }

    private val _saveState = MutableStateFlow(SaveState.READY)
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
    fun saveMemo() {
        if (_saveState.value != SaveState.READY) return // TODO: use update{}
        _saveState.value = SaveState.SAVING
        val memoToSave = memo
        val hasSelectedLocation = selectedLocation != null
        viewModelScope.launch {
            try {
                val reminderError = createMemo(memoToSave, hasSelectedLocation).exceptionOrNull()
                _saveState.value = if (reminderError == null) {
                    SaveState.SAVED
                } else {
                    Log.w("CreateMemo", "Memo saved without an active location reminder", reminderError)
                    SaveState.SAVED_WITHOUT_REMINDER
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
    }
}
