package com.sap.codelab.utils

import androidx.room.Database
import androidx.room.RoomDatabase
import com.sap.codelab.data.local.dao.MemoDao
import com.sap.codelab.domain.model.Memo

/**
 * That database that is used to store information.
 */
@Database(entities = [Memo::class], version = 5, exportSchema = false)
internal abstract class Database : RoomDatabase() {

    abstract fun getMemoDao(): MemoDao
}
