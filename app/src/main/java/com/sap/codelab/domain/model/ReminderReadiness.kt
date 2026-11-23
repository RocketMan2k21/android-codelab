package com.sap.codelab.domain.model

internal data class ReminderReadiness(
    val location: LocationPermission,
    val locationServicesEnabled: Boolean,
    val notificationAccess: NotificationAccess
)

internal enum class NotificationAccess { ENABLED, PERMISSION_REQUIRED, APP_DISABLED, CHANNEL_DISABLED }
