package com.sap.codelab.data.permission

import android.content.Context
import androidx.core.content.edit

/** Remembers whether the app has requested the notification permission. */
internal class NotificationPermissionRequestHistory(context: Context) {
    private val preferences = context.getSharedPreferences("notification_permissions", Context.MODE_PRIVATE)

    fun wasRequested(): Boolean = preferences.getBoolean("requested", false)

    fun markRequested() {
        preferences.edit { putBoolean("requested", true) }
    }
}
