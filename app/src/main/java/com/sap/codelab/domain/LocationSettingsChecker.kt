package com.sap.codelab.domain

import android.content.IntentSender

/** Checks device location settings and reports whether Android can prompt for a fix. */
internal interface LocationSettingsChecker {
    suspend fun check(): Result

    sealed interface Result {
        /** Current device settings satisfy the location request. */
        data object Satisfied : Result

        /** The [intentSender] launches the system dialog for resolving the settings issue. */
        data class ResolutionRequired(val intentSender: IntentSender) : Result

        /** The check failed. The caller can open the device's Location settings. */
        data object Unavailable : Result
    }
}
