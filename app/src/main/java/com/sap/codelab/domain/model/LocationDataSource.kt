package com.sap.codelab.domain.model

import kotlin.math.roundToLong

private const val E7_SCALE = 10_000_000.0

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
) {
    // Store seven decimal places in the existing INTEGER columns.
    val latitudeE7: Long get() = (lat * E7_SCALE).roundToLong()
    val longitudeE7: Long get() = (lon * E7_SCALE).roundToLong()

    companion object {
        fun fromE7(latitude: Long, longitude: Long): Location =
            Location(latitude / E7_SCALE, longitude / E7_SCALE)
    }
}
