package io.flowdux.sample.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Helper scope for iOS
class MainScope : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Main
}
