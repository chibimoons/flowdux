package io.flowdux.sample.scaling

import io.flowdux.Action
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import kotlinx.serialization.Serializable

/**
 * Server state for the scaling demo.
 */
data class ScalingState(
    val counter: Long = 0,
    val connectedClients: Int = 0,
    val broadcastCount: Long = 0,
) : State

/**
 * Base action interface for scaling demo.
 */
interface ScalingAction : Action

/**
 * Shared actions that cross the wire between client and server.
 * This sealed interface is used for serialization.
 */
@Serializable
sealed interface SharedScalingAction : ScalingAction {
    // Client -> Server
    @Serializable
    data class Ping(val clientId: String) : SharedScalingAction, ServerSharedAction

    @Serializable
    data class Increment(val amount: Int = 1) : SharedScalingAction, ServerSharedAction

    // Server -> Client
    @Serializable
    data class Pong(val counter: Long, val connectedClients: Int) : SharedScalingAction, ClientSharedAction

    @Serializable
    data class CounterUpdate(val counter: Long) : SharedScalingAction, ClientSharedAction

    @Serializable
    data class ServerStats(
        val connectedClients: Int,
        val broadcastCount: Long,
        val counter: Long,
    ) : SharedScalingAction, ClientSharedAction
}

/**
 * Server-only actions (not serialized, not sent over wire).
 */
sealed interface ServerScalingAction : ScalingAction {
    data class ClientConnected(val clientId: String) : ServerScalingAction
    data class ClientDisconnected(val clientId: String) : ServerScalingAction
    data class BroadcastCompleted(val count: Int) : ServerScalingAction
}

/**
 * Reducer for the scaling demo.
 */
val scalingReducer = Reducer<ScalingState, ScalingAction> { state, action ->
    when (action) {
        is SharedScalingAction.Ping -> state
        is SharedScalingAction.Increment -> state.copy(counter = state.counter + action.amount)
        is ServerScalingAction.ClientConnected -> state.copy(connectedClients = state.connectedClients + 1)
        is ServerScalingAction.ClientDisconnected -> state.copy(
            connectedClients = maxOf(0, state.connectedClients - 1)
        )
        is ServerScalingAction.BroadcastCompleted -> state.copy(
            broadcastCount = state.broadcastCount + action.count
        )
        // Client-side actions (never reach reducer on server)
        is SharedScalingAction.Pong,
        is SharedScalingAction.CounterUpdate,
        is SharedScalingAction.ServerStats -> state
        else -> state
    }
}
