package com.sap.codelab.domain.model


internal interface LocationDataSource {
    suspend fun getCurrentLocation(): Location
}

internal data class Location(
    val lat: Double,
    val lon: Double
)