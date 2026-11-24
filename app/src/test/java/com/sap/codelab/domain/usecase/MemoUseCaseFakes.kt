package com.sap.codelab.domain.usecase

import com.sap.codelab.data.location.GeofenceManager
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.repository.IMemoRepository

internal class InMemoryMemoRepository : IMemoRepository {
    private val memos = linkedMapOf<Long, Memo>()
    private var nextId = 1L
    var saveFailure: Exception? = null

    override suspend fun saveMemo(memo: Memo): Long {
        saveFailure?.let { throw it }
        val id = if (memo.id == 0L) nextId else memo.id
        nextId = maxOf(nextId, id + 1)
        memos[id] = memo.copy(id = id)
        return id
    }

    override suspend fun setRegistered(memoId: Long, isRegistered: Boolean) {
        memos[memoId] = memos.getValue(memoId).copy(isRegistered = isRegistered)
    }

    override suspend fun getAll(): List<Memo> = memos.values.map { it.copy() }

    override suspend fun getOpen(): List<Memo> = getAll().filterNot { it.isDone }

    override suspend fun getMemoById(id: Long): Memo = memos.getValue(id).copy()
}

internal class RecordingGeofenceManager : GeofenceManager {
    val registered = mutableListOf<Memo>()
    val removed = mutableListOf<String>()
    var onRegister: suspend (Memo) -> Unit = {}
    var onRemove: suspend (String) -> Unit = {}

    override suspend fun register(memo: Memo) {
        registered.add(memo.copy())
        onRegister(memo)
    }

    override suspend fun remove(reminderId: String) {
        removed.add(reminderId)
        onRemove(reminderId)
    }
}

internal fun testMemo(id: Long = 0L) = Memo(
    id = id,
    title = "Reminder",
    description = "Pick up the order",
    reminderDate = 0L,
    reminderLatitude = 42.4,
    reminderLongitude = 19.3
)
