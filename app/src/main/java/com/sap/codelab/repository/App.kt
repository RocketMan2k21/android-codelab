package com.sap.codelab.repository

import android.app.Application
import com.sap.codelab.data.location.GeofenceNotificationManager
import com.sap.codelab.data.location.LocationPermissionSource
import com.sap.codelab.data.location.PlayServicesGeofenceManager
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
        MonitoringUseCase(
            repository = RoomMemoRepository,
            geofenceManager = PlayServicesGeofenceManager(this),
            locationPermissions = LocationPermissionSource(this),
            notifications = geofenceNotificationManager
        )
    }

    override fun onCreate() {
        super.onCreate()
        RoomMemoRepository.initialize(this)
        geofenceNotificationManager = GeofenceNotificationManager(this)
    }
}
