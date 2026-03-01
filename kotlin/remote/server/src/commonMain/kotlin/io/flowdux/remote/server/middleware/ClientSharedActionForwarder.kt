package io.flowdux.remote.server.middleware

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.Middleware
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Internal middleware that re-dispatches [ClientSharedAction]s through the full pipeline.
 *
 * This middleware solves the problem where [ClientSharedAction]s emitted from
 * middleware processors bypass sync middlewares (because emitted actions go to
 * the next middleware, not back to the beginning).
 *
 * When this middleware sees a [ClientSharedAction], it re-dispatches it through
 * the full pipeline so that [SingleClientSyncMiddleware] or [MultiClientSyncMiddleware]
 * can intercept and send it to the client(s).
 *
 * This middleware is automatically added by [createServerStore][io.flowdux.remote.server.createServerStore].
 * Users should not instantiate this class directly.
 *
 * @param dispatch Function to re-dispatch actions through the full Store pipeline.
 */
internal class ClientSharedActionForwarder<S : State, A : Action>(private val dispatch: (A) -> Unit) :
    Middleware<S, A> {
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    override fun process(getState: () -> S, action: A): Flow<A> {
        if (action is ClientSharedAction) {
            dispatch(action)
            return emptyFlow()
        }
        return flowOf(action)
    }
}
