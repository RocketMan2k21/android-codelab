package com.sap.codelab.domain.usecase

import com.sap.codelab.domain.GeofenceManager
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.MemoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

internal class CreateMemoUseCase(
    private val repository: MemoRepository,
    private val geofenceManager: GeofenceManager
) {
    enum class ReminderRequest { NONE, REGISTER, SKIP }

    sealed interface Outcome {
        data object Saved : Outcome
        data object MonitoringActive : Outcome
        data object MonitoringSkipped : Outcome

        /** The memo was saved before the reminder setup failed. */
        data class RegistrationFailed(val error: Exception) : Outcome
    }

    /** Saves the memo before handling any location reminder. */
    suspend operator fun invoke(memo: Memo, reminder: ReminderRequest): Outcome = memoGeofenceChanges.withLock {
        val memoId = repository.saveMemo(memo)
        when (reminder) {
            ReminderRequest.NONE -> Outcome.Saved
            ReminderRequest.SKIP -> Outcome.MonitoringSkipped
            ReminderRequest.REGISTER -> registerReminder(memo.copy(id = memoId))
        }
    }

    private suspend fun registerReminder(memo: Memo): Outcome {
        return try {
            geofenceManager.register(memo)

            repository.setRegistered(memo.id, true)
            Outcome.MonitoringActive
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Outcome.RegistrationFailed(error)
        }
    }
}
