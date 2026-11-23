package com.sap.codelab.domain.usecase

import com.sap.codelab.data.location.GeofenceManager
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.repository.IMemoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CreateMemoUseCase(
    private val repository: IMemoRepository,
    private val geofenceManager: GeofenceManager
) {
    enum class ReminderRequest { NONE, REGISTER, SKIP }

    sealed interface Outcome {
        data object Saved : Outcome
        data object MonitoringActive : Outcome
        data object MonitoringSkipped : Outcome
        data class RegistrationFailed(val error: Exception) : Outcome
    }

    /** Saves the memo before handling any location reminder. */
    suspend operator fun invoke(memo: Memo, reminder: ReminderRequest): Outcome {
        val memoId = withContext(Dispatchers.IO) { repository.saveMemo(memo) }
        return when (reminder) {
            ReminderRequest.NONE -> Outcome.Saved
            ReminderRequest.SKIP -> Outcome.MonitoringSkipped
            ReminderRequest.REGISTER -> registerReminder(memo.copy(id = memoId))
        }
    }

    private suspend fun registerReminder(memo: Memo): Outcome {
        return try {
            geofenceManager.register(memo)
            Outcome.MonitoringActive
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Outcome.RegistrationFailed(error)
        }
    }
}
