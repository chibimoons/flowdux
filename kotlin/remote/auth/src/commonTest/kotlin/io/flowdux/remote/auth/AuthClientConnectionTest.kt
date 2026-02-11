package io.flowdux.remote.auth

import app.cash.turbine.turbineScope
import io.flowdux.remote.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class AuthClientConnectionTest {

    @Test
    fun happyPath_connectsAndAuthenticates() = runTest {
        val mock = MockClientConnection()
        val authConn = AuthClientConnection(
            delegate = mock,
            credentialProvider = CredentialProvider { "valid-token" },
        )

        // Launch connect in background (it suspends for connection lifetime)
        val connectJob = launch { authConn.connect() }

        // Mock sends auth success response
        mock.simulateIncoming(AuthProtocol.encodeAuthSuccess())

        // Wait for connected
        authConn.connectionState.first { it == ConnectionState.CONNECTED }

        // Verify auth request was sent
        assertEquals(1, mock.sentMessages.size)
        assertTrue(AuthProtocol.isAuthMessage(mock.sentMessages[0]))
        assertEquals("valid-token", AuthProtocol.decodeAuthRequest(mock.sentMessages[0]))

        // Clean up
        connectJob.cancel()
    }

    @Test
    fun happyPath_forwardsNonAuthMessages() = runTest {
        val mock = MockClientConnection()
        val authConn = AuthClientConnection(
            delegate = mock,
            credentialProvider = CredentialProvider { "token" },
        )

        val connectJob = launch { authConn.connect() }

        // Complete auth handshake
        mock.simulateIncoming(AuthProtocol.encodeAuthSuccess())
        authConn.connectionState.first { it == ConnectionState.CONNECTED }

        turbineScope {
            val messages = authConn.incoming.testIn(backgroundScope)

            // Send a normal (non-auth) message
            mock.simulateIncoming("""{"type":"action","payload":"hello"}""")
            assertEquals("""{"type":"action","payload":"hello"}""", messages.awaitItem())

            // Auth messages should NOT be forwarded
            mock.simulateIncoming(AuthProtocol.encodeAuthSuccess())
            // Normal message should still come through
            mock.simulateIncoming("""{"data":"world"}""")
            assertEquals("""{"data":"world"}""", messages.awaitItem())

            messages.cancel()
        }

        connectJob.cancel()
    }

    @Test
    fun authRejected_throwsAuthenticationException() = runTest {
        val mock = MockClientConnection()
        val authConn = AuthClientConnection(
            delegate = mock,
            credentialProvider = CredentialProvider { "bad-token" },
        )

        val ex = assertFailsWith<AuthenticationException> {
            launch {
                mock.simulateIncoming(AuthProtocol.encodeAuthError("Invalid credentials"))
            }
            authConn.connect()
        }

        assertEquals("Authentication rejected by server", ex.message)
    }

    @Test
    fun timeout_throwsAuthenticationException() = runTest {
        val mock = MockClientConnection()
        val authConn = AuthClientConnection(
            delegate = mock,
            credentialProvider = CredentialProvider { "token" },
            config = AuthConfig(handshakeTimeout = 100.milliseconds),
        )

        val ex = assertFailsWith<AuthenticationException> {
            authConn.connect()
        }

        assertEquals("Auth handshake timed out", ex.message)
    }
}
