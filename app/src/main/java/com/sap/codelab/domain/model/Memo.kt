package com.sap.codelab.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a memo.
 */
@Entity(tableName = "memo")
internal data class Memo(
        @ColumnInfo(name = "id")
        @PrimaryKey(autoGenerate = true)
        var id: Long,
        @ColumnInfo(name = "title")
        var title: String,
        @ColumnInfo(name = "description")
        var description: String,
        @ColumnInfo(name = "reminderDate")
        var reminderDate: Long,
        @ColumnInfo(name = "reminderLatitude")
        var reminderLatitude: Double,
        @ColumnInfo(name = "reminderLongitude")
        var reminderLongitude: Double,
        @ColumnInfo(name = "priority")
        val priority: Int = 0,
        @ColumnInfo(name = "isDone")
        var isDone: Boolean = false,
        @ColumnInfo(defaultValue = "0")
        val isRegistered: Boolean = false
)

internal val Memo.hasLocation: Boolean
    get() = reminderLatitude != 0.0 || reminderLongitude != 0.0
