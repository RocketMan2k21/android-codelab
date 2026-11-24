package com.sap.codelab.repository

import com.sap.codelab.domain.model.Memo

/** Provides access to memos stored in the supplied Room database. */
internal class RoomMemoRepository(database: Database) : IMemoRepository {
    private val dao = database.getMemoDao()

    override suspend fun saveMemo(memo: Memo): Long = dao.insert(memo)

    override suspend fun setRegistered(memoId: Long, isRegistered: Boolean) =
        dao.setRegistered(memoId, isRegistered)

    override suspend fun getOpen(): List<Memo> = dao.getOpen()

    suspend fun getOpenByIds(memoIds: List<Long>): List<Memo> =
        dao.getOpenByIds(memoIds)

    override suspend fun getAll(): List<Memo> = dao.getAll()

    override suspend fun getMemoById(id: Long): Memo = dao.getMemoById(id)
}
