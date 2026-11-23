package com.sap.codelab.repository

import android.app.Application
import com.sap.codelab.data.location.GeofenceNotificationManager

/**
 * Extension of the Android Application class.
 */
internal class App : Application() {
    lateinit var geofenceNotificationManager: GeofenceNotificationManager
        private set

    override fun onCreate() {
        super.onCreate()
        RoomMemoRepository.initialize(this)
        geofenceNotificationManager = GeofenceNotificationManager(this)
    }
}
