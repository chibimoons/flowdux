package io.flowdux.benchmark

import kotlin.time.Duration

/**
 * Result of a single benchmark run.
 */
data class BenchmarkResult(
    val name: String,
    val totalActions: Int,
    val totalTime: Duration,
    val avgLatency: Duration,
    val minLatency: Duration,
    val maxLatency: Duration,
    val p50Latency: Duration,
    val p95Latency: Duration,
    val p99Latency: Duration,
    val throughput: Double, // actions per second
) {
    fun print() {
        println("""
            |
            |=== $name ===
            |  Total Actions:  $totalActions
            |  Total Time:     $totalTime
            |  Throughput:     ${String.format("%,.0f", throughput)} actions/sec
            |
            |  Latency:
            |    Average:      $avgLatency
            |    Min:          $minLatency
            |    Max:          $maxLatency
            |    P50:          $p50Latency
            |    P95:          $p95Latency
            |    P99:          $p99Latency
            |
            |  Game Server Metrics:
            |    Can sustain 60 FPS:  ${if (canSustain60Fps()) "YES ✅" else "NO ❌"}
            |    Max sustainable FPS: ${String.format("%.1f", maxSustainableFps())}
            |    Actions per frame (60fps): ${String.format("%.1f", actionsPerFrame(60))}
        """.trimMargin())
    }

    /** Check if average latency allows 60 FPS (16.67ms per frame) */
    fun canSustain60Fps(): Boolean = avgLatency.inWholeMicroseconds < 16_667

    /** Calculate max sustainable FPS based on average latency */
    fun maxSustainableFps(): Double {
        val latencyMs = avgLatency.inWholeMicroseconds / 1000.0
        return if (latencyMs > 0) 1000.0 / latencyMs else Double.MAX_VALUE
    }

    /** Calculate how many actions can be processed per frame at given FPS */
    fun actionsPerFrame(fps: Int): Double {
        val frameTimeMs = 1000.0 / fps
        val latencyMs = avgLatency.inWholeMicroseconds / 1000.0
        return if (latencyMs > 0) frameTimeMs / latencyMs else Double.MAX_VALUE
    }
}

/**
 * Summary of multiple benchmark runs.
 */
data class BenchmarkSummary(
    val results: List<BenchmarkResult>,
) {
    fun print() {
        println("\n" + "=".repeat(60))
        println("FLOWDUX BENCHMARK SUMMARY")
        println("=".repeat(60))

        results.forEach { it.print() }

        println("\n" + "=".repeat(60))
        println("COMPARISON TABLE")
        println("=".repeat(60))
        println(String.format("%-30s %15s %15s %10s", "Benchmark", "Throughput", "Avg Latency", "60fps?"))
        println("-".repeat(70))
        results.forEach { r ->
            println(String.format(
                "%-30s %12.0f/s %15s %10s",
                r.name.take(30),
                r.throughput,
                r.avgLatency,
                if (r.canSustain60Fps()) "✅" else "❌"
            ))
        }
    }
}
