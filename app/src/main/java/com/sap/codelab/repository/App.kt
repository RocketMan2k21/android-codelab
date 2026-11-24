package com.sap.codelab.repository

import android.app.Application
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import com.sap.codelab.data.location.GeofenceNotificationManager
import com.sap.codelab.data.location.LocationPermissionSource
import com.sap.codelab.data.location.PlayServicesGeofenceManager
import com.sap.codelab.domain.model.ReminderReadiness
import com.sap.codelab.domain.usecase.CreateMemoUseCase
import com.sap.codelab.domain.usecase.MonitoringUseCase

/**
 * Extension of the Android Application class.
 */
internal class App : Application() {
    lateinit var geofenceNotificationManager: GeofenceNotificationManager
        private set

    val createMemoUseCase by lazy {
        CreateMemoUseCase(RoomMemoRepository, PlayServicesGeofenceManager(this))
    }

    val monitoringUseCase by lazy {
        val permissions = LocationPermissionSource(this)
        MonitoringUseCase(RoomMemoRepository, PlayServicesGeofenceManager(this)) {
            ReminderReadiness(
                location = permissions.getPermissionStatus(),
                locationServicesEnabled = getSystemService(LocationManager::class.java)
                    ?.let(LocationManagerCompat::isLocationEnabled) == true,
                notificationAccess = geofenceNotificationManager.getAccess()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        RoomMemoRepository.initialize(this)
        geofenceNotificationManager = GeofenceNotificationManager(this)
    }
}
