package io.flowdux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowPipelineTest {
    @Test
    fun `stateIn with Dispatchers Default`(): Unit = runBlocking {
        println("\n=== stateIn with Dispatchers.Default ===\n")

        val scope = CoroutineScope(Dispatchers.Default + Job())

        flow {
            listOf(1, 2, 3).forEach {
                println("[$coroutineContext] emit: $it")
                emit(it)
            }
        }.map {
            println("[$coroutineContext] map1 start: $it")
            delay(50)
            println("[$coroutineContext] map1 end: $it → ${it * 2}")
            it * 2
        }.map {
            println("[$coroutineContext] map2 start: $it")
            delay(50)
            println("[$coroutineContext] map2 end: $it → ${it + 1}")
            it + 1
        }.stateIn(scope, SharingStarted.Eagerly, 0)
            .let { stateFlow ->
                delay(500)
                println("\nFinal state: ${stateFlow.value}")
            }

        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `flatMapMerge then map with stateIn`(): Unit = runBlocking {
        println("\n=== flatMapMerge → map → stateIn ===\n")

        val scope = CoroutineScope(Dispatchers.Default + Job())

        flow {
            listOf(1, 2, 3).forEach {
                println("[$coroutineContext] emit: $it")
                emit(it)
            }
        }.flatMapMerge { value ->
            flow {
                println("[$coroutineContext] flatMapMerge processing: $value")
                delay(50) // simulate async work
                emit(value)
            }
        }.map {
            println("[$coroutineContext] map1 start: $it")
            delay(30)
            println("[$coroutineContext] map1 end: $it → ${it * 10}")
            it * 10
        }.map {
            println("[$coroutineContext] map2 start: $it")
            delay(30)
            println("[$coroutineContext] map2 end: $it → ${it * 10}")
            it * 10
        }.flowOn(Dispatchers.Default)
            .collect {
                delay(500)
                println("\nFinal state: $it")
            }

        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `flowOn between map operators`(): Unit = runBlocking {
        println("\n=== flowOn between map operators ===\n")

        flow {
            listOf(1, 2, 3).forEach {
                println("[$coroutineContext] emit: $it")
                emit(it)
            }
        }.flatMapMerge { value ->
            flow {
                println("[$coroutineContext] flatMapMerge processing: $value")
                delay(50)
                emit(value)
            }
        }.map {
            println("[$coroutineContext] map1 start: $it")
            delay(30)
            println("[$coroutineContext] map1 end: $it → ${it * 10}")
            it * 10
        }.flowOn(Dispatchers.Default) // 여기서 컨텍스트 변경!
            .map {
                println("[$coroutineContext] map2 start: $it")
                delay(30)
                println("[$coroutineContext] map2 end: $it → ${it * 10}")
                it * 10
            }.flowOn(Dispatchers.Default) // 여기서 컨텍스트 변경!
            .collect {
                println("[$coroutineContext] collect: $it")
            }
    }
}
