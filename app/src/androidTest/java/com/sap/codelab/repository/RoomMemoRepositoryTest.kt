package com.sap.codelab.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sap.codelab.utils.Database
import com.sap.codelab.data.local.repository.RoomMemoRepository
import com.sap.codelab.domain.model.Memo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMemoRepositoryTest {
    @Test
    fun readsAndWritesFromMainWithoutAllowingMainThreadQueries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, Database::class.java).build()
        try {
            val repository = RoomMemoRepository(database)
            withContext(Dispatchers.Main) {
                val memo = Memo(
                    id = 0,
                    title = "Reminder",
                    description = "Pick up the order",
                    reminderDate = 0,
                    reminderLatitude = 42.4,
                    reminderLongitude = 19.3,
                )
                val id = repository.saveMemo(memo)
                assertTrue(id > 0)

                repository.setRegistered(id, true)
                val registered = memo.copy(id = id, isRegistered = true)
                assertEquals(registered, repository.getMemoById(id))
                assertEquals(listOf(registered), repository.getOpen())
                assertEquals(listOf(registered), repository.getOpenByIds(listOf(id)))

                val completed = registered.copy(isDone = true)
                repository.saveMemo(completed)
                assertTrue(repository.getOpenByIds(listOf(id)).isEmpty())
                assertEquals(listOf(completed), repository.getAll())
            }
        } finally {
            database.close()
        }
    }
}
