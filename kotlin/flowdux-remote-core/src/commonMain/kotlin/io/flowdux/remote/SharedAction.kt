package io.flowdux.remote

import io.flowdux.Action

/**
 * Marker interface for actions that should be sent to the remote server.
 *
 * Actions implementing this interface will be intercepted by [RemoteFlowMiddleware],
 * serialized, and sent to the server via the configured [RemoteConnection].
 * They will NOT be dispatched to the local reducer.
 *
 * Example:
 * ```kotlin
 * data class IncrementAction(val amount: Int) : AppAction(), SharedAction
 * ```
 */
interface SharedAction : Action {
    val typeKey: String get() = this::class.simpleName ?: "Unknown"
}
