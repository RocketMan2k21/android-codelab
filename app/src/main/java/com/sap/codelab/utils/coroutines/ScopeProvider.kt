package com.sap.codelab.utils.coroutines

import kotlinx.coroutines.*
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Provides the coroutines application scope and factory methods for creating local scopes used in activities, fragments, components etc.
 */
internal object ScopeProvider {

    /**
     * Creates a new coroutine scope for any local context such as: activities, fragments, views, components etc.
     * Make sure you handle cancellation properly!
     *
     * @param dispatcher    - dispatcher used to run the coroutines. If there is no dispatcher provided the Main (UI) dispatcher is used by default.
     */
    fun newScope(dispatcher: CoroutineDispatcher = Dispatchers.Main): CoroutineScope {
        return CoroutineScope(dispatcher)
    }
}