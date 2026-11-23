package com.sap.codelab.domain.model

internal interface LocationDataSource {
    /**
     * Returns a single location fix.
     *
     * @throws SecurityException when the app lacks location access.
     * @throws LocationUnavailableException when the provider cannot return a fix.
     */
    suspend fun getCurrentLocation(): Location
}

internal class LocationUnavailableException :
    IllegalStateException("The device could not provide a current location.")

internal data class Location(
    val lat: Double,
    val lon: Double
)
