package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.Store
import io.flowdux.remote.server.middleware.InternalStartListening

/**
 * Collect state changes and dispatch each as a wrapped action.
 *
 * Suspends indefinitely until the calling coroutine is cancelled
 * (e.g., by WebSocket disconnect or [use]/[serve] cleanup).
 * This is a low-level primitive used internally by [serve].
 * Prefer [serve] which handles listening, state sync, and cleanup automatically.
 *
 * @param wrapState Maps the current state to an action (typically a [ClientSharedAction][io.flowdux.remote.ClientSharedAction])
 *   that the server middleware will send to the client.
 */
suspend fun <S : State, A : Action> Store<S, A>.serveState(
    wrapState: (S) -> A,
) {
    state.collect { dispatch(wrapState(it)) }
}

/**
 * Execute [block] with this store, then [close][Store.close] it.
 *
 * Ensures the store is always closed, even if [block] throws or is cancelled.
 *
 * ```kotlin
 * createChatStore(session).use {
 *     // custom setup logic here
 * }
 * ```
 */
suspend inline fun <S : State, A : Action> Store<S, A>.use(
    block: suspend Store<S, A>.() -> Unit,
) {
    try {
        block()
    } finally {
        close()
    }
}

/**
 * All-in-one server helper: starts client listener, syncs state, and closes the store.
 *
 * Combines [use], client listener setup, and [serveState] into a single call.
 * Suspends until the calling coroutine is cancelled (e.g., by WebSocket disconnect).
 *
 * **Requires [ServerRemoteMiddleware] in the store's middleware list.**
 *
 * ```kotlin
 * webSocket("/game") {
 *     createGameStore(this).serve { state ->
 *         SyncState(GameState(score = state.score))
 *     }
 * }
 * ```
 *
 * @param wrapState Maps the current state to an action (typically a [ClientSharedAction][io.flowdux.remote.ClientSharedAction])
 *   that the server middleware will send to the client.
 */
@Suppress("UNCHECKED_CAST")
suspend fun <S : State, A : Action> Store<S, A>.serve(
    wrapState: (S) -> A,
) = use {
    dispatch(InternalStartListening() as A)
    serveState(wrapState)
}
