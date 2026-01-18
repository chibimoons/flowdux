package io.flowdux.timetravel

import io.flowdux.Action
import io.flowdux.State

data class StateSnapshot<S : State, A : Action>(
    val index: Int,
    val action: A?,
    val previousState: S?,
    val currentState: S,
    val timestamp: Long
)
