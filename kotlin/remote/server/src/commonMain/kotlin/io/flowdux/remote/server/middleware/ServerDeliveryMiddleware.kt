package io.flowdux.remote.server.middleware

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.Middleware
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Server-side middleware that automatically re-dispatches [ClientSharedAction]s
 * through the full middleware pipeline.
 *
 * This middleware solves the problem where [ClientSharedAction]s emitted from
 * middleware processors bypass sync middlewares (because emitted actions go to
 * the next middleware, not back to the beginning).
 *
 * When this middleware sees a [ClientSharedAction], it re-dispatches it through
 * the full pipeline so that [SingleClientSyncMiddleware] or [MultiClientSyncMiddleware]
 * can intercept and send it to the client(s).
 *
 * **Important:** This middleware must be placed AFTER the sync middleware in the
 * middleware list to work correctly.
 *
 * Usage:
 * ```kotlin
 * // Option 1: Use createServerStore (recommended)
 * val store = createServerStore(
 *     initialState = MyState(),
 *     syncMiddleware = mySyncMiddleware,
 *     reducer = myReducer,
 * )
 *
 * // Option 2: Manual setup
 * lateinit var store: Store<MyState, MyAction>
 * val deliveryMiddleware = ServerDeliveryMiddleware<MyState, MyAction> { store.dispatch(it) }
 * store = createStore(
 *     middlewares = listOf(mySyncMiddleware, deliveryMiddleware),
 *     // ...
 * )
 * ```
 *
 * With this middleware, you can use `emit(ClientSharedAction)` from processors
 * and it will automatically be sent to the client:
 * ```kotlin
 * override val processors = buildProcessors {
 *     on<ScoreChanged> { state, action ->
 *         emit(ScoreUpdate(state.score))  // ClientSharedAction - auto re-dispatched
 *         emit(action)                     // Local action - passes through
 *     }
 * }
 * ```
 *
 * @param dispatch Function to re-dispatch actions through the full Store pipeline.
 */
class ServerDeliveryMiddleware<S : State, A : Action>(
    private val dispatch: (A) -> Unit,
) : Middleware<S, A> {

    override val name: String = "ServerDeliveryMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    override fun process(getState: () -> S, action: A): Flow<A> {
        // ClientSharedAction: re-dispatch through full pipeline
        // so sync middleware can intercept and send to client(s)
        if (action is ClientSharedAction) {
            dispatch(action)
            return flow { } // Don't emit; action is re-dispatched
        }

        // All other actions pass through
        return flowOf(action)
    }
}
