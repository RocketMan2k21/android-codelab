package com.sap.codelab.view.create

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sap.codelab.R
import com.sap.codelab.data.geofence.GeofenceNotificationManager
import com.sap.codelab.databinding.ActivityCreateMemoBinding
import com.sap.codelab.App
import com.sap.codelab.view.create.CreateMemoViewModel.SaveResult
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
    private val app get() = application as App

    private val selectLocation = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            MapActivity.readLocation(result.data)?.let { model.selectLocation(it) }
            showSelectedLocation()
        }
    }

    private val requestForegroundLocation = registerForActivityResult(LocationPermissionLauncher()) {
        model.onSystemResult(ReminderSystemAction.REQUEST_FOREGROUND_LOCATION)
    }

    private val requestBackgroundLocation = registerForActivityResult(
        LocationPermissionLauncher(LocationPermissionLauncher.Mode.BACKGROUND)
    ) {
        model.onSystemResult(ReminderSystemAction.REQUEST_BACKGROUND_LOCATION)
    }

    private val openLocationSettings = settingsLauncher(ReminderSystemAction.OPEN_LOCATION_SETTINGS)
    private val openLocationServices = settingsLauncher(ReminderSystemAction.OPEN_LOCATION_SERVICES)
    private val openAppNotifications = settingsLauncher(ReminderSystemAction.OPEN_APP_NOTIFICATIONS)
    private val openChannelNotifications = settingsLauncher(ReminderSystemAction.OPEN_CHANNEL_NOTIFICATIONS)
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.onSystemResult(ReminderSystemAction.REQUEST_NOTIFICATIONS)
    }

    private fun settingsLauncher(action: ReminderSystemAction) =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            model.onSystemResult(action)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateMemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val factory = viewModelFactory {
            initializer {
                CreateMemoViewModel(
                    savedState = createSavedStateHandle(),
                    createMemo = app.createMemoUseCase,
                    monitoring = app.monitoringUseCase,
                )
            }
        }
        model = ViewModelProvider(this, factory)[CreateMemoViewModel::class.java]
        supportFragmentManager.setFragmentResultListener(ReminderRequirementDialog.RESULT_KEY, this) { _, result ->
            val requirement = ReminderRequirementDialog.readRequirement(result) ?: return@setFragmentResultListener
            val action = RationaleAction.entries.firstOrNull {
                it.name == result.getString(ReminderRequirementDialog.ACTION_KEY)
            } ?: return@setFragmentResultListener
            model.onRationaleResult(requirement, action)
        }
        bindForm()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.saveState.collect(::renderSaveState)
            }
        }
    }

    private fun bindForm() = binding.contentCreateMemo.run {
        memoOptions.isVisible = true
        memoTitle.isSaveEnabled = false
        memoDescription.isSaveEnabled = false
        memoPriority.isSaveEnabled = false

        val draft = model.draft
        memoTitle.setText(draft.title)
        memoDescription.setText(draft.description)
        memoPriority.adapter = ArrayAdapter(
            this@CreateMemo,
            android.R.layout.simple_spinner_item,
            PriorityOption.entries.map { getString(it.label) }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val priority = PriorityOption.entries.firstOrNull { it.value == draft.priority } ?: PriorityOption.MEDIUM
        memoPriority.setSelection(priority.ordinal)

        memoTitle.doAfterTextChanged { updateDraftFromForm() }
        memoDescription.doAfterTextChanged { updateDraftFromForm() }
        memoPriority.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateDraftFromForm()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        memoLocation.setOnClickListener {
            if (model.saveState.value == SaveState.Ready) {
                selectLocation.launch(MapActivity.createIntent(this@CreateMemo, model.selectedLocation))
            }
        }
        showSelectedLocation()
    }

    private fun renderSaveState(state: SaveState) {
        invalidateOptionsMenu()
        binding.contentCreateMemo.run {
            val editable = state == SaveState.Ready
            memoTitle.isEnabled = editable
            memoDescription.isEnabled = editable
            memoPriority.isEnabled = editable
            memoLocation.isEnabled = editable
        }

        val step = (state as? SaveState.AwaitingReminder)?.step
        val requirement = (step as? ReminderStep.ShowRationale)?.requirement
        supportFragmentManager.fragments.filterIsInstance<ReminderRequirementDialog>().forEach { dialog ->
            if (requirement == null || dialog.requirement != requirement) dialog.dismissNow()
        }

        when (state) {
            SaveState.Ready, SaveState.Saving -> Unit
            is SaveState.AwaitingReminder -> when (val reminderStep = state.step) {
                is ReminderStep.ShowRationale -> showRequirementDialog(reminderStep.requirement)
                is ReminderStep.AwaitSystemResult -> launchRequirementStep(reminderStep.action)
            }
            is SaveState.Saved -> {
                val message = when (state.result) {
                    SaveResult.SAVED -> R.string.memo_saved
                    SaveResult.WITH_REMINDER -> R.string.memo_saved_with_reminder
                    SaveResult.WITHOUT_REMINDER -> R.string.memo_saved_without_reminder
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }
            SaveState.Failed -> {
                Toast.makeText(this, R.string.memo_save_error, Toast.LENGTH_SHORT).show()
                model.acknowledgeSaveFailure()
            }
        }
    }

    private fun showRequirementDialog(requirement: ReminderRequirement) {
        if (supportFragmentManager.fragments.filterIsInstance<ReminderRequirementDialog>()
                .any { it.requirement == requirement }
        ) return

        val dialog = when (requirement) {
            ReminderRequirement.PRECISE_LOCATION -> ReminderRequirementDialog.newInstance(
                requirement,
                R.string.location_permission_settings_title,
                getString(R.string.reminder_foreground_location_message),
                R.string.allow_location,
                showSettingsOption = false
            )
            ReminderRequirement.BACKGROUND_LOCATION -> ReminderRequirementDialog.newInstance(
                requirement, R.string.background_location_settings_title, backgroundGuidance()
            )
            ReminderRequirement.LOCATION_SERVICES -> ReminderRequirementDialog.newInstance(
                requirement, R.string.location_services_title, getString(R.string.location_services_message)
            )
            ReminderRequirement.NOTIFICATION_PERMISSION -> ReminderRequirementDialog.newInstance(
                requirement,
                R.string.reminder_notifications_title,
                getString(R.string.reminder_notifications_message),
                R.string.allow_notifications,
                showSettingsOption = true
            )
            ReminderRequirement.APP_NOTIFICATIONS -> ReminderRequirementDialog.newInstance(
                requirement, R.string.reminder_notifications_title, getString(R.string.reminder_notifications_message)
            )
            ReminderRequirement.CHANNEL_NOTIFICATIONS -> ReminderRequirementDialog.newInstance(
                requirement, R.string.reminder_notifications_title, getString(R.string.reminder_notification_channel_message)
            )
        }
        dialog.showNow(supportFragmentManager, ReminderRequirementDialog.TAG)
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
        return if (model.hasPreciseLocationPermission()) {
            message
        } else {
            getString(R.string.background_location_precise_guidance, message)
        }
    }

    private fun launchRequirementStep(action: ReminderSystemAction) {
        if (!model.claimSystemLaunch(action)) return
        try {
            when (action) {
                ReminderSystemAction.REQUEST_FOREGROUND_LOCATION -> requestForegroundLocation.launch(Unit)
                ReminderSystemAction.REQUEST_BACKGROUND_LOCATION -> requestBackgroundLocation.launch(Unit)
                ReminderSystemAction.OPEN_LOCATION_SETTINGS -> openLocationSettings.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
                ReminderSystemAction.OPEN_LOCATION_SERVICES -> openLocationServices.launch(
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                )
                ReminderSystemAction.REQUEST_NOTIFICATIONS -> requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                ReminderSystemAction.OPEN_APP_NOTIFICATIONS -> openAppNotifications.launch(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
                ReminderSystemAction.OPEN_CHANNEL_NOTIFICATIONS -> openChannelNotifications.launch(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, GeofenceNotificationManager.CHANNEL_ID)
                )
            }
        } catch (_: ActivityNotFoundException) {
            onRequirementLaunchFailed(action)
        } catch (_: SecurityException) {
            onRequirementLaunchFailed(action)
        }
    }

    private fun onRequirementLaunchFailed(action: ReminderSystemAction) {
        Toast.makeText(this, R.string.reminder_requirement_screen_error, Toast.LENGTH_SHORT).show()
        model.onSystemResult(action)
    }

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
        menu.findItem(R.id.action_save).isEnabled = model.saveState.value == SaveState.Ready
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
        if (model.saveState.value != SaveState.Ready) return
        updateDraftFromForm()
        val draft = model.draft
        val titleMissing = draft.title.isBlank()
        val descriptionMissing = draft.description.isBlank()
        binding.contentCreateMemo.run {
            memoTitleContainer.error = getString(R.string.memo_title_empty_error).takeIf { titleMissing }
            memoDescription.error = getString(R.string.memo_text_empty_error).takeIf { descriptionMissing }
        }
        if (!titleMissing && !descriptionMissing) {
            model.saveMemo()
        }
    }

    private fun updateDraftFromForm() = binding.contentCreateMemo.run {
        val priority = PriorityOption.entries.getOrNull(memoPriority.selectedItemPosition)?.value
            ?: model.draft.priority
        model.updateDraft(memoTitle.text.toString(), memoDescription.text.toString(), priority)
    }

    private enum class PriorityOption(val value: Int, @field:StringRes val label: Int) {
        HIGH(2, R.string.priority_high),
        MEDIUM(1, R.string.priority_medium),
        LOW(0, R.string.priority_low)
    }
}
