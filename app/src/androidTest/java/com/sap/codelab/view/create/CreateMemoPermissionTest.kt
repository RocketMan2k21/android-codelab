package com.sap.codelab.view.create

import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Spinner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sap.codelab.R
import com.sap.codelab.data.location.GeofenceManager
import com.sap.codelab.domain.model.Location
import com.sap.codelab.domain.model.LocationPermission
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.model.NotificationAccess
import com.sap.codelab.domain.model.ReminderReadiness
import com.sap.codelab.domain.usecase.CreateMemoUseCase
import com.sap.codelab.repository.IMemoRepository
import com.sap.codelab.view.create.CreateMemoViewModel.SaveResult
import com.sap.codelab.view.create.CreateMemoViewModel.SaveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateMemoPermissionTest {
    @Test
    fun activityRecreationKeepsFormAndCurrentRationale() {
        ActivityScenario.launch(CreateMemo::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.memo_title).setText("Title")
                activity.findViewById<EditText>(R.id.memo_description).setText("Description")
                activity.findViewById<Spinner>(R.id.memo_priority).setSelection(0)
            }
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[CreateMemoViewModel::class.java]
                model.selectLocation(Location(42.0, 19.0))
                model.saveMemo(preciseOnly.copy(location = preciseOnly.location.copy(
                    isPreciseLocationEnabled = false
                )))
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                assertEquals("Title", activity.findViewById<EditText>(R.id.memo_title).text.toString())
                assertEquals("Description", activity.findViewById<EditText>(R.id.memo_description).text.toString())
                assertEquals(0, activity.findViewById<Spinner>(R.id.memo_priority).selectedItemPosition)
                val dialog = activity.supportFragmentManager.fragments.filterIsInstance<ReminderRequirementDialog>()
                    .single()
                assertEquals(ReminderRequirement.PRECISE_LOCATION, dialog.requirement)
            }
        }
    }

    @Test
    fun activityDismissesRestoredDialogWhenThereIsNoActiveSave() {
        ActivityScenario.launch(CreateMemo::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ReminderRequirementDialog.newInstance(
                    ReminderRequirement.BACKGROUND_LOCATION,
                    R.string.background_location_settings_title,
                    "Background location",
                ).showNow(activity.supportFragmentManager, ReminderRequirementDialog.TAG)
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val dialogs = activity.supportFragmentManager.fragments.filterIsInstance<ReminderRequirementDialog>()
                assertTrue(dialogs.isEmpty())
            }
        }
    }

    @Test
    fun processRecreationRestoresOnlyTheDraftAndRequiresNewSave() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val original = fixture.newViewModel()
        val draft = MemoDraft("Original title", "Description", 2, Location(0.0, 0.0))
        original.selectLocation(checkNotNull(draft.location))
        original.updateDraft(draft.title, draft.description, draft.priority)
        original.saveMemo(preciseOnly)
        original.continueFrom(ReminderRequirement.BACKGROUND_LOCATION, ReminderSystemAction.OPEN_LOCATION_SETTINGS)

        assertEquals(setOf("memoDraft"), fixture.state.keys())
        val savedDraft = checkNotNull(fixture.state.get<Bundle>("memoDraft"))
        assertEquals(setOf("title", "description", "priority", "location"), savedDraft.keySet())
        val restored = fixture.newViewModel(SavedStateHandle(mapOf("memoDraft" to savedDraft)))

        assertEquals(draft, restored.draft)
        assertEquals(SaveState.Ready, restored.saveState.value)
        assertFalse(restored.claimSystemLaunch(ReminderSystemAction.OPEN_LOCATION_SETTINGS))
        restored.onSystemResult(ReminderSystemAction.OPEN_LOCATION_SETTINGS, allPermissions)
        restored.onRationaleResult(ReminderRequirement.BACKGROUND_LOCATION, RationaleAction.SKIP)
        assertEquals(SaveState.Ready, restored.saveState.value)
        assertTrue(fixture.savedMemos.isEmpty())

        restored.saveMemo(preciseOnly)
        restored.assertRationale(ReminderRequirement.BACKGROUND_LOCATION)
        restored.onRationaleResult(ReminderRequirement.BACKGROUND_LOCATION, RationaleAction.SKIP)
        restored.awaitSave(SaveState.Saved(SaveResult.WITHOUT_REMINDER))
        assertEquals(draft.toMemo(), fixture.savedMemos.single())
        assertTrue(fixture.registeredMemos.isEmpty())
    }

    @Test
    fun retainedViewModelDoesNotRelaunchSettingsAndProcessesItsResult() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val model = fixture.newViewModelWithLocation()
        model.saveMemo(preciseOnly)
        model.continueFrom(ReminderRequirement.BACKGROUND_LOCATION, ReminderSystemAction.OPEN_LOCATION_SETTINGS)

        val reattachedState = model.saveState.first()
        assertEquals(
            SaveState.AwaitingReminder(ReminderStep.AwaitSystemResult(
                ReminderSystemAction.OPEN_LOCATION_SETTINGS, launched = true
            )),
            reattachedState
        )
        assertFalse(model.claimSystemLaunch(ReminderSystemAction.OPEN_LOCATION_SETTINGS))
        model.onSystemResult(ReminderSystemAction.OPEN_LOCATION_SERVICES, allPermissions)
        assertSame(reattachedState, model.saveState.value)

        model.onSystemResult(ReminderSystemAction.OPEN_LOCATION_SETTINGS, allPermissions)
        model.awaitSave(SaveState.Saved(SaveResult.WITH_REMINDER))
        fixture.assertRegisteredMemo()
    }

    @Test
    fun settingsCanBeRetriedAfterApproximateAccess() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val model = fixture.newViewModel()
        model.selectLocation(Location(42.0, 19.0))
        model.updateDraft("Title", "Description", 1)
        model.saveMemo(preciseOnly)
        model.onRationaleResult(ReminderRequirement.BACKGROUND_LOCATION, RationaleAction.CONTINUE)
        assertTrue(model.claimSystemLaunch(ReminderSystemAction.OPEN_LOCATION_SETTINGS))
        model.onSystemResult(
            ReminderSystemAction.OPEN_LOCATION_SETTINGS,
            allPermissions.copy(location = allPermissions.location.copy(isPreciseLocationEnabled = false))
        )
        model.assertRationale(ReminderRequirement.BACKGROUND_LOCATION)
        assertTrue(fixture.savedMemos.isEmpty())

        model.onRationaleResult(ReminderRequirement.BACKGROUND_LOCATION, RationaleAction.CONTINUE)
        assertTrue(model.claimSystemLaunch(ReminderSystemAction.OPEN_LOCATION_SETTINGS))
        model.onSystemResult(ReminderSystemAction.OPEN_LOCATION_SETTINGS, allPermissions)

        model.awaitSave(SaveState.Saved(SaveResult.WITH_REMINDER))
        fixture.assertRegisteredMemo()
    }

    @Test
    fun android10RequestsForegroundThenBackgroundAndRegistersSavedMemo() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.Q)
        val model = fixture.newViewModel()
        model.selectLocation(Location(42.0, 19.0))
        model.updateDraft("Title", "Description", 1)
        model.saveMemo(preciseOnly.copy(location = preciseOnly.location.copy(isPreciseLocationEnabled = false)))
        model.assertRationale(ReminderRequirement.PRECISE_LOCATION)
        model.onRationaleResult(ReminderRequirement.PRECISE_LOCATION, RationaleAction.CONTINUE)
        model.assertSystemAction(ReminderSystemAction.REQUEST_FOREGROUND_LOCATION)
        assertTrue(model.claimSystemLaunch(ReminderSystemAction.REQUEST_FOREGROUND_LOCATION))
        model.onSystemResult(ReminderSystemAction.REQUEST_FOREGROUND_LOCATION, preciseOnly)
        model.assertSystemAction(ReminderSystemAction.REQUEST_BACKGROUND_LOCATION)
        assertTrue(model.claimSystemLaunch(ReminderSystemAction.REQUEST_BACKGROUND_LOCATION))
        model.onSystemResult(ReminderSystemAction.REQUEST_BACKGROUND_LOCATION, allPermissions)

        model.awaitSave(SaveState.Saved(SaveResult.WITH_REMINDER))
        fixture.assertRegisteredMemo()
    }

    @Test
    fun memoWithoutLocationUsesPlainSaveState() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val model = fixture.newViewModel()
        model.updateDraft("Title", "Description", 1)
        model.saveMemo(allPermissions)

        model.awaitSave(SaveState.Saved(SaveResult.SAVED))
        assertEquals(1, fixture.savedMemos.size)
        assertTrue(fixture.registeredMemos.isEmpty())
    }

    @Test
    fun android10BackgroundDenialSavesWithoutRegistering() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.Q)
        val model = fixture.newViewModel()
        model.selectLocation(Location(42.0, 19.0))
        model.updateDraft("Title", "Description", 1)
        model.saveMemo(preciseOnly)
        model.assertSystemAction(ReminderSystemAction.REQUEST_BACKGROUND_LOCATION)
        assertTrue(model.claimSystemLaunch(ReminderSystemAction.REQUEST_BACKGROUND_LOCATION))
        model.onSystemResult(ReminderSystemAction.REQUEST_BACKGROUND_LOCATION, preciseOnly)

        model.awaitSave(SaveState.Saved(SaveResult.WITHOUT_REMINDER))
        assertEquals(1, fixture.savedMemos.size)
        assertTrue(fixture.registeredMemos.isEmpty())
    }

    @Test
    fun decliningRationalePreservesTheSelectedLocation() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val model = fixture.newViewModel()
        val location = Location(42.123456789, -19.123456789)
        model.selectLocation(location)
        model.updateDraft("Title", "Description", 1)
        model.saveMemo(preciseOnly)
        model.onRationaleResult(ReminderRequirement.BACKGROUND_LOCATION, RationaleAction.SKIP)

        model.awaitSave(SaveState.Saved(SaveResult.WITHOUT_REMINDER))
        assertEquals(location.lat, fixture.savedMemos.single().reminderLatitude, 0.0)
        assertEquals(location.lon, fixture.savedMemos.single().reminderLongitude, 0.0)
        assertTrue(fixture.registeredMemos.isEmpty())
    }

    @Test
    fun walksThroughAllRequirementsBeforeRegistering() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.TIRAMISU)
        val model = fixture.newViewModelWithLocation()
        val foregroundOnly = preciseOnly.copy(
            locationServicesEnabled = false,
            notificationAccess = NotificationAccess.PERMISSION_REQUIRED
        )
        val noLocationGrant = foregroundOnly.copy(location = foregroundOnly.location.copy(
            isLocationPermissionEnabled = false,
            isPreciseLocationEnabled = false
        ))
        model.saveMemo(noLocationGrant)
        model.continueFrom(ReminderRequirement.PRECISE_LOCATION, ReminderSystemAction.REQUEST_FOREGROUND_LOCATION)
        model.onSystemResult(ReminderSystemAction.REQUEST_FOREGROUND_LOCATION, foregroundOnly)

        model.continueFrom(ReminderRequirement.BACKGROUND_LOCATION, ReminderSystemAction.OPEN_LOCATION_SETTINGS)
        val locationOff = foregroundOnly.copy(location = allPermissions.location)
        model.onSystemResult(ReminderSystemAction.OPEN_LOCATION_SETTINGS, locationOff)

        model.continueFrom(ReminderRequirement.LOCATION_SERVICES, ReminderSystemAction.OPEN_LOCATION_SERVICES)
        model.onSystemResult(ReminderSystemAction.OPEN_LOCATION_SERVICES, locationOff)
        model.assertRationale(ReminderRequirement.LOCATION_SERVICES)
        assertFalse(model.claimSystemLaunch(ReminderSystemAction.OPEN_LOCATION_SERVICES))
        assertTrue(fixture.savedMemos.isEmpty())

        model.continueFrom(ReminderRequirement.LOCATION_SERVICES, ReminderSystemAction.OPEN_LOCATION_SERVICES)
        model.onSystemResult(ReminderSystemAction.OPEN_LOCATION_SERVICES, locationOff.copy(locationServicesEnabled = true))
        model.continueFrom(ReminderRequirement.NOTIFICATION_PERMISSION, ReminderSystemAction.REQUEST_NOTIFICATIONS)
        model.onSystemResult(ReminderSystemAction.REQUEST_NOTIFICATIONS, allPermissions)

        model.awaitSave(SaveState.Saved(SaveResult.WITH_REMINDER))
        fixture.assertRegisteredMemo()
    }

    @Test
    fun foregroundAndNotificationDenialsStopBeforeRegistration() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.TIRAMISU)
        val model = fixture.newViewModelWithLocation()
        val approximate = preciseOnly.copy(location = preciseOnly.location.copy(isPreciseLocationEnabled = false))
        model.saveMemo(approximate)
        model.continueFrom(ReminderRequirement.PRECISE_LOCATION, ReminderSystemAction.REQUEST_FOREGROUND_LOCATION)
        model.onSystemResult(ReminderSystemAction.REQUEST_FOREGROUND_LOCATION, approximate)
        model.awaitSave(SaveState.Saved(SaveResult.WITHOUT_REMINDER))
        assertEquals(1, fixture.savedMemos.size)
        assertTrue(fixture.registeredMemos.isEmpty())

        val notificationFixture = Fixture(Build.VERSION_CODES.TIRAMISU)
        val notificationModel = notificationFixture.newViewModelWithLocation()
        val missingPermission = allPermissions.copy(notificationAccess = NotificationAccess.PERMISSION_REQUIRED)
        notificationModel.saveMemo(missingPermission)
        notificationModel.continueFrom(ReminderRequirement.NOTIFICATION_PERMISSION, ReminderSystemAction.REQUEST_NOTIFICATIONS)
        notificationModel.onSystemResult(ReminderSystemAction.REQUEST_NOTIFICATIONS, missingPermission)
        notificationModel.awaitSave(SaveState.Saved(SaveResult.WITHOUT_REMINDER))
        assertEquals(1, notificationFixture.savedMemos.size)
        assertTrue(notificationFixture.registeredMemos.isEmpty())
    }

    @Test
    fun skippingDisabledLocationServicesStopsBeforeRequestingNotifications() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.TIRAMISU)
        val model = fixture.newViewModelWithLocation()
        model.saveMemo(allPermissions.copy(
            locationServicesEnabled = false,
            notificationAccess = NotificationAccess.PERMISSION_REQUIRED
        ))
        model.assertRationale(ReminderRequirement.LOCATION_SERVICES)
        model.onRationaleResult(ReminderRequirement.LOCATION_SERVICES, RationaleAction.SKIP)
        model.awaitSave(SaveState.Saved(SaveResult.WITHOUT_REMINDER))
        assertEquals(1, fixture.savedMemos.size)
        assertTrue(fixture.registeredMemos.isEmpty())
    }

    @Test
    fun blockedAppAndChannelUseNotificationSettingsWithoutRuntimeRequest() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.S_V2)
        val model = fixture.newViewModelWithLocation()
        val appBlocked = allPermissions.copy(notificationAccess = NotificationAccess.APP_DISABLED)
        val channelBlocked = appBlocked.copy(notificationAccess = NotificationAccess.CHANNEL_DISABLED)
        model.saveMemo(appBlocked)
        model.continueFrom(ReminderRequirement.APP_NOTIFICATIONS, ReminderSystemAction.OPEN_APP_NOTIFICATIONS)
        model.onSystemResult(ReminderSystemAction.OPEN_APP_NOTIFICATIONS, channelBlocked)
        model.continueFrom(ReminderRequirement.CHANNEL_NOTIFICATIONS, ReminderSystemAction.OPEN_CHANNEL_NOTIFICATIONS)
        model.onSystemResult(ReminderSystemAction.OPEN_CHANNEL_NOTIFICATIONS, channelBlocked)
        model.assertRationale(ReminderRequirement.CHANNEL_NOTIFICATIONS)
        assertTrue(fixture.savedMemos.isEmpty())

        model.continueFrom(ReminderRequirement.CHANNEL_NOTIFICATIONS, ReminderSystemAction.OPEN_CHANNEL_NOTIFICATIONS)
        model.onSystemResult(ReminderSystemAction.OPEN_CHANNEL_NOTIFICATIONS, allPermissions)
        model.awaitSave(SaveState.Saved(SaveResult.WITH_REMINDER))
        fixture.assertRegisteredMemo()
    }

    @Test
    fun writeFailureKeepsDraftForRetryAndSuccessClearsIt() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val model = fixture.newViewModel()
        model.updateDraft("Title", "Description", 2)
        fixture.failSave = true
        model.saveMemo(preciseOnly)

        model.awaitSave(SaveState.Failed)
        assertEquals(MemoDraft("Title", "Description", 2), model.draft)
        assertEquals(setOf("memoDraft"), fixture.state.keys())

        fixture.failSave = false
        model.acknowledgeSaveFailure()
        model.saveMemo(preciseOnly)
        model.awaitSave(SaveState.Saved(SaveResult.SAVED))
        assertEquals(model.draft.toMemo(), fixture.savedMemos.single())
        assertTrue(fixture.state.keys().isEmpty())
    }

    private fun CreateMemoViewModel.continueFrom(
        requirement: ReminderRequirement,
        action: ReminderSystemAction,
    ) {
        assertRationale(requirement)
        onRationaleResult(requirement, RationaleAction.CONTINUE)
        assertSystemAction(action)
        assertTrue(claimSystemLaunch(action))
    }

    private fun CreateMemoViewModel.assertRationale(requirement: ReminderRequirement) {
        assertEquals(SaveState.AwaitingReminder(ReminderStep.ShowRationale(requirement)), saveState.value)
    }

    private fun CreateMemoViewModel.assertSystemAction(action: ReminderSystemAction) {
        assertEquals(SaveState.AwaitingReminder(ReminderStep.AwaitSystemResult(action)), saveState.value)
    }

    private fun onMain(test: suspend () -> Unit) {
        runBlocking { withContext(Dispatchers.Main) { test() } }
    }

    private suspend fun CreateMemoViewModel.awaitSave(expected: SaveState) {
        withTimeout(5_000) { saveState.first { it == expected } }
    }

    private class Fixture(private val sdkInt: Int) {
        val state = SavedStateHandle()
        var failSave = false
        val savedMemos = mutableListOf<Memo>()
        val registeredMemos = mutableListOf<Memo>()

        private val repository = object : IMemoRepository {
            override suspend fun saveMemo(memo: Memo): Long {
                check(!failSave) { "Write failed" }
                savedMemos.add(memo)
                return MEMO_ID
            }

            override suspend fun setRegistered(memoId: Long, isRegistered: Boolean) {
                assertEquals(MEMO_ID, memoId)
                savedMemos[0] = savedMemos.single().copy(isRegistered = isRegistered)
            }

            override suspend fun getAll(): List<Memo> = error("Unexpected query")
            override suspend fun getOpen(): List<Memo> = error("Unexpected query")
            override suspend fun getMemoById(id: Long): Memo = error("Unexpected query")
        }

        private val geofenceManager = object : GeofenceManager {
            override suspend fun register(memo: Memo) {
                assertEquals(1, savedMemos.size)
                registeredMemos.add(memo)
            }

            override suspend fun remove(reminderId: String) = error("Unexpected removal")
        }

        fun assertRegisteredMemo() {
            val savedMemo = savedMemos.single()
            assertTrue(savedMemo.isRegistered)
            assertEquals(savedMemo.copy(id = MEMO_ID, isRegistered = false), registeredMemos.single())
        }

        fun newViewModel(savedState: SavedStateHandle = state) =
            CreateMemoViewModel(savedState, CreateMemoUseCase(repository, geofenceManager), sdkInt)

        fun newViewModelWithLocation() = newViewModel().apply {
            selectLocation(Location(42.0, 19.0))
            updateDraft("Title", "Description", 1)
        }
    }

    private companion object {
        const val MEMO_ID = 42L
        val preciseOnly = ReminderReadiness(
            location = LocationPermission(
                isLocationPermissionEnabled = true,
                isPreciseLocationEnabled = true,
                isBackgroundLocationEnabled = false,
                isRequested = true
            ),
            locationServicesEnabled = true,
            notificationAccess = NotificationAccess.ENABLED
        )
        val allPermissions = preciseOnly.copy(location = preciseOnly.location.copy(isBackgroundLocationEnabled = true))
    }
}
