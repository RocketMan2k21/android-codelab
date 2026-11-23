package com.sap.codelab.view.create

import androidx.lifecycle.ViewModel
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.repository.RoomMemoRepository
import com.sap.codelab.utils.coroutines.ScopeProvider
import com.sap.codelab.utils.extensions.empty
import kotlinx.coroutines.launch

/**
 * ViewModel for matching CreateMemo view. Handles user interactions.
 */
internal class CreateMemoViewModel : ViewModel() {

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
        ScopeProvider.application.launch {
            RoomMemoRepository.saveMemo(memo)
        }
    }

    /**
     * Call this method to update the memo. This is usually needed when the user changed his input.
     */
    fun updateMemo(title: String, description: String) {
        memo = memo.copy(title = title, description = description, createdAt = System.currentTimeMillis())
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
}
