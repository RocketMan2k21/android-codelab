package com.sap.codelab.data.location

import com.sap.codelab.domain.model.Memo

internal interface GeofenceManager {
    /** Registers a saved memo with a selected location. The request ID is its memo ID as a string. */
    suspend fun register(memo: Memo)
    suspend fun remove(reminderId: String)
}
