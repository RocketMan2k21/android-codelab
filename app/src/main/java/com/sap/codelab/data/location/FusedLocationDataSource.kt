package com.sap.codelab.data.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sap.codelab.domain.model.Location
import com.sap.codelab.domain.model.LocationDataSource
import com.sap.codelab.domain.model.LocationUnavailableException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await

internal class FusedLocationDataSource(
    context: Context,
    private val permissionSource: LocationPermissionSource
) : LocationDataSource {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun getCurrentLocation(): Location {
        if (!permissionSource.getPermissionStatus().isLocationPermissionEnabled) {
            throw SecurityException("Location permission is required to get the current location.")
        }

        val cancellation = CancellationTokenSource()
        // The permission source checks for fine or coarse location access.
        @SuppressLint("MissingPermission")
        val location = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .await(cancellation) ?: throw LocationUnavailableException()

        return Location(lat = location.latitude, lon = location.longitude)
    }
}
