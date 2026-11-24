package com.sap.codelab.view.create

import com.sap.codelab.domain.model.Location
import com.sap.codelab.domain.model.Memo

internal data class MemoDraft(
    val title: String = "",
    val description: String = "",
    val priority: Int = DEFAULT_PRIORITY,
    val location: Location? = null,
) {
    /** Builds the new Room memo from the submitted fields. */
    fun toMemo() = Memo(
        id = 0,
        title = title,
        description = description,
        priority = priority,
        reminderDate = 0,
        reminderLatitude = location?.lat ?: 0.0,
        reminderLongitude = location?.lon ?: 0.0,
    )

    companion object {
        const val DEFAULT_PRIORITY = 1
    }
}
