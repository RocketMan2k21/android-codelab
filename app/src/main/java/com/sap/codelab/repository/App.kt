package com.sap.codelab.repository

import android.app.Application
import com.sap.codelab.data.location.GeofenceNotificationManager
import com.sap.codelab.data.location.PlayServicesGeofenceManager
import com.sap.codelab.domain.usecase.CreateMemoUseCase

/**
 * Extension of the Android Application class.
 */
internal class App : Application() {
    lateinit var geofenceNotificationManager: GeofenceNotificationManager
        private set

    val createMemoUseCase by lazy {
        CreateMemoUseCase(RoomMemoRepository, PlayServicesGeofenceManager(this))
    }

    override fun onCreate() {
        super.onCreate()
        RoomMemoRepository.initialize(this)
        geofenceNotificationManager = GeofenceNotificationManager(this)
    }
}
