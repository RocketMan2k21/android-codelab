package com.sap.codelab.view.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.sap.codelab.App
import com.sap.codelab.R
import com.sap.codelab.data.location.FusedLocationDataSource
import com.sap.codelab.databinding.ActivityMapBinding
import com.sap.codelab.domain.model.Location
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class MapActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMapBinding
    private val permissionSource get() = (application as App).locationPermissionSource
    private val locationSource by lazy { FusedLocationDataSource(this, permissionSource) }
    private var map: GoogleMap? = null
    private var shouldCenterOnDevice = false
    private var centerJob: Job? = null

    private val requestLocationPermission = registerForActivityResult(LocationPermissionLauncher()) { result ->
        binding.locationStatus.isVisible = result == LocationPermissionLauncher.Result.DENIED
        centerMapOnDevice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeActionContentDescription(R.string.action_back)

        val initialLocation = readLocation(intent)
        shouldCenterOnDevice = savedInstanceState?.getBoolean(KEY_CENTER_ON_DEVICE) ?: (initialLocation == null)
        binding.locationStatus.isVisible = savedInstanceState?.getBoolean(KEY_PERMISSION_DENIED) == true
        binding.centerCurrentLocation.setOnClickListener {
            shouldCenterOnDevice = true
            checkLocationPermission()
        }
        binding.confirmLocation.setOnClickListener {
            val center = map?.cameraPosition?.target ?: return@setOnClickListener
            setResult(RESULT_OK, Intent().putLocation(Location(center.latitude, center.longitude)))
            finish()
        }

        val fragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        fragment.getMapAsync { googleMap ->
            map = googleMap
            if (savedInstanceState == null) {
                val target = initialLocation?.let { LatLng(it.lat, it.lon) } ?: LatLng(0.0, 0.0)
                val zoom = if (initialLocation == null) WORLD_ZOOM else LOCATION_ZOOM
                googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
            }
            googleMap.setOnCameraMoveStartedListener { reason ->
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                    shouldCenterOnDevice = false
                    centerJob?.cancel()
                }
            }
            binding.mapMarker.isVisible = true
            binding.centerCurrentLocation.isVisible = true
            binding.confirmLocation.isEnabled = true
            centerMapOnDevice()
        }

        if (savedInstanceState == null) {
            lifecycleScope.launch { lifecycle.withStarted { checkLocationPermission() } }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (permissionSource.getPermissionStatus().isLocationPermissionEnabled) {
            binding.locationStatus.isVisible = false
            centerMapOnDevice()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_CENTER_ON_DEVICE, shouldCenterOnDevice)
        outState.putBoolean(KEY_PERMISSION_DENIED, binding.locationStatus.isVisible)
        super.onSaveInstanceState(outState)
    }

    private fun checkLocationPermission() {
        val status = permissionSource.getPermissionStatus()
        when {
            status.isLocationPermissionEnabled -> centerMapOnDevice()
            status.isRequested -> {
                binding.locationStatus.isVisible = true
                AlertDialog.Builder(this)
                    .setTitle(R.string.location_permission_settings_title)
                    .setMessage(R.string.location_permission_settings_message)
                    .setPositiveButton(R.string.open_app_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }
                    .setNegativeButton(R.string.choose_location_manually, null)
                    .show()
            }
            else -> requestLocationPermission.launch(Unit)
        }
    }

    private fun centerMapOnDevice() {
        val googleMap = map ?: return
        if (!shouldCenterOnDevice || centerJob?.isActive == true ||
            !permissionSource.getPermissionStatus().isLocationPermissionEnabled
        ) {
            return
        }
        centerJob = lifecycleScope.launch {
            try {
                val location = locationSource.getCurrentLocation()
                shouldCenterOnDevice = false
                googleMap.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(location.lat, location.lon), LOCATION_ZOOM)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Manual selection remains available when the current location cannot be read.
            }
        }
    }

    companion object {
        private const val EXTRA_LATITUDE = "com.sap.codelab.map.LATITUDE"
        private const val EXTRA_LONGITUDE = "com.sap.codelab.map.LONGITUDE"
        private const val KEY_CENTER_ON_DEVICE = "centerOnDevice"
        private const val KEY_PERMISSION_DENIED = "permissionDenied"
        private const val WORLD_ZOOM = 2f
        private const val LOCATION_ZOOM = 15f

        fun createIntent(context: Context, location: Location?): Intent =
            Intent(context, MapActivity::class.java).apply {
                location?.let { putLocation(it) }
            }

        fun readLocation(intent: Intent?): Location? {
            val latitude = intent?.getDoubleExtra(EXTRA_LATITUDE, Double.NaN) ?: return null
            val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN)
            return if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                Location(latitude, longitude)
            } else {
                null
            }
        }

        private fun Intent.putLocation(location: Location): Intent =
            putExtra(EXTRA_LATITUDE, location.lat).putExtra(EXTRA_LONGITUDE, location.lon)
    }
}
