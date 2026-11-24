package com.sap.codelab.data.location

import android.content.Context
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.sap.codelab.domain.LocationSettingsChecker
import com.sap.codelab.domain.LocationSettingsChecker.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

internal class PlayServicesLocationSettingsChecker(context: Context) : LocationSettingsChecker {
    private val applicationContext = context.applicationContext
    private val client by lazy { LocationServices.getSettingsClient(applicationContext) }

    override suspend fun check(): Result {
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build())
            .build()

        return try {
            client.checkLocationSettings(request).await()
            Result.Satisfied
        } catch (error: ResolvableApiException) {
            Result.ResolutionRequired(error.resolution.intentSender)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.Unavailable
        }
    }
}
