package io.flowdux

interface StoreLogger<S : State, A : Action> {
    fun onActionDispatched(action: A)
    fun onMiddlewareProcessing(middlewareName: String, action: A)
    fun onMiddlewaresCompleted(action: A)
    fun onFlowHolderActionEmitted(action: A)
    fun onErrorOccurred(throwable: Throwable)
    fun onErrorHandled(action: A)
    fun onStateReduced(action: A, previousState: S, newState: S)
}

class NoOpStoreLogger<S : State, A : Action> : StoreLogger<S, A> {
    override fun onActionDispatched(action: A) {}
    override fun onMiddlewareProcessing(middlewareName: String, action: A) {}
    override fun onMiddlewaresCompleted(action: A) {}
    override fun onFlowHolderActionEmitted(action: A) {}
    override fun onErrorOccurred(throwable: Throwable) {}
    override fun onErrorHandled(action: A) {}
    override fun onStateReduced(action: A, previousState: S, newState: S) {}
}
