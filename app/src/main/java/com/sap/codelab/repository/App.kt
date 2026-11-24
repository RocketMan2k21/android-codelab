package com.sap.codelab.repository

import android.app.Application
import androidx.room.Room
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

    val memoRepository by lazy {
        val database = Room.databaseBuilder(this, Database::class.java, "codelab")
            .fallbackToDestructiveMigration(true)
            .build()
        RoomMemoRepository(database)
    }

    val createMemoUseCase by lazy {
        CreateMemoUseCase(memoRepository, PlayServicesGeofenceManager(this))
    }

    val monitoringUseCase by lazy {
        MonitoringUseCase(
            repository = memoRepository,
            geofenceManager = PlayServicesGeofenceManager(this),
            locationPermissions = LocationPermissionSource(this),
            notifications = geofenceNotificationManager
        )
    }

    override fun onCreate() {
        super.onCreate()
        geofenceNotificationManager = GeofenceNotificationManager(this)
    }
}
