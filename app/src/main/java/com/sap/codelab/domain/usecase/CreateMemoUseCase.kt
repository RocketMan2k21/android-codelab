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
    /** Saves the memo and reports any subsequent geofence registration failure separately. */
    suspend operator fun invoke(memo: Memo, hasSelectedLocation: Boolean): Result<Unit> {
        val memoId = withContext(Dispatchers.IO) { repository.saveMemo(memo) }
        if (!hasSelectedLocation) return Result.success(Unit)

        return try {
            geofenceManager.register(memo.copy(id = memoId))
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
