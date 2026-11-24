package com.sap.codelab.domain

import com.sap.codelab.domain.model.Memo

/**
 * Interface for a repository offering memo related CRUD operations.
 */
internal interface MemoRepository {

    /**
     * Saves the given memo to the database and returns its ID.
     */
    suspend fun saveMemo(memo: Memo): Long

    suspend fun setRegistered(memoId: Long, isRegistered: Boolean)

    /**
     * @return all memos currently in the database.
     */
    suspend fun getAll(): List<Memo>

    /**
     * @return all memos currently in the database, except those that have been marked as "done".
     */
    suspend fun getOpen(): List<Memo>

    /**
     * @return the memo whose id matches the given id.
     */
    suspend fun getMemoById(id: Long): Memo
}
