package com.sap.codelab.data.entity

import androidx.room.Entity

@Entity(
    primaryKeys = ["geofenceId", "reminderId"]
)
data class GeofenceReminderCrossRef(
    val geofenceId: String,
    val reminderId: String
)