package com.sap.codelab.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sap.codelab.domain.model.Memo

/**
 * The Dao representation of a Memo.
 */
@Dao
internal interface MemoDao {

    /**
     * @return all memos that are currently in the database.
     */
    @Query("SELECT * FROM memo")
    suspend fun getAll(): List<Memo>

    /**
     * @return all memos that are currently in the database and have not yet been marked as "done".
     */
    @Query("SELECT * FROM memo WHERE isDone = 0")
    suspend fun getOpen(): List<Memo>

    @Query("SELECT * FROM memo WHERE isDone = 0 AND id IN (:memoIds)")
    suspend fun getOpenByIds(memoIds: List<Long>): List<Memo>

    /**
     * Inserts the given Memo into the database. We currently do not support updating of memos.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memo: Memo): Long

    @Query("UPDATE memo SET isRegistered = :isRegistered WHERE id = :memoId")
    suspend fun setRegistered(memoId: Long, isRegistered: Boolean)

    /**
     * @return the memo whose id matches the given id.
     */
    @Query("SELECT * FROM memo WHERE id = :memoId")
    suspend fun getMemoById(memoId: Long): Memo
}
