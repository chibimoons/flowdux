package io.flowdux.benchmark

import io.flowdux.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTime

/**
 * FlowDux performance benchmark suite.
 *
 * Measures meaningful performance metrics:
 * - Dispatch-to-State latency
 * - Throughput (actions/second)
 * - Concurrent dispatch performance
 * - Middleware overhead
 */
class FlowDuxBenchmark(
    private val config: BenchmarkConfig = BenchmarkConfig(),
) {
    // -- Test Fixtures --

    data class BenchState(
        val counter: Int = 0,
        val lastAction: String = "",
    ) : State

    sealed interface BenchAction : Action {
        data class Increment(val amount: Int = 1) : BenchAction
        data class SetValue(val value: Int) : BenchAction
        data object Reset : BenchAction
    }

    private val benchReducer = Reducer<BenchState, BenchAction> { state, action ->
        when (action) {
            is BenchAction.Increment -> state.copy(
                counter = state.counter + action.amount,
                lastAction = "Increment"
            )
            is BenchAction.SetValue -> state.copy(
                counter = action.value,
                lastAction = "SetValue"
            )
            is BenchAction.Reset -> BenchState()
        }
    }

    /** Pass-through middleware (minimal overhead) */
    private class PassThroughMiddleware : Middleware<BenchState, BenchAction> {
        override val name = "PassThrough"
        override val processors: ActionProcessorMap<BenchState, BenchAction> = emptyMap()
    }

    /** Middleware that does some work (simulates real middleware) */
    private class WorkingMiddleware(
        private val workIterations: Int = 100
    ) : Middleware<BenchState, BenchAction> {
        override val name = "Working"
        override val processors: ActionProcessorMap<BenchState, BenchAction> = emptyMap()

        @Volatile
        private var sinkhole = 0

        override fun process(getState: () -> BenchState, action: BenchAction): Flow<BenchAction> = flow {
            // Simulate some work (sinkhole prevents JIT from eliminating the loop)
            var sum = 0
            repeat(workIterations) { sum += it }
            sinkhole = sum
            emit(action)
        }
    }

    // -- Benchmark Methods --

    /**
     * Benchmark 1: Baseline (No Middleware)
     * Measures raw dispatch-to-state performance without middleware.
     */
    suspend fun benchmarkBaseline(): BenchmarkResult {
        return runBenchmark(
            name = "Baseline (No Middleware)",
            middlewares = emptyList()
        )
    }

    /**
     * Benchmark 2: With Pass-through Middlewares
     * Measures middleware chain overhead with minimal work.
     */
    suspend fun benchmarkWithPassThroughMiddleware(): BenchmarkResult {
        return runBenchmark(
            name = "With ${config.middlewareCount} PassThrough Middlewares",
            middlewares = List(config.middlewareCount) { PassThroughMiddleware() }
        )
    }

    /**
     * Benchmark 3: With Working Middlewares
     * Measures realistic middleware overhead.
     */
    suspend fun benchmarkWithWorkingMiddleware(): BenchmarkResult {
        return runBenchmark(
            name = "With ${config.middlewareCount} Working Middlewares",
            middlewares = List(config.middlewareCount) { WorkingMiddleware() }
        )
    }

    /**
     * Benchmark 4: Concurrent Dispatches
     * Measures performance under concurrent dispatch load.
     */
    suspend fun benchmarkConcurrentDispatch(): BenchmarkResult {
        return runConcurrentBenchmark(
            name = "Concurrent (${config.concurrentDispatchers} dispatchers)",
            concurrency = config.concurrentDispatchers
        )
    }

    /**
     * Benchmark 5: Game Server Simulation
     * Simulates game server tick with multiple actions per tick.
     */
    suspend fun benchmarkGameServerSimulation(): BenchmarkResult {
        return runGameServerBenchmark(
            name = "Game Server Sim (${config.targetTickRate} tps)"
        )
    }

    /**
     * Run all benchmarks and return summary.
     */
    suspend fun runAll(): BenchmarkSummary {
        println("Starting FlowDux Benchmark Suite...")
        println("Config: $config")
        println()

        val results = mutableListOf<BenchmarkResult>()

        print("Running baseline benchmark...")
        results.add(benchmarkBaseline())
        println(" done")

        print("Running pass-through middleware benchmark...")
        results.add(benchmarkWithPassThroughMiddleware())
        println(" done")

        print("Running working middleware benchmark...")
        results.add(benchmarkWithWorkingMiddleware())
        println(" done")

        print("Running concurrent dispatch benchmark...")
        results.add(benchmarkConcurrentDispatch())
        println(" done")

        print("Running game server simulation...")
        results.add(benchmarkGameServerSimulation())
        println(" done")

        return BenchmarkSummary(results)
    }

    // -- Internal Implementation --

    private suspend fun runBenchmark(
        name: String,
        middlewares: List<Middleware<BenchState, BenchAction>>,
    ): BenchmarkResult = coroutineScope {
        val latencies = mutableListOf<Duration>()

        repeat(config.repeatCount) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = createStore(
                initialState = BenchState(),
                reducer = benchReducer,
                middlewares = middlewares,
                scope = scope,
            )

            // Warmup
            repeat(config.warmupIterations) {
                store.dispatch(BenchAction.Increment())
            }
            // Wait for warmup to complete
            delay(100)

            // Measure
            repeat(config.actionsPerBenchmark) { i ->
                val startTime = System.nanoTime()

                store.dispatch(BenchAction.Increment())

                // Wait for state to update
                store.state.first { it.counter >= config.warmupIterations + i + 1 }

                val endTime = System.nanoTime()
                latencies.add((endTime - startTime).nanoseconds)
            }

            store.close()
        }

        calculateResult(name, latencies)
    }

    private suspend fun runConcurrentBenchmark(
        name: String,
        concurrency: Int,
    ): BenchmarkResult = coroutineScope {
        val latencies = mutableListOf<Duration>()
        val actionsPerDispatcher = config.actionsPerBenchmark / concurrency

        repeat(config.repeatCount) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = createStore(
                initialState = BenchState(),
                reducer = benchReducer,
                middlewares = emptyList(),
                scope = scope,
            )

            // Warmup
            repeat(config.warmupIterations) {
                store.dispatch(BenchAction.Increment())
            }
            delay(100)

            val totalTime = measureTime {
                val jobs = List(concurrency) { dispatcherId ->
                    launch {
                        repeat(actionsPerDispatcher) {
                            val start = System.nanoTime()
                            store.dispatch(BenchAction.Increment())
                            val end = System.nanoTime()
                            synchronized(latencies) {
                                latencies.add((end - start).nanoseconds)
                            }
                        }
                    }
                }
                jobs.joinAll()

                // Wait for all actions to be processed
                store.state.first { it.counter >= config.warmupIterations + config.actionsPerBenchmark }
            }

            store.close()
        }

        calculateResult(name, latencies)
    }

    private suspend fun runGameServerBenchmark(
        name: String,
    ): BenchmarkResult = coroutineScope {
        val latencies = mutableListOf<Duration>()
        val tickDurationMs = 1000L / config.targetTickRate
        val actionsPerTick = 10 // Simulate 10 player actions per tick
        val totalTicks = config.actionsPerBenchmark / actionsPerTick

        repeat(config.repeatCount) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = createStore(
                initialState = BenchState(),
                reducer = benchReducer,
                middlewares = listOf(PassThroughMiddleware()),
                scope = scope,
            )

            // Warmup
            repeat(config.warmupIterations) {
                store.dispatch(BenchAction.Increment())
            }
            delay(100)

            repeat(totalTicks) { tick ->
                val tickStart = System.nanoTime()

                // Simulate multiple actions in one tick
                repeat(actionsPerTick) {
                    store.dispatch(BenchAction.Increment())
                }

                // Wait for tick's actions to process
                val expectedCount = config.warmupIterations + (tick + 1) * actionsPerTick
                store.state.first { it.counter >= expectedCount }

                val tickEnd = System.nanoTime()
                latencies.add((tickEnd - tickStart).nanoseconds)

                // Simulate tick timing (if we're faster than target, we have headroom)
                val tickTime = (tickEnd - tickStart) / 1_000_000
                if (tickTime < tickDurationMs) {
                    delay(tickDurationMs - tickTime)
                }
            }

            store.close()
        }

        calculateResult(name, latencies)
    }

    private fun calculateResult(name: String, latencies: List<Duration>): BenchmarkResult {
        val sorted = latencies.sorted()
        val totalTime = latencies.fold(Duration.ZERO) { acc, d -> acc + d }

        return BenchmarkResult(
            name = name,
            totalActions = latencies.size,
            totalTime = totalTime,
            avgLatency = totalTime / latencies.size,
            minLatency = sorted.first(),
            maxLatency = sorted.last(),
            p50Latency = sorted[sorted.size / 2],
            p95Latency = sorted[(sorted.size * 0.95).toInt()],
            p99Latency = sorted[(sorted.size * 0.99).toInt()],
            throughput = latencies.size.toDouble() / (totalTime.inWholeMilliseconds / 1000.0),
        )
    }
}
