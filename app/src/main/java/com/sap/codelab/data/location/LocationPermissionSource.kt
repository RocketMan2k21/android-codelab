package com.sap.codelab.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import com.sap.codelab.domain.model.LocationPermission

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
        val foregroundGranted = fineGranted || coarseGranted
        val backgroundGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            foregroundGranted &&
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            foregroundGranted
        }

        return LocationPermission(
            isLocationPermissionEnabled = foregroundGranted,
            isPreciseLocationEnabled = fineGranted,
            isBackgroundLocationEnabled = backgroundGranted,
            isRequested = preferences.getBoolean(FOREGROUND_PERMISSION_REQUESTED, false)
        )
    }

    fun isLocationEnabled(): Boolean {
        val manager = applicationContext.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    fun markPermissionRequested() {
        preferences.edit { putBoolean(FOREGROUND_PERMISSION_REQUESTED, true) }
    }
}
