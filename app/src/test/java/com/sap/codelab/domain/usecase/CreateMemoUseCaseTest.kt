package com.sap.codelab.domain.usecase

import com.sap.codelab.domain.usecase.CreateMemoUseCase.Outcome
import com.sap.codelab.domain.usecase.CreateMemoUseCase.ReminderRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateMemoUseCaseTest {
    private val repository = InMemoryMemoRepository()
    private val geofences = RecordingGeofenceManager()
    private val createMemo = CreateMemoUseCase(repository, geofences)

    @Test
    fun addsMemoWithoutLocation() = runBlocking {
        val memo = testMemo().copy(reminderLatitude = 0.0, reminderLongitude = 0.0)

        assertEquals(Outcome.Saved, createMemo(memo, ReminderRequest.NONE))

        val saved = repository.getOpen().single()
        assertTrue(saved.id > 0)
        assertEquals(memo.copy(id = saved.id), saved)
        assertTrue(geofences.registered.isEmpty())
    }

    @Test
    fun addsLocationMemoWhenMonitoringIsSkipped() = runBlocking {
        val memo = testMemo(id = 21)

        assertEquals(Outcome.MonitoringSkipped, createMemo(memo, ReminderRequest.SKIP))

        assertEquals(listOf(memo), repository.getOpen())
        assertTrue(geofences.registered.isEmpty())
    }

    @Test
    fun registersNewMemoUsingItsStoredIdBeforeMarkingItRegistered() = runBlocking {
        val memo = testMemo()
        geofences.onRegister = { registered ->
            assertTrue(registered.id > 0)
            assertEquals(registered, repository.getMemoById(registered.id))
            assertFalse(registered.isRegistered)
        }

        assertEquals(Outcome.MonitoringActive, createMemo(memo, ReminderRequest.REGISTER))

        val registered = geofences.registered.single()
        assertEquals(memo.copy(id = registered.id), registered)
        assertEquals(listOf(registered.copy(isRegistered = true)), repository.getOpen())
    }

    @Test
    fun savingTheSameIdReplacesTheMemoAndRegistersTheUpdatedLocationWithThatId() = runBlocking {
        val original = testMemo(id = 21)
        val updated = original.copy(title = "Updated reminder", reminderLatitude = 0.0)

        assertEquals(Outcome.MonitoringActive, createMemo(original, ReminderRequest.REGISTER))
        assertEquals(Outcome.MonitoringActive, createMemo(updated, ReminderRequest.REGISTER))

        assertEquals(listOf(updated.copy(isRegistered = true)), repository.getAll())
        assertEquals(listOf(original, updated), geofences.registered)
    }

    @Test
    fun twoUnsavedMemosGetSeparateIds() = runBlocking {
        val memo = testMemo()

        createMemo(memo, ReminderRequest.REGISTER)
        createMemo(memo, ReminderRequest.REGISTER)

        val ids = repository.getAll().map { it.id }
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
        assertEquals(ids, geofences.registered.map { it.id })
    }

    @Test
    fun registrationFailureLeavesTheMemoSavedAndUnregistered() = runBlocking {
        val failure = SecurityException("Location permission was revoked")
        geofences.onRegister = { throw failure }

        val outcome = createMemo(testMemo(), ReminderRequest.REGISTER)

        assertEquals(Outcome.RegistrationFailed(failure), outcome)
        assertEquals(listOf(geofences.registered.single()), repository.getOpen())
        assertFalse(repository.getOpen().single().isRegistered)
    }

    @Test
    fun registrationCancellationPropagatesAndLeavesTheMemoUnregistered() = runBlocking {
        val cancellation = CancellationException("Registration cancelled")
        geofences.onRegister = { throw cancellation }

        val thrown = runCatching { createMemo(testMemo(), ReminderRequest.REGISTER) }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf(geofences.registered.single()), repository.getOpen())
        assertFalse(repository.getOpen().single().isRegistered)
    }

    @Test
    fun saveFailurePropagatesWithoutRegisteringAGeofence() = runBlocking {
        val failure = IllegalStateException("Storage unavailable")
        repository.saveFailure = failure

        val thrown = runCatching { createMemo(testMemo(), ReminderRequest.REGISTER) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertTrue(repository.getAll().isEmpty())
        assertTrue(geofences.registered.isEmpty())
    }
}
