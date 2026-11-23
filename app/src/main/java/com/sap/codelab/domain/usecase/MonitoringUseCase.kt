package com.sap.codelab.domain.usecase

import android.util.Log
import com.sap.codelab.data.location.GeofenceManager
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.model.NotificationAccess
import com.sap.codelab.domain.model.ReminderReadiness
import com.sap.codelab.repository.IMemoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Memo.hasLocation: Boolean
    get() = reminderLatitude != 0.0 || reminderLongitude != 0.0

internal val memoGeofenceChanges = Mutex()

internal class MonitoringUseCase(
    private val repository: IMemoRepository,
    private val geofenceManager: GeofenceManager,
    val readRequirements: () -> ReminderReadiness
) {
    data class Status(val isVisible: Boolean, val requirements: ReminderReadiness, val pendingIds: Set<Long>) {
        val canActivate: Boolean
            get() = requirements.canMonitor

        val isActive: Boolean
            get() = isVisible && canActivate && pendingIds.isEmpty()
    }

    fun status(openMemos: List<Memo>): Status {
        val locationMemos = openMemos.filter { it.hasLocation }
        val pendingIds = locationMemos.filterNot { it.isRegistered }.map { it.id }.toSet()
        return Status(locationMemos.isNotEmpty(), readRequirements(), pendingIds)
    }

    suspend fun activate(): Set<Long> = memoGeofenceChanges.withLock {
        if (!readRequirements().canMonitor) return@withLock emptySet()
        val openMemos = withContext(Dispatchers.IO) { repository.getOpen() }
        val failed = mutableSetOf<Long>()
        for (memo in openMemos) {
            if (!memo.hasLocation || memo.isDone || memo.isRegistered) continue
            try {
                geofenceManager.register(memo)

                withContext(Dispatchers.IO) {
                    repository.setRegistered(memo.id, true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("MonitoringUseCase", "Could not register geofence for memo ${memo.id}", error)
                failed.add(memo.id)
            }
        }
        failed
    }

    suspend fun setCompleted(memoId: Long, completed: Boolean) = memoGeofenceChanges.withLock {
        withContext(Dispatchers.IO) {
            val memo = repository.getMemoById(memoId)
            repository.saveMemo(memo.copy(isDone = completed))
            if (completed && memo.hasLocation) {
                geofenceManager.remove(memo.id.toString())
                repository.setRegistered(memo.id, false)
            }
        }
    }
}

private val ReminderReadiness.canMonitor: Boolean
    get() = location.isPreciseLocationEnabled && location.isBackgroundLocationEnabled &&
        locationServicesEnabled && notificationAccess == NotificationAccess.ENABLED
