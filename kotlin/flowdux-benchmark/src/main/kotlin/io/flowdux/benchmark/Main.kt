package io.flowdux.benchmark

import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val config = BenchmarkConfig(
        warmupIterations = 1000,
        actionsPerBenchmark = 10_000,
        repeatCount = 3,
        concurrentDispatchers = 10,
        middlewareCount = 5,
        targetTickRate = 60,
    )

    val benchmark = FlowDuxBenchmark(config)
    val summary = benchmark.runAll()
    summary.print()
}
