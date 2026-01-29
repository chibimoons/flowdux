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

    // Store benchmarks
    val storeBenchmark = FlowDuxBenchmark(config)
    val storeSummary = storeBenchmark.runAll()
    storeSummary.print()

    // Serialization benchmarks
    val serializationBenchmark = SerializationBenchmark(config)
    serializationBenchmark.printPayloadSizes()
    val serializationResults = serializationBenchmark.runAll()
    BenchmarkSummary(serializationResults).print()
}
