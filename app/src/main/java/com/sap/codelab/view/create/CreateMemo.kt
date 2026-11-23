package com.sap.codelab.view.create

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.location.LocationManagerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sap.codelab.R
import com.sap.codelab.data.location.GeofenceNotificationManager
import com.sap.codelab.data.location.LocationPermissionSource
import com.sap.codelab.databinding.ActivityCreateMemoBinding
import com.sap.codelab.domain.model.ReminderReadiness
import com.sap.codelab.repository.App
import com.sap.codelab.utils.extensions.empty
import com.sap.codelab.view.create.CreateMemoViewModel.RationaleAction
import com.sap.codelab.view.create.CreateMemoViewModel.SaveState
import com.sap.codelab.view.map.LocationPermissionLauncher
import com.sap.codelab.view.map.MapActivity
import kotlinx.coroutines.launch

/**
 * Activity that allows a user to create a new Memo.
 */
internal class CreateMemo : AppCompatActivity() {

    private lateinit var binding: ActivityCreateMemoBinding
    private lateinit var model: CreateMemoViewModel
    private val permissionSource by lazy { LocationPermissionSource(this) }

    private val selectLocation = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            MapActivity.readLocation(result.data)?.let { model.selectLocation(it) }
            showSelectedLocation()
        }
    }

    private val requestForegroundLocation = registerForActivityResult(LocationPermissionLauncher()) {
        model.onPermissionResult(SaveState.REQUESTING_FOREGROUND, reminderReadiness())
    }

    private val requestBackgroundLocation = registerForActivityResult(
        LocationPermissionLauncher(LocationPermissionLauncher.Mode.BACKGROUND)
    ) {
        model.onPermissionResult(SaveState.REQUESTING_BACKGROUND, reminderReadiness())
    }

    private val openLocationSettings = settingsLauncher(SaveState.OPENING_SETTINGS)
    private val openLocationServices = settingsLauncher(SaveState.OPENING_LOCATION_SERVICES)
    private val openAppNotifications = settingsLauncher(SaveState.OPENING_APP_NOTIFICATIONS)
    private val openChannelNotifications = settingsLauncher(SaveState.OPENING_CHANNEL_NOTIFICATIONS)
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.onPermissionResult(SaveState.REQUESTING_NOTIFICATIONS, reminderReadiness())
    }

    private fun settingsLauncher(state: SaveState) =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            model.onPermissionResult(state, reminderReadiness())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateMemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val factory = viewModelFactory {
            initializer {
                CreateMemoViewModel(createSavedStateHandle(), (application as App).createMemoUseCase)
            }
        }
        model = ViewModelProvider(this, factory)[CreateMemoViewModel::class.java]
        supportFragmentManager.setFragmentResultListener(ReminderRequirementDialog.RESULT_KEY, this) { _, result ->
            val state = SaveState.valueOf(checkNotNull(result.getString(ReminderRequirementDialog.STATE_KEY)))
            val action = RationaleAction.valueOf(checkNotNull(result.getString(ReminderRequirementDialog.ACTION_KEY)))
            model.onRationaleResult(state, action)
        }
        binding.contentCreateMemo.run {
            memoOptions.isVisible = true
            memoPriority.adapter = ArrayAdapter(
                this@CreateMemo,
                android.R.layout.simple_spinner_item,
                PriorityOption.entries.map { getString(it.label) }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            if (savedInstanceState == null) {
                memoPriority.setSelection(PriorityOption.MEDIUM.ordinal)
            }
            memoLocation.setOnClickListener {
                if (model.saveState.value == SaveState.READY) {
                    selectLocation.launch(MapActivity.createIntent(this@CreateMemo, model.selectedLocation))
                }
            }
        }
        showSelectedLocation()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.saveState.collect { state ->
                    invalidateOptionsMenu()
                    when (state) {
                        SaveState.FOREGROUND_RATIONALE, SaveState.BACKGROUND_RATIONALE,
                        SaveState.LOCATION_SERVICES_RATIONALE, SaveState.NOTIFICATION_PERMISSION_RATIONALE,
                        SaveState.APP_NOTIFICATIONS_RATIONALE, SaveState.CHANNEL_NOTIFICATIONS_RATIONALE ->
                            showRequirementDialog(state)
                        SaveState.REQUESTING_FOREGROUND, SaveState.REQUESTING_BACKGROUND, SaveState.OPENING_SETTINGS,
                        SaveState.OPENING_LOCATION_SERVICES, SaveState.REQUESTING_NOTIFICATIONS,
                        SaveState.OPENING_APP_NOTIFICATIONS, SaveState.OPENING_CHANNEL_NOTIFICATIONS ->
                            launchRequirementStep(state)
                        SaveState.SAVED, SaveState.SAVED_WITH_REMINDER, SaveState.SAVED_WITHOUT_REMINDER -> {
                            val message = when (state) {
                                SaveState.SAVED_WITH_REMINDER -> R.string.memo_saved_with_reminder
                                SaveState.SAVED_WITHOUT_REMINDER -> R.string.memo_saved_without_reminder
                                else -> null
                            }
                            if (message != null) {
                                Toast.makeText(this@CreateMemo, message, Toast.LENGTH_LONG).show()
                            }
                            setResult(RESULT_OK)
                            finish()
                        }
                        SaveState.FAILED -> {
                            Toast.makeText(this@CreateMemo, R.string.memo_save_error, Toast.LENGTH_SHORT).show()
                            model.acknowledgeSaveFailure()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun showRequirementDialog(state: SaveState) {
        val tag = "${ReminderRequirementDialog.TAG}:${state.name}"
        if (supportFragmentManager.findFragmentByTag(tag) != null) return

        val dialog = when (state) {
            SaveState.FOREGROUND_RATIONALE -> ReminderRequirementDialog.newInstance(
                state,
                R.string.location_permission_settings_title,
                getString(R.string.reminder_foreground_location_message),
                R.string.allow_location,
                showSettingsOption = false
            )
            SaveState.BACKGROUND_RATIONALE -> ReminderRequirementDialog.newInstance(
                state, R.string.background_location_settings_title, backgroundGuidance()
            )
            SaveState.LOCATION_SERVICES_RATIONALE -> ReminderRequirementDialog.newInstance(
                state, R.string.location_services_title, getString(R.string.location_services_message)
            )
            SaveState.NOTIFICATION_PERMISSION_RATIONALE -> ReminderRequirementDialog.newInstance(
                state,
                R.string.reminder_notifications_title,
                getString(R.string.reminder_notifications_message),
                R.string.allow_notifications,
                showSettingsOption = true
            )
            SaveState.APP_NOTIFICATIONS_RATIONALE -> ReminderRequirementDialog.newInstance(
                state, R.string.reminder_notifications_title, getString(R.string.reminder_notifications_message)
            )
            SaveState.CHANNEL_NOTIFICATIONS_RATIONALE -> ReminderRequirementDialog.newInstance(
                state, R.string.reminder_notifications_title, getString(R.string.reminder_notification_channel_message)
            )
            else -> error("No dialog for $state")
        }
        dialog.show(supportFragmentManager, tag)
    }

    private fun backgroundGuidance(): String {
        val optionLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.backgroundPermissionOptionLabel
        } else {
            getString(R.string.allow_location_all_the_time)
        }
        val message = getString(
            R.string.background_location_settings_message,
            optionLabel
        )
        return if (permissionSource.getPermissionStatus().isPreciseLocationEnabled) {
            message
        } else {
            getString(R.string.background_location_precise_guidance, message)
        }
    }

    private fun launchRequirementStep(state: SaveState) {
        if (!model.claimPermissionLaunch(state)) return
        try {
            when (state) {
                SaveState.REQUESTING_FOREGROUND -> requestForegroundLocation.launch(Unit)
                SaveState.REQUESTING_BACKGROUND -> requestBackgroundLocation.launch(Unit)
                SaveState.OPENING_SETTINGS -> openLocationSettings.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
                SaveState.OPENING_LOCATION_SERVICES -> openLocationServices.launch(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                )
                SaveState.REQUESTING_NOTIFICATIONS -> requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                SaveState.OPENING_APP_NOTIFICATIONS -> openAppNotifications.launch(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
                SaveState.OPENING_CHANNEL_NOTIFICATIONS -> openChannelNotifications.launch(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, GeofenceNotificationManager.CHANNEL_ID)
                )
                else -> error("No permission launcher for $state")
            }
        } catch (_: ActivityNotFoundException) {
            onRequirementLaunchFailed(state)
        } catch (_: SecurityException) {
            onRequirementLaunchFailed(state)
        }
    }

    private fun onRequirementLaunchFailed(state: SaveState) {
        Toast.makeText(this, R.string.reminder_requirement_screen_error, Toast.LENGTH_SHORT).show()
        model.onPermissionResult(state, reminderReadiness())
    }

    private fun reminderReadiness() = ReminderReadiness(
        location = permissionSource.getPermissionStatus(),
        locationServicesEnabled = getSystemService(LocationManager::class.java)
            ?.let(LocationManagerCompat::isLocationEnabled) == true,
        notificationAccess = (application as App).geofenceNotificationManager.getAccess()
    )

    private fun showSelectedLocation() {
        val location = model.selectedLocation
        binding.contentCreateMemo.run {
            memoLocation.setText(if (location == null) R.string.add_memo_location else R.string.change_memo_location)
            memoLocationValue.isVisible = location != null
            memoLocationValue.text = location?.let { getString(R.string.selected_memo_location, it.lat, it.lon) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_create_memo, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_save).isEnabled = model.saveState.value == SaveState.READY
        return super.onPrepareOptionsMenu(menu)
    }

    /**
     * Handles actionbar interactions.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                saveMemo()
                true
            }

            else             -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Saves the memo if the input is valid; otherwise shows the corresponding error messages.
     */
    private fun saveMemo() {
        if (model.saveState.value != SaveState.READY) return
        binding.contentCreateMemo.run {
            val priority = PriorityOption.entries[memoPriority.selectedItemPosition]
            model.updateMemo(memoTitle.text.toString(), memoDescription.text.toString(), priority.value)
            if (model.isMemoValid()) {
                model.saveMemo(reminderReadiness())
            } else {
                memoTitleContainer.error = getErrorMessage(model.hasTitleError(), R.string.memo_title_empty_error)
                memoDescription.error = getErrorMessage(model.hasTextError(), R.string.memo_text_empty_error)
            }
        }
    }

    /**
     * Returns the error message if there is an error, or an empty string otherwise.
     *
     * @param hasError          - whether there is an error.
     * @param errorMessageResId - the resource id of the error message to show.
     * @return the error message if there is an error, or an empty string otherwise.
     */
    private fun getErrorMessage(hasError: Boolean, @StringRes errorMessageResId: Int): String {
        return if (hasError) {
            getString(errorMessageResId)
        } else {
            String.empty()
        }
    }

    private enum class PriorityOption(val value: Int, @field:StringRes val label: Int) {
        HIGH(2, R.string.priority_high),
        MEDIUM(1, R.string.priority_medium),
        LOW(0, R.string.priority_low)
    }
}
