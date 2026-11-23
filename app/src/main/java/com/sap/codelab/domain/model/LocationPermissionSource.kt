package com.sap.codelab.domain.model

internal data class LocationPermission(
    /** Whether the app has precise or approximate foreground location access. */
    val isLocationPermissionEnabled: Boolean,
    /** Whether the app has previously launched a foreground location permission request. */
    val isRequested: Boolean
)