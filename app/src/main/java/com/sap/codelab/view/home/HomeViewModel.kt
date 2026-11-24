package com.sap.codelab.view.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.usecase.MonitoringUseCase
import com.sap.codelab.repository.App
import com.sap.codelab.repository.IMemoRepository
import com.sap.codelab.repository.RoomMemoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class HomeViewModel(
    private val repository: IMemoRepository,
    private val monitoring: MonitoringUseCase
) : ViewModel() {

    data class MonitoringState(
        val status: MonitoringUseCase.Status? = null,
        val isActivating: Boolean = false,
        val failedIds: Set<Long> = emptySet()
    )

    var isShowingAll = false
        private set
    private var refreshJob: Job? = null
    private val _memos = MutableStateFlow<List<Memo>>(emptyList())
    val memos = _memos.asStateFlow()

    private val _monitoringState = MutableStateFlow(MonitoringState())
    val monitoringState = _monitoringState.asStateFlow()

    private val completionErrors = Channel<Unit>(Channel.CONFLATED)
    val memoUpdateErrors = completionErrors.receiveAsFlow()

    fun readRequirements() = monitoring.readRequirements()

    fun loadAllMemos() {
        isShowingAll = true
        refreshMemos()
    }

    fun loadOpenMemos() {
        isShowingAll = false
        refreshMemos()
    }

    fun refreshMemos() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            reload()
        }
    }

    fun activateMonitoring() {
        if (_monitoringState.value.isActivating) return
        _monitoringState.value = _monitoringState.value.copy(isActivating = true)
        viewModelScope.launch {
            try {
                val failedIds = monitoring.activate()
                _monitoringState.value = _monitoringState.value.copy(failedIds = failedIds)
            } finally {
                _monitoringState.value = _monitoringState.value.copy(isActivating = false)
                refreshMemos()
            }
        }
    }

    fun updateMemo(memoId: Long, isChecked: Boolean) {
        viewModelScope.launch {
            try {
                monitoring.setCompleted(memoId, isChecked)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("HomeViewModel", "Could not update the memo or remove its geofence", error)
                completionErrors.trySend(Unit)
            } finally {
                refreshMemos()
            }
        }
    }

    private suspend fun reload() {
        val showAll = isShowingAll
        val (openMemos, displayedMemos) = withContext(Dispatchers.IO) {
            val open = repository.getOpen()
            open to if (showAll) repository.getAll() else open
        }
        if (showAll == isShowingAll) _memos.value = displayedMemos
        val status = monitoring.status(openMemos)
        val state = _monitoringState.value
        _monitoringState.value = state.copy(
            status = status,
            failedIds = state.failedIds.intersect(status.pendingIds)
        )
    }

    companion object {
        fun create(app: App): HomeViewModel = HomeViewModel(RoomMemoRepository, app.monitoringUseCase)
    }
}
