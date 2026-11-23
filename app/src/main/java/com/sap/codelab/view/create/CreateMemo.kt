package com.sap.codelab.view.create

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sap.codelab.R
import com.sap.codelab.data.location.LocationPermissionSource
import com.sap.codelab.databinding.ActivityCreateMemoBinding
import com.sap.codelab.repository.App
import com.sap.codelab.utils.extensions.empty
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
        model.onPermissionResult(SaveState.REQUESTING_FOREGROUND, permissionSource.getPermissionStatus())
    }

    private val requestBackgroundLocation = registerForActivityResult(
        LocationPermissionLauncher(LocationPermissionLauncher.Mode.BACKGROUND)
    ) {
        model.onPermissionResult(SaveState.REQUESTING_BACKGROUND, permissionSource.getPermissionStatus())
    }

    private val openLocationSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.onPermissionResult(SaveState.OPENING_SETTINGS, permissionSource.getPermissionStatus())
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
        supportFragmentManager.setFragmentResultListener(BackgroundLocationDialog.RESULT_KEY, this) { _, result ->
            model.onBackgroundRationaleResult(result.getBoolean(BackgroundLocationDialog.OPEN_SETTINGS_KEY))
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
                        SaveState.BACKGROUND_RATIONALE -> showBackgroundRationale()
                        SaveState.REQUESTING_FOREGROUND, SaveState.REQUESTING_BACKGROUND, SaveState.OPENING_SETTINGS ->
                            launchPermissionStep(state)
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

    private fun showBackgroundRationale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            supportFragmentManager.findFragmentByTag(BackgroundLocationDialog.TAG) != null
        ) return

        val message = getString(
            R.string.background_location_settings_message,
            packageManager.backgroundPermissionOptionLabel
        )
        val guidance = if (permissionSource.getPermissionStatus().isPreciseLocationEnabled) {
            message
        } else {
            getString(R.string.background_location_precise_guidance, message)
        }
        BackgroundLocationDialog.newInstance(guidance).show(supportFragmentManager, BackgroundLocationDialog.TAG)
    }

    private fun launchPermissionStep(state: SaveState) {
        if (!model.claimPermissionLaunch(state)) return
        try {
            when (state) {
                SaveState.REQUESTING_FOREGROUND -> requestForegroundLocation.launch(Unit)
                SaveState.REQUESTING_BACKGROUND -> requestBackgroundLocation.launch(Unit)
                SaveState.OPENING_SETTINGS -> openLocationSettings.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
                else -> error("No permission launcher for $state")
            }
        } catch (_: ActivityNotFoundException) {
            model.onPermissionResult(state, null)
        } catch (_: SecurityException) {
            model.onPermissionResult(state, null)
        }
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
                model.saveMemo(permissionSource.getPermissionStatus())
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
