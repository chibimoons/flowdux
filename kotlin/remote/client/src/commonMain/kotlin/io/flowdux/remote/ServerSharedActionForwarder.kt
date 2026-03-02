package io.flowdux.remote

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.Middleware
import io.flowdux.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Internal middleware that re-dispatches [ServerSharedAction]s through the full pipeline.
 *
 * This middleware solves the problem where [ServerSharedAction]s emitted from
 * middleware processors bypass [SyncMiddleware] (because emitted actions go to
 * the next middleware, not back to the beginning).
 *
 * When this middleware sees a [ServerSharedAction], it re-dispatches it through
 * the full pipeline so that [SyncMiddleware] can intercept and send it to the server.
 *
 * This middleware is automatically added by [createClientStore]. Users should not
 * instantiate this class directly.
 *
 * @param dispatch Function to re-dispatch actions through the full Store pipeline.
 */
internal class ServerSharedActionForwarder<S : State, A : Action>(private val dispatch: (A) -> Unit) :
    Middleware<S, A> {
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    override fun process(getState: () -> S, action: A): Flow<A> {
        if (action is ServerSharedAction) {
            dispatch(action)
            return emptyFlow()
        }
        return flowOf(action)
    }
}
