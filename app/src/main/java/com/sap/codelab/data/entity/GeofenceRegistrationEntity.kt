package com.sap.codelab.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class GeofenceRegistrationEntity(
    @PrimaryKey
    val geofenceId: String,
    val status: GeofenceStatus,
    val latitude: Double,
    val longitude: Double,
    val radius: Float,

    val lastRegisteredAt: Long?
)

enum class GeofenceStatus {
    PENDING,
    REGISTERED,
    FAILED
}