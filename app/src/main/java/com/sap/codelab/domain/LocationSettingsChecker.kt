package com.sap.codelab.domain

import android.content.IntentSender

/** Checks device location settings and reports whether Android can prompt for a fix. */
internal interface LocationSettingsChecker {
    suspend fun check(): Result

    sealed interface Result {
        data object Satisfied : Result
        data class ResolutionRequired(val intentSender: IntentSender) : Result
        data object Unavailable : Result
    }
}
