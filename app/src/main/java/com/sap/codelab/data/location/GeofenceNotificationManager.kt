package com.sap.codelab.data.location

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.icu.text.BreakIterator
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.view.detail.BUNDLE_MEMO_ID
import com.sap.codelab.view.detail.ViewMemo

internal class GeofenceNotificationManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val notifications = NotificationManagerCompat.from(applicationContext)

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Location reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    /** Shows the reminder for a memo whose geofence has triggered. */
    fun show(memo: Memo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val characters = BreakIterator.getCharacterInstance().apply { setText(memo.description) }
        val end = characters.next(DESCRIPTION_LIMIT)
        val preview = if (end == BreakIterator.DONE) memo.description else memo.description.substring(0, end)

        val details = Intent(applicationContext, ViewMemo::class.java)
            .setData(Uri.fromParts("memo", memo.id.toString(), null))
            .putExtra(BUNDLE_MEMO_ID, memo.id)
        val openMemo = PendingIntent.getActivity(applicationContext, 0, details, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("You’ve arrived • ${memo.title}")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openMemo)
            .setAutoCancel(true)
            .build()

        notifications.notify("geofence:${memo.id}", 0, notification)
    }

    private companion object {
        const val CHANNEL_ID = "geofence_reminders"
        const val DESCRIPTION_LIMIT = 140
    }
}
