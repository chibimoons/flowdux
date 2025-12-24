package io.flowdux.sample.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Helper scope for iOS
class MainScope : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Main
}

// Extension to observe StateFlow from Swift
fun <T> StateFlow<T>.watch(block: (T) -> Unit): Closeable {
    val scope = MainScope()
    scope.launch {
        this@watch.collect { block(it) }
    }
    return object : Closeable {
        override fun close() {
            scope.coroutineContext[SupervisorJob.Key]?.cancel()
        }
    }
}

interface Closeable {
    fun close()
}
