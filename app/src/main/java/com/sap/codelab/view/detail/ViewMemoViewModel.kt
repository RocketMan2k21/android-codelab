package com.sap.codelab.view.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sap.codelab.R
import com.sap.codelab.domain.model.Memo
import com.sap.codelab.domain.usecase.MonitoringUseCase
import com.sap.codelab.repository.IMemoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ViewMemoViewModel(
    private val memoId: Long,
    private val repository: IMemoRepository,
    private val monitoring: MonitoringUseCase
) : ViewModel() {

    data class State(val memo: Memo? = null, val isCompleting: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val errors = Channel<Int>(Channel.CONFLATED)
    val errorMessages = errors.receiveAsFlow()

    init {
        viewModelScope.launch {
            _state.value = State(memo = readMemo())
        }
    }

    fun markDone() {
        val current = state.value
        val memo = current.memo ?: return
        if (memo.isDone || current.isCompleting) return
        _state.value = current.copy(isCompleting = true)

        viewModelScope.launch {
            try {
                monitoring.setCompleted(memoId, true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("ViewMemoViewModel", "Could not update the memo or remove its geofence", error)
                errors.trySend(R.string.memo_completion_error)
            }
            _state.value = State(memo = readMemo() ?: memo)
        }
    }

    private suspend fun readMemo(): Memo? {
        return try {
            withContext(Dispatchers.IO) { repository.getMemoById(memoId) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("ViewMemoViewModel", "Could not load memo $memoId", error)
            errors.trySend(R.string.memo_load_error)
            null
        }
    }
}
