package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.Store

/**
 * Collect state changes and dispatch each as a wrapped action.
 *
 * Suspends until the store is closed or the calling coroutine is cancelled.
 * Typically used in a server WebSocket handler to sync state to the client:
 *
 * ```kotlin
 * webSocket("/game") {
 *     val store = createStore(...)
 *     try {
 *         store.dispatch(StartListening)
 *         store.serveState { SyncState(it) }
 *     } finally {
 *         store.close()
 *     }
 * }
 * ```
 *
 * @param wrapState Maps the current state to an action (typically a [ClientSharedAction][io.flowdux.remote.ClientSharedAction])
 *   that the server middleware will send to the client.
 */
suspend fun <S : State, A : Action> Store<S, A>.serveState(
    wrapState: (S) -> A,
) {
    state.collect { dispatch(wrapState(it)) }
}
