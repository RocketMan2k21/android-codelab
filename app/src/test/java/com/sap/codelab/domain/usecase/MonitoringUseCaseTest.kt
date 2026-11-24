package com.sap.codelab.domain.usecase

import android.util.Log
import com.sap.codelab.data.location.GeofenceNotificationManager
import com.sap.codelab.data.location.LocationPermissionSource
import com.sap.codelab.domain.model.LocationPermission
import com.sap.codelab.domain.model.NotificationAccess
import com.sap.codelab.domain.model.ReminderReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`

class MonitoringUseCaseTest {
    private val repository = InMemoryMemoRepository()
    private val geofences = RecordingGeofenceManager()
    private val permissions = mock(LocationPermissionSource::class.java)
    private val notifications = mock(GeofenceNotificationManager::class.java)
    private var readiness = ReminderReadiness(
        location = LocationPermission(
            isLocationPermissionEnabled = true,
            isPreciseLocationEnabled = true,
            isBackgroundLocationEnabled = true,
            isRequested = true
        ),
        locationServicesEnabled = true,
        notificationAccess = NotificationAccess.ENABLED
    )
    private val monitoring = MonitoringUseCase(repository, geofences, permissions, notifications)

    @Before
    fun stubReminderStatus() {
        `when`(permissions.getPermissionStatus()).thenAnswer { readiness.location }
        `when`(permissions.isLocationEnabled()).thenAnswer { readiness.locationServicesEnabled }
        `when`(notifications.getAccess()).thenAnswer { readiness.notificationAccess }
    }

    @Test
    fun statusIsHiddenWithoutLocationMemos() {
        val withoutLocation = testMemo(id = 1).copy(reminderLatitude = 0.0, reminderLongitude = 0.0)

        for (memos in listOf(emptyList(), listOf(withoutLocation))) {
            val status = monitoring.status(memos)

            assertFalse(status.isVisible)
            assertFalse(status.isActive)
            assertTrue(status.pendingIds.isEmpty())
        }
    }

    @Test
    fun activatesOnlyOpenUnregisteredMemosWithLocations() = runBlocking {
        val alreadyRegistered = testMemo(id = 1).copy(isRegistered = true)
        val completed = testMemo(id = 2).copy(isDone = true)
        val withoutLocation = testMemo(id = 3).copy(reminderLatitude = 0.0, reminderLongitude = 0.0)
        val onEquator = testMemo(id = 4).copy(reminderLatitude = 0.0)
        val onPrimeMeridian = testMemo(id = 5).copy(reminderLongitude = 0.0)
        listOf(alreadyRegistered, completed, withoutLocation, onEquator, onPrimeMeridian)
            .forEach(repository::saveMemo)

        val before = monitoring.status(repository.getOpen())
        assertTrue(before.isVisible)
        assertTrue(before.canActivate)
        assertFalse(before.isActive)
        assertEquals(setOf(onEquator.id, onPrimeMeridian.id), before.pendingIds)

        assertTrue(monitoring.activate().isEmpty())

        assertEquals(setOf(onEquator, onPrimeMeridian), geofences.registered.toSet())
        assertEquals(2, geofences.registered.size)
        assertEquals(
            setOf(
                alreadyRegistered,
                completed,
                withoutLocation,
                onEquator.copy(isRegistered = true),
                onPrimeMeridian.copy(isRegistered = true)
            ),
            repository.getAll().toSet()
        )
        val after = monitoring.status(repository.getOpen())
        assertTrue(after.isActive)
        assertTrue(after.pendingIds.isEmpty())
    }

    @Test
    fun activationSkipsRegistrationWhenAnyRequirementIsMissing() = runBlocking {
        val memo = testMemo(id = 1)
        repository.saveMemo(memo)
        val available = readiness
        val blocked = listOf(
            available.copy(location = available.location.copy(isPreciseLocationEnabled = false)),
            available.copy(location = available.location.copy(isBackgroundLocationEnabled = false)),
            available.copy(locationServicesEnabled = false),
            available.copy(notificationAccess = NotificationAccess.PERMISSION_REQUIRED),
            available.copy(notificationAccess = NotificationAccess.APP_DISABLED),
            available.copy(notificationAccess = NotificationAccess.CHANNEL_DISABLED)
        )

        for (unavailable in blocked) {
            readiness = unavailable

            val status = monitoring.status(repository.getOpen())
            assertEquals(unavailable, status.requirements)
            assertTrue(status.isVisible)
            assertFalse(unavailable.toString(), status.canActivate)
            assertFalse(status.isActive)
            assertEquals(setOf(memo.id), status.pendingIds)
            assertFalse(monitoring.status(listOf(memo.copy(isRegistered = true))).isActive)
            assertTrue(monitoring.activate().isEmpty())
        }

        assertTrue(geofences.registered.isEmpty())
        assertEquals(listOf(memo), repository.getOpen())

        readiness = available
        assertTrue(monitoring.activate().isEmpty())
        assertEquals(listOf(memo), geofences.registered)
    }

    @Test
    fun concurrentActivationsRegisterTheSameMemoOnlyOnce() = runBlocking {
        val memo = testMemo(id = 1)
        repository.saveMemo(memo)
        val registrationStarted = CompletableDeferred<Unit>()
        val finishRegistration = CompletableDeferred<Unit>()
        geofences.onRegister = {
            registrationStarted.complete(Unit)
            finishRegistration.await()
        }

        withTimeout(5_000) {
            val first = async { monitoring.activate() }
            registrationStarted.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { monitoring.activate() }
            finishRegistration.complete(Unit)

            assertTrue(first.await().isEmpty())
            assertTrue(second.await().isEmpty())
        }

        assertEquals(listOf(memo), geofences.registered)
        assertTrue(repository.getMemoById(memo.id).isRegistered)
    }

    @Test
    fun activationReportsFailuresAndRetriesOnlyUnregisteredMemos() = runBlocking {
        val failed = testMemo(id = 1)
        val successful = testMemo(id = 2)
        listOf(failed, successful).forEach(repository::saveMemo)
        val error = SecurityException("Registration temporarily unavailable")
        geofences.onRegister = { if (it.id == failed.id) throw error }

        mockStatic(Log::class.java).use { log ->
            assertEquals(setOf(failed.id), monitoring.activate())
            log.verify { Log.w("MonitoringUseCase", "Could not register geofence for memo ${failed.id}", error) }
        }
        assertFalse(repository.getMemoById(failed.id).isRegistered)
        assertTrue(repository.getMemoById(successful.id).isRegistered)
        assertEquals(setOf(failed.id), monitoring.status(repository.getOpen()).pendingIds)
        assertEquals(setOf(failed, successful), geofences.registered.toSet())
        assertEquals(2, geofences.registered.size)

        geofences.onRegister = {}
        assertTrue(monitoring.activate().isEmpty())

        assertEquals(listOf(failed), geofences.registered.drop(2))
        assertTrue(monitoring.status(repository.getOpen()).isActive)
    }

    @Test
    fun activationCancellationPropagatesWithoutReportingFailure() {
        val memo = testMemo(id = 1)
        repository.saveMemo(memo)
        geofences.onRegister = { throw CancellationException("Activation cancelled") }

        mockStatic(Log::class.java).use { log ->
            assertThrows(CancellationException::class.java) { runBlocking { monitoring.activate() } }
            log.verifyNoInteractions()
        }

        assertEquals(listOf(memo), repository.getOpen())
    }

    @Test
    fun completingMemoRemovesItsGeofenceAndKeepsItOutOfTheOpenList() = runBlocking {
        val target = testMemo(id = 1).copy(isRegistered = true)
        val other = testMemo(id = 2).copy(isRegistered = true)
        listOf(target, other).forEach(repository::saveMemo)

        monitoring.setCompleted(target.id, completed = true)

        assertEquals(listOf(target.id.toString()), geofences.removed)
        assertEquals(listOf(other), repository.getOpen())
        assertEquals(listOf(target.copy(isDone = true, isRegistered = false), other), repository.getAll())
    }

    @Test
    fun completingMemoWithoutLocationDoesNotRemoveAGeofence() = runBlocking {
        val memo = testMemo(id = 1).copy(reminderLatitude = 0.0, reminderLongitude = 0.0)
        repository.saveMemo(memo)

        monitoring.setCompleted(memo.id, completed = true)

        assertTrue(geofences.removed.isEmpty())
        assertTrue(repository.getOpen().isEmpty())
        assertEquals(memo.copy(isDone = true), repository.getMemoById(memo.id))
    }

    @Test
    fun reopeningMemoMakesItEligibleForActivationAgain() = runBlocking {
        val memo = testMemo(id = 1).copy(isDone = true)
        repository.saveMemo(memo)

        monitoring.setCompleted(memo.id, completed = false)

        val reopened = memo.copy(isDone = false)
        assertEquals(listOf(reopened), repository.getOpen())
        assertTrue(geofences.removed.isEmpty())
        assertTrue(geofences.registered.isEmpty())
        assertEquals(setOf(memo.id), monitoring.status(repository.getOpen()).pendingIds)

        assertTrue(monitoring.activate().isEmpty())
        assertEquals(listOf(reopened), geofences.registered)
    }

    @Test
    fun failedRemovalKeepsTheRegistrationFlagUntilRemovalIsRetried() {
        val memo = testMemo(id = 1).copy(isRegistered = true)
        repository.saveMemo(memo)
        val failure = IllegalStateException("Geofence removal failed")
        geofences.onRemove = { throw failure }

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { monitoring.setCompleted(memo.id, completed = true) }
        }

        assertEquals(failure.message, thrown.message)
        assertEquals(memo.copy(isDone = true), repository.getMemoById(memo.id))
        assertTrue(repository.getOpen().isEmpty())

        geofences.onRemove = {}
        runBlocking { monitoring.setCompleted(memo.id, completed = true) }

        assertEquals(listOf(memo.id.toString(), memo.id.toString()), geofences.removed)
        assertEquals(memo.copy(isDone = true, isRegistered = false), repository.getMemoById(memo.id))
    }

    @Test
    fun completingAnUnknownIdDoesNotRemoveAGeofence() {
        assertThrows(NoSuchElementException::class.java) {
            runBlocking { monitoring.setCompleted(memoId = 999, completed = true) }
        }

        assertTrue(geofences.removed.isEmpty())
        assertTrue(repository.getAll().isEmpty())
    }
}
