package io.flowdux.remote

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.Middleware
import io.flowdux.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Client-side middleware that automatically re-dispatches [ServerSharedAction]s
 * through the full middleware pipeline.
 *
 * This middleware solves the problem where [ServerSharedAction]s emitted from
 * middleware processors bypass [SyncMiddleware] (because emitted actions go to
 * the next middleware, not back to the beginning).
 *
 * When this middleware sees a [ServerSharedAction], it re-dispatches it through
 * the full pipeline so that [SyncMiddleware] can intercept and send it to the server.
 *
 * **Important:** This middleware must be placed AFTER [SyncMiddleware] in the
 * middleware list to work correctly.
 *
 * Usage:
 * ```kotlin
 * // Option 1: Use createClientStore (recommended)
 * val store = createClientStore(
 *     initialState = MyState(),
 *     syncMiddleware = mySyncMiddleware,
 *     reducer = myReducer,
 * )
 *
 * // Option 2: Manual setup
 * lateinit var store: Store<MyState, MyAction>
 * val deliveryMiddleware = ClientDeliveryMiddleware<MyState, MyAction> { store.dispatch(it) }
 * store = createStore(
 *     middlewares = listOf(mySyncMiddleware, deliveryMiddleware),
 *     // ...
 * )
 * ```
 *
 * With this middleware, you can use `emit(ServerSharedAction)` from processors
 * and it will automatically be sent to the server:
 * ```kotlin
 * override val processors = buildProcessors {
 *     on<SendMessage> { _, action ->
 *         emit(ChatMessage(action.text))  // ServerSharedAction - auto re-dispatched
 *         emit(MessageSent(action.text))  // Local action - passes through
 *     }
 * }
 * ```
 *
 * @param dispatch Function to re-dispatch actions through the full Store pipeline.
 */
class ClientDeliveryMiddleware<S : State, A : Action>(
    private val dispatch: (A) -> Unit,
) : Middleware<S, A> {

    override val name: String = "ClientDeliveryMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    override fun process(getState: () -> S, action: A): Flow<A> {
        // ServerSharedAction: re-dispatch through full pipeline
        // so SyncMiddleware can intercept and send to server
        if (action is ServerSharedAction) {
            dispatch(action)
            return flow { } // Don't emit; action is re-dispatched
        }

        // All other actions pass through
        return flowOf(action)
    }
}
