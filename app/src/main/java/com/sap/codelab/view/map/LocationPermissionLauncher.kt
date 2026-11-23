package com.sap.codelab.view.map

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import com.sap.codelab.data.location.LocationPermissionSource

internal class LocationPermissionLauncher : ActivityResultContract<Unit, LocationPermissionLauncher.Result>() {
    enum class Result {
        PRECISE,
        APPROXIMATE,
        DENIED
    }

    private val contract = RequestMultiplePermissions()
    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    override fun createIntent(context: Context, input: Unit): Intent {
        LocationPermissionSource(context).markPermissionRequested()
        return contract.createIntent(context, permissions)
    }

    override fun getSynchronousResult(context: Context, input: Unit): SynchronousResult<Result>? =
        contract.getSynchronousResult(context, permissions)?.let { SynchronousResult(toResult(it.value)) }

    override fun parseResult(resultCode: Int, intent: Intent?): Result =
        toResult(contract.parseResult(resultCode, intent))

    private fun toResult(grants: Map<String, Boolean>): Result = when {
        grants[Manifest.permission.ACCESS_FINE_LOCATION] == true -> Result.PRECISE
        grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> Result.APPROXIMATE
        else -> Result.DENIED
    }
}
