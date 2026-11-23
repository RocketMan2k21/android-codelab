package com.sap.codelab.domain.model

internal data class LocationPermission(
    val isLocationPermissionEnabled: Boolean,
    val isRequested: Boolean
)

internal interface LocationPermissionSource {
    fun getPermissionStatus(): LocationPermission
}