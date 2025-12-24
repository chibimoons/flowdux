package io.flowdux

import kotlinx.coroutines.flow.Flow

interface Action

interface FlowHolderAction : Action {
    fun toFlowAction() : Flow<Action>
}
