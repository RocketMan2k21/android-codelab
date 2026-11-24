package com.sap.codelab.data.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofenceStatusCodes
import com.sap.codelab.repository.App
import com.sap.codelab.utils.coroutines.ScopeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "GeofenceReceiver"

internal class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        Log.d("Geofence", "Received IDs: ${event.triggeringGeofences?.map { it.requestId }}")

        if (event.hasError()) {
            Log.w(TAG, GeofenceStatusCodes.getStatusCodeString(event.errorCode))
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_DWELL) return

        val memoIds = event.triggeringGeofences.orEmpty().mapNotNull { it.requestId.toLongOrNull() }
        if (memoIds.isEmpty()) return

        val app = context.applicationContext as App
        val pendingResult = goAsync() ?: return
        ScopeProvider.newScope(Dispatchers.Default).launch {
            try {
                app.memoRepository.getOpenByIds(memoIds).forEach { memo ->
                    app.geofenceNotificationManager.show(memo)
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Could not show geofence reminders", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
