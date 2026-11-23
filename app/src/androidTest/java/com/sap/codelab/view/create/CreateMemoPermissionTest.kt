package com.sap.codelab.view.create

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sap.codelab.data.location.GeofenceManager
import com.sap.codelab.domain.model.Location
import com.sap.codelab.domain.model.LocationPermission
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.model.NotificationAccess
import com.sap.codelab.domain.model.ReminderReadiness
import com.sap.codelab.domain.usecase.CreateMemoUseCase
import com.sap.codelab.repository.IMemoRepository
import com.sap.codelab.view.create.CreateMemoViewModel.RationaleAction
import com.sap.codelab.view.create.CreateMemoViewModel.SaveState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateMemoPermissionTest {
    @Test
    fun settingsDenialAfterRecreationReturnsToRationaleUntilUserChoosesToSave() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val original = fixture.newViewModel()
        original.selectLocation(Location(0.0, 0.0))
        original.updateMemo("Original title", "Description", 2)
        original.saveMemo(preciseOnly)
        assertEquals(SaveState.BACKGROUND_RATIONALE, original.saveState.value)
        original.onRationaleResult(SaveState.BACKGROUND_RATIONALE, RationaleAction.CONTINUE)
        assertTrue(original.claimPermissionLaunch(SaveState.OPENING_SETTINGS))

        val restoredState = SavedStateHandle(fixture.state.keys().associateWith { key -> fixture.state.get<Any>(key) })
        val restored = fixture.newViewModel(restoredState)
        assertFalse(restored.claimPermissionLaunch(SaveState.OPENING_SETTINGS))
        restored.saveMemo(allPermissions)
        restored.onPermissionResult(SaveState.OPENING_SETTINGS, preciseOnly)
        restored.onPermissionResult(SaveState.OPENING_SETTINGS, allPermissions)
        assertEquals(SaveState.BACKGROUND_RATIONALE, restored.saveState.value)
        assertTrue(fixture.savedMemos.isEmpty())
        assertFalse(restored.claimPermissionLaunch(SaveState.OPENING_SETTINGS))

        restored.onRationaleResult(SaveState.BACKGROUND_RATIONALE, RationaleAction.SKIP)
        restored.awaitSave(SaveState.SAVED_WITHOUT_REMINDER)
        assertEquals("Original title", fixture.savedMemos.single().title)
        assertEquals(2, fixture.savedMemos.single().priority)
        assertTrue(fixture.registeredMemos.isEmpty())
    }

    @Test
    fun settingsCanBeRetriedAfterApproximateAccess() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val model = fixture.newViewModel()
        model.selectLocation(Location(42.0, 19.0))
        model.updateMemo("Title", "Description", 1)
        model.saveMemo(preciseOnly)
        model.onRationaleResult(SaveState.BACKGROUND_RATIONALE, RationaleAction.CONTINUE)
        assertTrue(model.claimPermissionLaunch(SaveState.OPENING_SETTINGS))
        model.onPermissionResult(
            SaveState.OPENING_SETTINGS,
            allPermissions.copy(location = allPermissions.location.copy(isPreciseLocationEnabled = false))
        )
        assertEquals(SaveState.BACKGROUND_RATIONALE, model.saveState.value)
        assertTrue(fixture.savedMemos.isEmpty())

        model.onRationaleResult(SaveState.BACKGROUND_RATIONALE, RationaleAction.CONTINUE)
        assertTrue(model.claimPermissionLaunch(SaveState.OPENING_SETTINGS))
        model.onPermissionResult(SaveState.OPENING_SETTINGS, allPermissions)

        model.awaitSave(SaveState.SAVED_WITH_REMINDER)
        assertEquals(fixture.savedMemos.single().copy(id = MEMO_ID), fixture.registeredMemos.single())
    }

    @Test
    fun android10RequestsForegroundThenBackgroundAndRegistersSavedMemo() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.Q)
        val model = fixture.newViewModel()
        model.selectLocation(Location(42.0, 19.0))
        model.updateMemo("Title", "Description", 1)
        model.saveMemo(preciseOnly.copy(location = preciseOnly.location.copy(isPreciseLocationEnabled = false)))
        assertEquals(SaveState.FOREGROUND_RATIONALE, model.saveState.value)
        model.onRationaleResult(SaveState.FOREGROUND_RATIONALE, RationaleAction.CONTINUE)
        assertEquals(SaveState.REQUESTING_FOREGROUND, model.saveState.value)
        assertTrue(model.claimPermissionLaunch(SaveState.REQUESTING_FOREGROUND))
        model.onPermissionResult(SaveState.REQUESTING_FOREGROUND, preciseOnly)
        assertEquals(SaveState.REQUESTING_BACKGROUND, model.saveState.value)
        assertTrue(model.claimPermissionLaunch(SaveState.REQUESTING_BACKGROUND))
        model.onPermissionResult(SaveState.REQUESTING_BACKGROUND, allPermissions)

        model.awaitSave(SaveState.SAVED_WITH_REMINDER)
        assertEquals(fixture.savedMemos.single().copy(id = MEMO_ID), fixture.registeredMemos.single())
    }

    @Test
    fun memoWithoutLocationUsesPlainSaveState() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val model = fixture.newViewModel()
        model.updateMemo("Title", "Description", 1)
        model.saveMemo(allPermissions)

        model.awaitSave(SaveState.SAVED)
        assertEquals(1, fixture.savedMemos.size)
        assertTrue(fixture.registeredMemos.isEmpty())
    }

    @Test
    fun android10BackgroundDenialSavesWithoutRegistering() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.Q)
        val model = fixture.newViewModel()
        model.selectLocation(Location(42.0, 19.0))
        model.updateMemo("Title", "Description", 1)
        model.saveMemo(preciseOnly)
        assertEquals(SaveState.REQUESTING_BACKGROUND, model.saveState.value)
        assertTrue(model.claimPermissionLaunch(SaveState.REQUESTING_BACKGROUND))
        model.onPermissionResult(SaveState.REQUESTING_BACKGROUND, preciseOnly)

        model.awaitSave(SaveState.SAVED_WITHOUT_REMINDER)
        assertEquals(1, fixture.savedMemos.size)
        assertTrue(fixture.registeredMemos.isEmpty())
    }

    @Test
    fun decliningRationalePreservesTheSelectedLocation() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.R)
        val model = fixture.newViewModel()
        val location = Location(42.123456789, -19.123456789)
        model.selectLocation(location)
        model.updateMemo("Title", "Description", 1)
        model.saveMemo(preciseOnly)
        model.onRationaleResult(SaveState.BACKGROUND_RATIONALE, RationaleAction.SKIP)

        model.awaitSave(SaveState.SAVED_WITHOUT_REMINDER)
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
        model.continueFrom(SaveState.FOREGROUND_RATIONALE, SaveState.REQUESTING_FOREGROUND)
        model.onPermissionResult(SaveState.REQUESTING_FOREGROUND, foregroundOnly)

        model.continueFrom(SaveState.BACKGROUND_RATIONALE, SaveState.OPENING_SETTINGS)
        val locationOff = foregroundOnly.copy(location = allPermissions.location)
        model.onPermissionResult(SaveState.OPENING_SETTINGS, locationOff)

        model.continueFrom(SaveState.LOCATION_SERVICES_RATIONALE, SaveState.OPENING_LOCATION_SERVICES)
        model.onPermissionResult(SaveState.OPENING_LOCATION_SERVICES, locationOff)
        assertEquals(SaveState.LOCATION_SERVICES_RATIONALE, model.saveState.value)
        assertFalse(model.claimPermissionLaunch(SaveState.OPENING_LOCATION_SERVICES))
        assertTrue(fixture.savedMemos.isEmpty())

        model.continueFrom(SaveState.LOCATION_SERVICES_RATIONALE, SaveState.OPENING_LOCATION_SERVICES)
        model.onPermissionResult(SaveState.OPENING_LOCATION_SERVICES, locationOff.copy(locationServicesEnabled = true))
        model.continueFrom(SaveState.NOTIFICATION_PERMISSION_RATIONALE, SaveState.REQUESTING_NOTIFICATIONS)
        model.onPermissionResult(SaveState.REQUESTING_NOTIFICATIONS, allPermissions)

        model.awaitSave(SaveState.SAVED_WITH_REMINDER)
        assertEquals(fixture.savedMemos.single().copy(id = MEMO_ID), fixture.registeredMemos.single())
    }

    @Test
    fun foregroundAndNotificationDenialsStopBeforeRegistration() = onMain {
        val fixture = Fixture(Build.VERSION_CODES.TIRAMISU)
        val model = fixture.newViewModelWithLocation()
        val approximate = preciseOnly.copy(location = preciseOnly.location.copy(isPreciseLocationEnabled = false))
        model.saveMemo(approximate)
        model.continueFrom(SaveState.FOREGROUND_RATIONALE, SaveState.REQUESTING_FOREGROUND)
        model.onPermissionResult(SaveState.REQUESTING_FOREGROUND, approximate)
        model.awaitSave(SaveState.SAVED_WITHOUT_REMINDER)
        assertEquals(1, fixture.savedMemos.size)
        assertTrue(fixture.registeredMemos.isEmpty())

        val notificationFixture = Fixture(Build.VERSION_CODES.TIRAMISU)
        val notificationModel = notificationFixture.newViewModelWithLocation()
        val missingPermission = allPermissions.copy(notificationAccess = NotificationAccess.PERMISSION_REQUIRED)
        notificationModel.saveMemo(missingPermission)
        notificationModel.continueFrom(SaveState.NOTIFICATION_PERMISSION_RATIONALE, SaveState.REQUESTING_NOTIFICATIONS)
        notificationModel.onPermissionResult(SaveState.REQUESTING_NOTIFICATIONS, missingPermission)
        notificationModel.awaitSave(SaveState.SAVED_WITHOUT_REMINDER)
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
        assertEquals(SaveState.LOCATION_SERVICES_RATIONALE, model.saveState.value)
        model.onRationaleResult(SaveState.LOCATION_SERVICES_RATIONALE, RationaleAction.SKIP)
        model.awaitSave(SaveState.SAVED_WITHOUT_REMINDER)
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
        model.continueFrom(SaveState.APP_NOTIFICATIONS_RATIONALE, SaveState.OPENING_APP_NOTIFICATIONS)
        model.onPermissionResult(SaveState.OPENING_APP_NOTIFICATIONS, channelBlocked)
        model.continueFrom(SaveState.CHANNEL_NOTIFICATIONS_RATIONALE, SaveState.OPENING_CHANNEL_NOTIFICATIONS)
        model.onPermissionResult(SaveState.OPENING_CHANNEL_NOTIFICATIONS, channelBlocked)
        assertEquals(SaveState.CHANNEL_NOTIFICATIONS_RATIONALE, model.saveState.value)
        assertTrue(fixture.savedMemos.isEmpty())

        model.continueFrom(SaveState.CHANNEL_NOTIFICATIONS_RATIONALE, SaveState.OPENING_CHANNEL_NOTIFICATIONS)
        model.onPermissionResult(SaveState.OPENING_CHANNEL_NOTIFICATIONS, allPermissions)
        model.awaitSave(SaveState.SAVED_WITH_REMINDER)
        assertEquals(fixture.savedMemos.single().copy(id = MEMO_ID), fixture.registeredMemos.single())
    }

    private fun CreateMemoViewModel.continueFrom(rationale: SaveState, launch: SaveState) {
        assertEquals(rationale, saveState.value)
        onRationaleResult(rationale, RationaleAction.CONTINUE)
        assertTrue(claimPermissionLaunch(launch))
    }

    private fun onMain(test: suspend () -> Unit) {
        runBlocking { withContext(Dispatchers.Main) { test() } }
    }

    private suspend fun CreateMemoViewModel.awaitSave(expected: SaveState) {
        withTimeout(5_000) { saveState.first { it == expected } }
    }

    private class Fixture(private val sdkInt: Int) {
        val state = SavedStateHandle()
        val savedMemos = mutableListOf<Memo>()
        val registeredMemos = mutableListOf<Memo>()

        private val repository = object : IMemoRepository {
            override fun saveMemo(memo: Memo): Long {
                savedMemos.add(memo)
                return MEMO_ID
            }

            override fun getAll(): List<Memo> = error("Unexpected query")
            override fun getOpen(): List<Memo> = error("Unexpected query")
            override fun getMemoById(id: Long): Memo = error("Unexpected query")
        }

        private val geofenceManager = object : GeofenceManager {
            override suspend fun register(memo: Memo) {
                assertEquals(1, savedMemos.size)
                registeredMemos.add(memo)
            }

            override suspend fun remove(reminderId: String) = error("Unexpected removal")
        }

        fun newViewModel(savedState: SavedStateHandle = state) =
            CreateMemoViewModel(savedState, CreateMemoUseCase(repository, geofenceManager), sdkInt)

        fun newViewModelWithLocation() = newViewModel().apply {
            selectLocation(Location(42.0, 19.0))
            updateMemo("Title", "Description", 1)
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
