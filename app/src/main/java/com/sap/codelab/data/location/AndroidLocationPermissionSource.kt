package com.sap.codelab.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.sap.codelab.domain.model.LocationPermission
import androidx.core.content.edit

private const val PREFERENCES_NAME = "location_permissions"
private const val FOREGROUND_PERMISSION_REQUESTED = "foreground_permission_requested"

internal class LocationPermissionSource(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun getPermissionStatus(): LocationPermission {
        val fineGranted = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return LocationPermission(
            isLocationPermissionEnabled = fineGranted || coarseGranted,
            isRequested = preferences.getBoolean(FOREGROUND_PERMISSION_REQUESTED, false)
        )
    }

    fun markPermissionRequested() {
        preferences.edit { putBoolean(FOREGROUND_PERMISSION_REQUESTED, true) }
    }
}
