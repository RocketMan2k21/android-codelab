package com.sap.codelab

import android.app.Application
import androidx.room.Room
import com.sap.codelab.data.geofence.GeofenceNotificationManager
import com.sap.codelab.data.location.LocationPermissionSource
import com.sap.codelab.data.geofence.PlayServicesGeofenceManager
import com.sap.codelab.utils.Database
import com.sap.codelab.data.local.repository.RoomMemoRepository
import com.sap.codelab.data.location.PlayServicesLocationSettingsChecker
import com.sap.codelab.data.permission.NotificationPermissionRequestHistory
import com.sap.codelab.domain.LocationSettingsChecker
import com.sap.codelab.domain.usecase.CreateMemoUseCase
import com.sap.codelab.domain.usecase.MonitoringUseCase

/**
 * Extension of the Android Application class.
 */
internal class App : Application() {
    val geofenceNotificationManager by lazy { GeofenceNotificationManager(this) }
    val notificationPermissionRequestHistory by lazy { NotificationPermissionRequestHistory(this) }
    val locationPermissionSource by lazy { LocationPermissionSource(this) }
    val locationSettingsChecker: LocationSettingsChecker by lazy { PlayServicesLocationSettingsChecker(this) }
    private val geofenceManager by lazy { PlayServicesGeofenceManager(this) }

    val memoRepository by lazy {
        val database = Room.databaseBuilder(this, Database::class.java, "codelab")
            .fallbackToDestructiveMigration(true)
            .build()
        RoomMemoRepository(database)
    }

    val createMemoUseCase by lazy {
        CreateMemoUseCase(memoRepository, geofenceManager)
    }

    val monitoringUseCase by lazy {
        MonitoringUseCase(
            repository = memoRepository,
            geofenceManager = geofenceManager,
            locationPermissions = locationPermissionSource,
            notifications = geofenceNotificationManager
        )
    }

    override fun onCreate() {
        super.onCreate()
    }
}
