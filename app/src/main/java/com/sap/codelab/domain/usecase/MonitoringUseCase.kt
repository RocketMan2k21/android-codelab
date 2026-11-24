package com.sap.codelab.domain.usecase

import android.util.Log
import com.sap.codelab.domain.GeofenceManager
import com.sap.codelab.data.geofence.GeofenceNotificationManager
import com.sap.codelab.data.location.LocationPermissionSource
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.model.NotificationAccess
import com.sap.codelab.domain.model.ReminderReadiness
import com.sap.codelab.domain.model.hasLocation
import com.sap.codelab.domain.MemoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes memo creation, monitoring activation, and completion across both use cases. */
internal val memoGeofenceChanges = Mutex()

internal class MonitoringUseCase(
    private val repository: MemoRepository,
    private val geofenceManager: GeofenceManager,
    private val locationPermissions: LocationPermissionSource,
    private val notifications: GeofenceNotificationManager
) {
    data class Status(val isVisible: Boolean, val requirements: ReminderReadiness, val pendingIds: Set<Long>) {
        val canActivate: Boolean
            get() = requirements.canMonitor

        val isActive: Boolean
            get() = isVisible && canActivate && pendingIds.isEmpty()
    }

    fun readRequirements(): ReminderReadiness = ReminderReadiness(
        location = locationPermissions.getPermissionStatus(),
        locationServicesEnabled = locationPermissions.isLocationEnabled(),
        notificationAccess = notifications.getAccess()
    )

    /** Combines stored registration flags for open memos with current permissions and settings. */
    fun status(openMemos: List<Memo>): Status {
        val locationMemos = openMemos.filter { it.hasLocation }
        val pendingIds = locationMemos.filterNot { it.isRegistered }.map { it.id }.toSet()
        return Status(locationMemos.isNotEmpty(), readRequirements(), pendingIds)
    }

    /** Attempts to register pending reminders when requirements are met and returns IDs that failed. */
    suspend fun activate(): Set<Long> = memoGeofenceChanges.withLock {
        if (!readRequirements().canMonitor) return@withLock emptySet()
        val openMemos = repository.getOpen()
        val failed = mutableSetOf<Long>()
        for (memo in openMemos) {
            if (!memo.hasLocation || memo.isDone || memo.isRegistered) continue
            try {
                geofenceManager.register(memo)

                repository.setRegistered(memo.id, true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("MonitoringUseCase", "Could not register geofence for memo ${memo.id}", error)
                failed.add(memo.id)
            }
        }
        failed
    }

    /** Saves the completion state before attempting geofence removal for a completed memo with a location. */
    suspend fun setCompleted(memoId: Long, completed: Boolean) = memoGeofenceChanges.withLock {
        val memo = repository.getMemoById(memoId)
        repository.saveMemo(memo.copy(isDone = completed))
        if (completed && memo.hasLocation) {
            geofenceManager.remove(memo.id.toString())
            repository.setRegistered(memo.id, false)
        }
    }
}

private val ReminderReadiness.canMonitor: Boolean
    get() = location.isPreciseLocationEnabled && location.isBackgroundLocationEnabled &&
        locationServicesEnabled && notificationAccess == NotificationAccess.ENABLED
