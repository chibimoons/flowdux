package io.flowdux.benchmark

import io.flowdux.Action
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.JsonMessageCodec
import io.flowdux.remote.MessageCodec
import io.flowdux.remote.SharedAction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.measureTime

/**
 * Serialization performance benchmark for FlowDux remote modules.
 *
 * Measures:
 * - ActionCodec encode/decode throughput
 * - MessageCodec encode/decode throughput
 * - Full round-trip (action → message → wire → message → action)
 * - Various payload sizes
 */
class SerializationBenchmark(
    private val config: BenchmarkConfig = BenchmarkConfig(),
) {
    // -- Test Fixtures --

    sealed interface BenchAction : Action {
        /** Small payload (~40 bytes) */
        data class Small(val value: Int) : BenchAction, SharedAction

        /** Medium payload (~200 bytes) */
        data class Medium(
            val userId: String,
            val message: String,
            val timestamp: Long,
            val metadata: String,
        ) : BenchAction, SharedAction

        /** Large payload (~1KB+) */
        data class Large(
            val userId: String,
            val items: List<String>,
            val scores: List<Int>,
            val nested: String,
        ) : BenchAction, SharedAction
    }

    class BenchActionCodec : ActionCodec<BenchAction> {
        override fun encode(action: BenchAction): String = when (action) {
            is BenchAction.Small ->
                """{"type":"Small","value":${action.value}}"""
            is BenchAction.Medium ->
                """{"type":"Medium","userId":"${action.userId}","message":"${action.message}","timestamp":${action.timestamp},"metadata":"${action.metadata}"}"""
            is BenchAction.Large -> {
                val items = action.items.joinToString(",") { "\"$it\"" }
                val scores = action.scores.joinToString(",")
                """{"type":"Large","userId":"${action.userId}","items":[$items],"scores":[$scores],"nested":"${action.nested}"}"""
            }
        }

        override fun decode(json: String): BenchAction = when {
            json.contains("\"type\":\"Small\"") -> {
                val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
                BenchAction.Small(value)
            }
            json.contains("\"type\":\"Medium\"") -> {
                val userId = Regex(""""userId":"([^"]+)"""").find(json)!!.groupValues[1]
                val message = Regex(""""message":"([^"]+)"""").find(json)!!.groupValues[1]
                val timestamp = Regex(""""timestamp":(\d+)""").find(json)!!.groupValues[1].toLong()
                val metadata = Regex(""""metadata":"([^"]+)"""").find(json)!!.groupValues[1]
                BenchAction.Medium(userId, message, timestamp, metadata)
            }
            json.contains("\"type\":\"Large\"") -> {
                val userId = Regex(""""userId":"([^"]+)"""").find(json)!!.groupValues[1]
                val items = Regex(""""items":\[([^\]]*)]""").find(json)!!.groupValues[1]
                    .split(",").map { it.trim().removeSurrounding("\"") }
                val scores = Regex(""""scores":\[([^\]]*)]""").find(json)!!.groupValues[1]
                    .split(",").map { it.trim().toInt() }
                val nested = Regex(""""nested":"((?:[^"\\]|\\.)*)"""").find(json)!!.groupValues[1]
                BenchAction.Large(userId, items, scores, nested)
            }
            else -> throw IllegalArgumentException("Unknown: $json")
        }
    }

    private val actionCodec = BenchActionCodec()
    private val messageCodec: MessageCodec = JsonMessageCodec()

    // Test data
    private val smallAction = BenchAction.Small(42)
    private val mediumAction = BenchAction.Medium(
        userId = "user-12345678",
        message = "Hello, this is a typical chat message with some content!",
        timestamp = 1706500000000L,
        metadata = """{"platform":"android","version":"2.1.0","locale":"ko-KR"}"""
    )
    private val largeAction = BenchAction.Large(
        userId = "user-12345678",
        items = List(20) { "item-${it.toString().padStart(4, '0')}-abcdefghij" },
        scores = List(20) { it * 100 + 42 },
        nested = """{"level":5,"experience":12500,"inventory":{"gold":9999,"gems":42},"achievements":["first_blood","veteran","master"]}"""
    )

    // -- Benchmark Methods --

    fun runAll(): List<BenchmarkResult> {
        println("\nStarting Serialization Benchmark Suite...")
        println()

        val results = mutableListOf<BenchmarkResult>()

        // ActionCodec benchmarks
        print("  ActionCodec encode (small)...")
        results.add(benchmarkOperation("ActionCodec Encode (Small ~40B)") {
            actionCodec.encode(smallAction)
        })
        println(" done")

        print("  ActionCodec encode (medium)...")
        results.add(benchmarkOperation("ActionCodec Encode (Medium ~200B)") {
            actionCodec.encode(mediumAction)
        })
        println(" done")

        print("  ActionCodec encode (large)...")
        results.add(benchmarkOperation("ActionCodec Encode (Large ~1KB)") {
            actionCodec.encode(largeAction)
        })
        println(" done")

        // Pre-encode for decode benchmarks
        val smallJson = actionCodec.encode(smallAction)
        val mediumJson = actionCodec.encode(mediumAction)
        val largeJson = actionCodec.encode(largeAction)

        print("  ActionCodec decode (small)...")
        results.add(benchmarkOperation("ActionCodec Decode (Small ~40B)") {
            actionCodec.decode(smallJson)
        })
        println(" done")

        print("  ActionCodec decode (medium)...")
        results.add(benchmarkOperation("ActionCodec Decode (Medium ~200B)") {
            actionCodec.decode(mediumJson)
        })
        println(" done")

        print("  ActionCodec decode (large)...")
        results.add(benchmarkOperation("ActionCodec Decode (Large ~1KB)") {
            actionCodec.decode(largeJson)
        })
        println(" done")

        // MessageCodec benchmarks
        print("  MessageCodec encode...")
        results.add(benchmarkOperation("MessageCodec EncodeAction") {
            messageCodec.encodeActionMessage(mediumJson)
        })
        println(" done")

        val wireMessage = messageCodec.encodeServerResponse(listOf(smallJson, mediumJson))
        print("  MessageCodec decode...")
        results.add(benchmarkOperation("MessageCodec DecodeServer (2 actions)") {
            messageCodec.decodeServerMessage(wireMessage)
        })
        println(" done")

        // Full round-trip
        print("  Full round-trip (medium)...")
        results.add(benchmarkOperation("Full Round-trip (Medium)") {
            // Client side: action → wire
            val encoded = actionCodec.encode(mediumAction)
            val message = messageCodec.encodeActionMessage(encoded)

            // Server side: wire → action
            val actionJson = messageCodec.decodeActionFromClient(message)
            actionCodec.decode(actionJson)

            // Server side: action → wire (response)
            val responseEncoded = actionCodec.encode(mediumAction)
            val response = messageCodec.encodeServerResponse(listOf(responseEncoded))

            // Client side: wire → action (response)
            val serverResponse = messageCodec.decodeServerMessage(response)
            for (json in serverResponse.actions) {
                actionCodec.decode(json)
            }
        })
        println(" done")

        // Batch encoding (server broadcasting to N clients)
        print("  Batch encode (10 actions)...")
        val batchActions = List(10) { mediumJson }
        results.add(benchmarkOperation("Batch Encode (10 actions)") {
            messageCodec.encodeServerResponse(batchActions)
        })
        println(" done")

        val batchWire = messageCodec.encodeServerResponse(batchActions)
        print("  Batch decode (10 actions)...")
        results.add(benchmarkOperation("Batch Decode (10 actions)") {
            val resp = messageCodec.decodeServerMessage(batchWire)
            for (json in resp.actions) {
                actionCodec.decode(json)
            }
        })
        println(" done")

        return results
    }

    private fun benchmarkOperation(
        name: String,
        operation: () -> Unit,
    ): BenchmarkResult {
        val iterations = config.actionsPerBenchmark
        val latencies = mutableListOf<Duration>()

        repeat(config.repeatCount) {
            // Warmup
            repeat(config.warmupIterations) { operation() }

            // Measure
            repeat(iterations) {
                val start = System.nanoTime()
                operation()
                val end = System.nanoTime()
                latencies.add((end - start).nanoseconds)
            }
        }

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

    fun printPayloadSizes() {
        val smallJson = actionCodec.encode(smallAction)
        val mediumJson = actionCodec.encode(mediumAction)
        val largeJson = actionCodec.encode(largeAction)

        val smallWire = messageCodec.encodeActionMessage(smallJson)
        val mediumWire = messageCodec.encodeActionMessage(mediumJson)
        val largeWire = messageCodec.encodeActionMessage(largeJson)

        println("\n--- Payload Sizes ---")
        println(String.format("%-15s %10s %10s", "Size", "ActionJSON", "Wire Msg"))
        println("-".repeat(37))
        println(String.format("%-15s %8dB %8dB", "Small", smallJson.length, smallWire.length))
        println(String.format("%-15s %8dB %8dB", "Medium", mediumJson.length, mediumWire.length))
        println(String.format("%-15s %8dB %8dB", "Large", largeJson.length, largeWire.length))
    }
}
