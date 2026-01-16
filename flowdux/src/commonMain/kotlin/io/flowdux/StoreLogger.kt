package io.flowdux

interface StoreLogger<S : State, A : Action> {
    fun onActionDispatched(action: A)
    fun onMiddlewareProcessing(middlewareName: String, action: A)
    fun onMiddlewaresCompleted(action: A)
    fun onFlowHolderActionEmitted(action: A)
    fun onErrorOccurred(throwable: Throwable)
    fun onErrorHandled(action: A)
    fun onStateReduced(action: A, previousState: S, newState: S)

    /**
     * Called when dispatch() is invoked after the store has been closed.
     * This may indicate a bug in the application - consider checking isClosed before dispatching.
     */
    fun onDispatchAfterClose(action: A)
}

class NoOpStoreLogger<S : State, A : Action> : StoreLogger<S, A> {
    override fun onActionDispatched(action: A) {}
    override fun onMiddlewareProcessing(middlewareName: String, action: A) {}
    override fun onMiddlewaresCompleted(action: A) {}
    override fun onFlowHolderActionEmitted(action: A) {}
    override fun onErrorOccurred(throwable: Throwable) {}
    override fun onErrorHandled(action: A) {}
    override fun onStateReduced(action: A, previousState: S, newState: S) {}
    override fun onDispatchAfterClose(action: A) {}
}

class DebugStoreLogger<S : State, A : Action>(
    private val tag: String = "Store"
) : StoreLogger<S, A> {
    override fun onActionDispatched(action: A) {
        println("[$tag] Dispatched: $action")
    }

    override fun onMiddlewareProcessing(middlewareName: String, action: A) {
        println("[$tag] Middleware($middlewareName) processing: $action")
    }

    override fun onMiddlewaresCompleted(action: A) {
        println("[$tag] Middlewares completed: $action")
    }

    override fun onFlowHolderActionEmitted(action: A) {
        println("[$tag] FlowHolderAction emitted: $action")
    }

    override fun onErrorOccurred(throwable: Throwable) {
        println("[$tag] Error: ${throwable.message}")
    }

    override fun onErrorHandled(action: A) {
        println("[$tag] Error handled with: $action")
    }

    override fun onStateReduced(action: A, previousState: S, newState: S) {
        println("[$tag] State reduced: $action")
        println("[$tag]   Previous: $previousState")
        println("[$tag]   New: $newState")
    }

    override fun onDispatchAfterClose(action: A) {
        println("[$tag] WARNING: Dispatch after close: $action")
    }
}
