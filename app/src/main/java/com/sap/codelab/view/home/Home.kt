package com.sap.codelab.view.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.graphics.ColorUtils
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.material.color.MaterialColors
import com.sap.codelab.R
import com.sap.codelab.data.location.GeofenceNotificationManager
import com.sap.codelab.databinding.ActivityHomeBinding
import com.sap.codelab.databinding.RecyclerviewMemoBinding
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.model.NotificationAccess
import com.sap.codelab.repository.App
import com.sap.codelab.view.create.CreateMemo
import com.sap.codelab.view.detail.BUNDLE_MEMO_ID
import com.sap.codelab.view.detail.ViewMemo
import com.sap.codelab.view.map.LocationPermissionLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The main activity of the app. Shows a list of recorded memos and lets the user add new memos.
 */
internal class Home : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var model: HomeViewModel
    private lateinit var menuItemShowAll: MenuItem
    private lateinit var menuItemShowOpen: MenuItem
    private lateinit var monitoringBanner: LinearLayout
    private var monitoringDialog: AlertDialog? = null
    private var monitoringOptions: MonitoringOptions? = null
    private var restoreMonitoringDialog = false
    private val requestedPermissions = mutableSetOf<String>()

    private val foregroundLocation = registerForActivityResult(LocationPermissionLauncher()) {
        model.refreshMemos()
    }
    private val runtimePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refreshMemos()
    }
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.refreshMemos()
    }
    private val resolveLocationSettings =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            model.refreshMemos()
        }

    private val createMemoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            model.refreshMemos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        val factory = viewModelFactory {
            initializer { HomeViewModel.create(application as App) }
        }
        model = ViewModelProvider(this, factory)[HomeViewModel::class.java]
        savedInstanceState?.getStringArrayList(REQUESTED_PERMISSIONS)?.let(requestedPermissions::addAll)
        restoreMonitoringDialog = savedInstanceState?.getBoolean(MONITORING_DIALOG_OPEN) == true

        // Setup the adapter and the recycler view
        setupRecyclerView(initializeAdapter())

        binding.fab.setOnClickListener {
            // Handles clicks on the FAB button > creates a new Memo
            createMemoLauncher.launch(Intent(this@Home, CreateMemo::class.java))
        }
        setupMonitoring()
    }

    override fun onResume() {
        super.onResume()
        model.refreshMemos()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(REQUESTED_PERMISSIONS, ArrayList(requestedPermissions))
        outState.putBoolean(MONITORING_DIALOG_OPEN, monitoringDialog?.isShowing == true || restoreMonitoringDialog)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        monitoringDialog?.dismiss()
        super.onDestroy()
    }

    private fun setupMonitoring() {
        val spacing = (8 * resources.displayMetrics.density).toInt()
        val errorColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError, 0xFFB00020.toInt())
        monitoringBanner = LinearLayout(this).apply {
            id = View.generateViewId()
            isVisible = false
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(spacing * 2, 0, spacing, 0)
            background = GradientDrawable().apply {
                setColor(ColorUtils.setAlphaComponent(errorColor, 32))
                cornerRadius = spacing.toFloat()
            }
            val label = TextView(context).apply {
                text = "Monitoring inactive"
                setTextAppearance(android.R.style.TextAppearance_Medium)
                setTextColor(errorColor)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            val fix = AppCompatButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = "Fix"
                setTextColor(errorColor)
                contentDescription = "Fix monitoring"
                setOnClickListener { showMonitoringSettings() }
            }
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(fix, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val content = binding.contentHome
        content.root.addView(monitoringBanner, 0)
        ConstraintSet().apply {
            clone(content.root)
            constrainWidth(monitoringBanner.id, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(monitoringBanner.id, ConstraintSet.WRAP_CONTENT)
            connect(monitoringBanner.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, spacing * 2)
            connect(monitoringBanner.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, spacing * 2)
            connect(monitoringBanner.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, spacing)
            constrainHeight(content.recyclerView.id, ConstraintSet.MATCH_CONSTRAINT)
            connect(content.recyclerView.id, ConstraintSet.TOP, monitoringBanner.id, ConstraintSet.BOTTOM, spacing)
            setGoneMargin(content.recyclerView.id, ConstraintSet.TOP, 0)
            applyTo(content.root)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.monitoringState.collect(::renderMonitoring) }
                launch {
                    model.memoUpdateErrors.collect {
                        Toast.makeText(this@Home, "Couldn’t update the memo or its location reminder.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showMonitoringSettings() {
        if (monitoringDialog != null || !monitoringBanner.isVisible) return
        val builder = AlertDialog.Builder(this)
        val options = MonitoringOptions(builder.context).apply {
            foreground.fix.setOnClickListener { fixForegroundLocation() }
            background.fix.setOnClickListener { fixBackgroundLocation() }
            services.fix.setOnClickListener { fixLocationServices() }
            notifications.fix.setOnClickListener { fixNotifications() }
        }
        val scroll = ScrollView(builder.context).apply { addView(options) }
        val dialog = builder
            .setTitle("Monitoring settings")
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Activate monitoring", null)
            .create()
        monitoringOptions = options
        monitoringDialog = dialog
        dialog.setOnDismissListener {
            monitoringOptions = null
            monitoringDialog = null
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { model.activateMonitoring() }
        renderMonitoring(model.monitoringState.value)
    }

    private fun renderMonitoring(state: HomeViewModel.MonitoringState) {
        val currentStatus = state.status ?: return
        monitoringBanner.isVisible = currentStatus.isVisible && !currentStatus.isActive
        if (!monitoringBanner.isVisible) {
            restoreMonitoringDialog = false
            monitoringDialog?.dismiss()
            return
        }
        if (restoreMonitoringDialog) {
            restoreMonitoringDialog = false
            showMonitoringSettings()
            return
        }
        val options = monitoringOptions ?: return
        val requirements = currentStatus.requirements
        options.foreground.render(requirements.location.isPreciseLocationEnabled)
        options.background.render(requirements.location.isBackgroundLocationEnabled)
        options.services.render(requirements.locationServicesEnabled)
        options.notifications.render(requirements.notificationAccess == NotificationAccess.ENABLED)
        monitoringDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled =
            currentStatus.canActivate && !state.isActivating
        val failures = state.failedIds.size
        options.error.isVisible = failures > 0
        options.error.text = when (failures) {
            0 -> ""
            1 -> "Couldn’t register one reminder."
            else -> "Couldn’t register $failures reminders."
        }
    }

    private fun fixForegroundLocation() {
        val location = model.readRequirements().location
        val permission = Manifest.permission.ACCESS_FINE_LOCATION
        if (location.isPreciseLocationEnabled) return
        val canRequest = !location.isRequested || shouldShowRequestPermissionRationale(permission) ||
            (location.isLocationPermissionEnabled && permission !in requestedPermissions)
        if (canRequest) {
            requestedPermissions.add(permission)
            foregroundLocation.launch(Unit)
        } else {
            openSettings(appDetailsSettings())
        }
    }

    private fun fixBackgroundLocation() {
        val location = model.readRequirements().location
        if (location.isBackgroundLocationEnabled) return
        if (!location.isPreciseLocationEnabled) {
            fixForegroundLocation()
            return
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestPermissionOrOpenSettings(Manifest.permission.ACCESS_BACKGROUND_LOCATION, appDetailsSettings())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle(R.string.background_location_settings_title)
                .setMessage(getString(
                    R.string.background_location_settings_message,
                    packageManager.backgroundPermissionOptionLabel
                ))
                .setPositiveButton(R.string.open_app_settings) { _, _ -> openSettings(appDetailsSettings()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun fixLocationServices() {
        lifecycleScope.launch {
            val request = LocationSettingsRequest.Builder()
                .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).build())
                .build()
            try {
                LocationServices.getSettingsClient(this@Home).checkLocationSettings(request).await()
                if (!model.readRequirements().locationServicesEnabled) {
                    openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    model.refreshMemos()
                }
            } catch (error: ResolvableApiException) {
                resolveLocationSettings.launch(IntentSenderRequest.Builder(error.resolution).build())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
    }

    private fun fixNotifications() {
        val notificationsSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        when (model.readRequirements().notificationAccess) {
            NotificationAccess.ENABLED -> Unit
            NotificationAccess.PERMISSION_REQUIRED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissionOrOpenSettings(Manifest.permission.POST_NOTIFICATIONS, notificationsSettings)
                }
            }
            NotificationAccess.APP_DISABLED -> openSettings(notificationsSettings)
            NotificationAccess.CHANNEL_DISABLED -> openSettings(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, GeofenceNotificationManager.CHANNEL_ID)
            )
        }
    }

    private fun requestPermissionOrOpenSettings(permission: String, settingsIntent: Intent) {
        if (permission !in requestedPermissions || shouldShowRequestPermissionRationale(permission)) {
            requestedPermissions.add(permission)
            runtimePermission.launch(permission)
        } else {
            openSettings(settingsIntent)
        }
    }

    private fun appDetailsSettings() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    private fun openSettings(intent: Intent) {
        try {
            settings.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.reminder_requirement_screen_error, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.reminder_requirement_screen_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Initializes the adapter and sets the needed callbacks.
     */
    private fun initializeAdapter(): HomeMemoAdapter {
        val adapter = HomeMemoAdapter({ view ->
            // Implementation for when the user selects a row to show the detail view
            showMemo((view.tag as Memo).id)
        }, { checkbox, isChecked ->
            // Implementation for when the user marks a memo as completed
            model.updateMemo((checkbox.tag as Memo).id, isChecked)
        })
        lifecycleScope.launch {
            model.memos.collect { memos ->
                adapter.submitList(memos)
            }
        }
        return adapter
    }

    /**
     * Opens the Memo detail view for the given memoId.
     *
     * @param memoId    - the id of the memo to be shown.
     */
    private fun showMemo(memoId: Long) {
        val intent = Intent(this@Home, ViewMemo::class.java)
        intent.putExtra(BUNDLE_MEMO_ID, memoId)
        startActivity(intent)
    }

    /**
     * Initializes the recycler view to display the list of memos.
     */
    private fun setupRecyclerView(adapter: HomeMemoAdapter) {
        binding.contentHome.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@Home, LinearLayoutManager.VERTICAL, false)
            this.adapter = adapter
            addItemDecoration(DividerItemDecoration(this@Home, (layoutManager as LinearLayoutManager).orientation))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)
        menuItemShowAll = menu.findItem(R.id.action_show_all)
        menuItemShowOpen = menu.findItem(R.id.action_show_open)
        menuItemShowAll.isVisible = !model.isShowingAll
        menuItemShowOpen.isVisible = model.isShowingAll
        return true
    }

    /**
     * Handles actionbar interactions.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_show_all -> {
                model.loadAllMemos()
                //Switch available menu options
                menuItemShowAll.isVisible = false
                menuItemShowOpen.isVisible = true
                true
            }
            R.id.action_show_open -> {
                model.loadOpenMemos()
                //Switch available menu options
                menuItemShowOpen.isVisible = false
                menuItemShowAll.isVisible = true
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private class MonitoringOptions(context: Context) : LinearLayout(context) {
        private val padding = (16 * resources.displayMetrics.density).toInt()
        private val spacing = padding / 2

        init {
            orientation = VERTICAL
            setPadding(padding, spacing, padding, spacing)
        }

        val foreground = requirement("Foreground location (precise)")
        val background = requirement("Background location")
        val services = requirement("Device Location Services")
        val notifications = requirement("Notification permission")
        val error = TextView(context).apply {
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }.also(::addView)

        private fun requirement(label: String): RequirementRow {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val title = TextView(context).apply { text = label }
            val state = TextView(context)
            val fix = AppCompatButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = "Fix"
                contentDescription = "Fix $label"
            }
            row.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(state, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = spacing
            })
            row.addView(fix, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = spacing
            })
            addView(row)
            return RequirementRow(state, fix)
        }
    }

    private class RequirementRow(private val state: TextView, val fix: AppCompatButton) {
        fun render(enabled: Boolean) {
            state.text = if (enabled) "On" else "Off"
            fix.isInvisible = enabled
        }
    }

    private class HomeMemoAdapter(
        private val onMemoClick: View.OnClickListener,
        private val onCompletionChange: CompoundButton.OnCheckedChangeListener
    ) : ListAdapter<Memo, MemoViewHolder>(object : DiffUtil.ItemCallback<Memo>() {
        override fun areItemsTheSame(oldItem: Memo, newItem: Memo) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Memo, newItem: Memo) = oldItem == newItem
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MemoViewHolder(RecyclerviewMemoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: MemoViewHolder, position: Int) {
            holder.update(getItem(position), onMemoClick, onCompletionChange)
            holder.itemView.findViewById<CheckBox>(R.id.checkBox).isEnabled = true
        }
    }

    private companion object {
        const val REQUESTED_PERMISSIONS = "monitoringRequestedPermissions"
        const val MONITORING_DIALOG_OPEN = "monitoringDialogOpen"
    }
}
