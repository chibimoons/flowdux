package io.flowdux.benchmark

/**
 * Configuration for FlowDux benchmarks.
 */
data class BenchmarkConfig(
    /** Number of warmup iterations before measuring */
    val warmupIterations: Int = 1000,
    /** Number of actions to dispatch per benchmark */
    val actionsPerBenchmark: Int = 10_000,
    /** Number of times to repeat each benchmark for averaging */
    val repeatCount: Int = 5,
    /** Number of concurrent dispatchers for concurrency test */
    val concurrentDispatchers: Int = 10,
    /** Number of middlewares to add for middleware overhead test */
    val middlewareCount: Int = 5,
    /** Target tick rate for game server simulation (ticks per second) */
    val targetTickRate: Int = 60,
)
