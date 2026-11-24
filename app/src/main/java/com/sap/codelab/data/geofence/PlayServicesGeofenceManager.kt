package com.sap.codelab.data.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.sap.codelab.domain.GeofenceManager
import com.sap.codelab.domain.model.Memo
import kotlinx.coroutines.tasks.await

private const val GEOFENCE_RADIUS_METERS = 200f

private const val TAG = "PlayServicesGeofenceManager"

/** Registers geofence entry events with Google Play services using memo IDs as request IDs. */
internal class PlayServicesGeofenceManager(context: Context) : GeofenceManager {
    private val applicationContext = context.applicationContext
    private val client = LocationServices.getGeofencingClient(applicationContext)

    /** Shared by all geofences so Play services can deliver transition data to the receiver. */
    private val transitionIntent: PendingIntent by lazy {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        PendingIntent.getBroadcast(
            applicationContext,
            0,
            Intent(applicationContext, GeofenceBroadcastReceiver::class.java),
            flags
        )
    }

    override suspend fun register(memo: Memo) {
        require(memo.id > 0) { "The memo must be saved before geofence registration." }
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Precise location permission is required to register a geofence.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Background location permission is required to register a geofence.")
        }

        val geofence = Geofence.Builder()
            .setRequestId(memo.id.toString())
            .setCircularRegion(memo.reminderLatitude, memo.reminderLongitude, GEOFENCE_RADIUS_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        client.addGeofences(request, transitionIntent).await()
        Log.d(TAG, "Added request ${geofence.requestId} to geofence request")
    }

    /** Removes the geofence whose request ID matches [reminderId]. */
    override suspend fun remove(reminderId: String) {
        client.removeGeofences(listOf(reminderId)).await()
        Log.d(TAG, "Removed Geofence with id $reminderId")
    }
}
