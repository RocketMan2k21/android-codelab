package com.sap.codelab.data.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofenceStatusCodes

private const val TAG = "GeofenceReceiver"

internal class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, GeofenceStatusCodes.getStatusCodeString(event.errorCode))
            return
        }
        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_DWELL) {
            val reminderIds = event.triggeringGeofences.orEmpty().map { it.requestId }
            Log.d(TAG, "Dwell transition for reminders: $reminderIds")
        }
    }
}
