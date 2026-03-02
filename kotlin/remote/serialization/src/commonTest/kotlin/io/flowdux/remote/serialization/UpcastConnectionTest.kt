package io.flowdux.remote.serialization

import io.flowdux.Action
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.server.connection.TypedServerConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpcastConnectionTest {
    // -- Test Action Hierarchy --
    interface AppAction : Action

    sealed interface SharedAction : AppAction {
        data class Message(val text: String) : SharedAction
    }

    data class LocalAction(val value: Int) : AppAction

    // -- Mock TypedClientConnection --
    class MockTypedClientConnection : TypedClientConnection<SharedAction> {
        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<ConnectionState> = _connectionState
        override val incoming: Flow<SharedAction> = flowOf(SharedAction.Message("hello"))

        val sentActions = mutableListOf<SharedAction>()

        override suspend fun send(action: SharedAction) {
            sentActions.add(action)
        }

        override suspend fun connect() {
            _connectionState.value = ConnectionState.CONNECTED
        }

        override suspend fun disconnect() {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // -- Mock TypedServerConnection --
    class MockTypedServerConnection : TypedServerConnection<SharedAction> {
        override val isActive: Boolean = true
        override val incoming: Flow<SharedAction> = flowOf(SharedAction.Message("from client"))

        val sentActions = mutableListOf<SharedAction>()

        override suspend fun send(action: SharedAction) {
            sentActions.add(action)
        }
    }

    // -- Tests --

    @Test
    fun `upcast TypedClientConnection - incoming actions are upcasted`() = runTest {
        val mockConnection = MockTypedClientConnection()
        val upcastedConnection: TypedClientConnection<AppAction> =
            mockConnection.upcast<SharedAction, AppAction>()

        val actions = upcastedConnection.incoming.toList()

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SharedAction.Message)
        assertEquals("hello", (actions[0] as SharedAction.Message).text)
    }

    @Test
    fun `upcast TypedClientConnection - send shared action works`() = runTest {
        val mockConnection = MockTypedClientConnection()
        val upcastedConnection: TypedClientConnection<AppAction> =
            mockConnection.upcast<SharedAction, AppAction>()

        upcastedConnection.send(SharedAction.Message("test"))

        assertEquals(1, mockConnection.sentActions.size)
        assertEquals(SharedAction.Message("test"), mockConnection.sentActions[0])
    }

    @Test
    fun `upcast TypedClientConnection - send non-shared action throws`() = runTest {
        val mockConnection = MockTypedClientConnection()
        val upcastedConnection: TypedClientConnection<AppAction> =
            mockConnection.upcast<SharedAction, AppAction>()

        assertFailsWith<IllegalArgumentException> {
            upcastedConnection.send(LocalAction(42))
        }
    }

    @Test
    fun `upcast TypedClientConnection - connect and disconnect delegate properly`() = runTest {
        val mockConnection = MockTypedClientConnection()
        val upcastedConnection: TypedClientConnection<AppAction> =
            mockConnection.upcast<SharedAction, AppAction>()

        assertEquals(ConnectionState.DISCONNECTED, upcastedConnection.connectionState.value)

        upcastedConnection.connect()
        assertEquals(ConnectionState.CONNECTED, upcastedConnection.connectionState.value)

        upcastedConnection.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, upcastedConnection.connectionState.value)
    }

    @Test
    fun `upcast TypedServerConnection - incoming actions are upcasted`() = runTest {
        val mockConnection = MockTypedServerConnection()
        val upcastedConnection: TypedServerConnection<AppAction> =
            mockConnection.upcast<SharedAction, AppAction>()

        val actions = upcastedConnection.incoming.toList()

        assertEquals(1, actions.size)
        assertTrue(actions[0] is SharedAction.Message)
        assertEquals("from client", (actions[0] as SharedAction.Message).text)
    }

    @Test
    fun `upcast TypedServerConnection - send shared action works`() = runTest {
        val mockConnection = MockTypedServerConnection()
        val upcastedConnection: TypedServerConnection<AppAction> =
            mockConnection.upcast<SharedAction, AppAction>()

        upcastedConnection.send(SharedAction.Message("server response"))

        assertEquals(1, mockConnection.sentActions.size)
        assertEquals(SharedAction.Message("server response"), mockConnection.sentActions[0])
    }

    @Test
    fun `upcast TypedServerConnection - send non-shared action throws`() = runTest {
        val mockConnection = MockTypedServerConnection()
        val upcastedConnection: TypedServerConnection<AppAction> =
            mockConnection.upcast<SharedAction, AppAction>()

        assertFailsWith<IllegalArgumentException> {
            upcastedConnection.send(LocalAction(99))
        }
    }
}
