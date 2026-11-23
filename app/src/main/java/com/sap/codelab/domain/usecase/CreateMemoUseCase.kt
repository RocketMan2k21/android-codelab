package com.sap.codelab.domain.usecase

import com.sap.codelab.data.location.GeofenceManager
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.repository.IMemoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CreateMemoUseCase(
    private val repository: IMemoRepository,
    private val geofenceManager: GeofenceManager
) {
    suspend operator fun invoke(memo: Memo) {
        val memoId = withContext(Dispatchers.IO) { repository.saveMemo(memo) }
        geofenceManager.register(memo.copy(id = memoId))
    }
}
