package io.flowdux.remote.auth

import app.cash.turbine.turbineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class AuthServerConnectionTest {

    @Test
    fun happyPath_authenticatesAndForwardsMessages() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = acceptAllVerifier,
        )

        // Send auth request from "client"
        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("user-42"))

        val result = authConn.awaitAuth(backgroundScope)

        // Auth succeeds
        assertIs<AuthResult.Success<TestPrincipal>>(result)
        assertEquals("user-42", result.principal.userId)
        assertEquals("User user-42", result.principal.name)

        // Verify auth_ok was sent back
        assertEquals(1, mock.sentMessages.size)
        assertTrue(mock.sentMessages[0].contains("auth_ok"))

        // Principal is now accessible
        assertEquals("user-42", authConn.principal.userId)

        // Forward non-auth messages
        turbineScope {
            val messages = authConn.incoming.testIn(backgroundScope)
            mock.simulateIncoming("""{"type":"action","data":"hello"}""")
            assertEquals("""{"type":"action","data":"hello"}""", messages.awaitItem())
            messages.cancel()
        }
    }

    @Test
    fun invalidToken_returnsFailure() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = rejectAllVerifier,
        )

        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("bad-token"))

        val result = authConn.awaitAuth(backgroundScope)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Access denied", result.reason)

        // Verify auth_error was sent back
        assertEquals(1, mock.sentMessages.size)
        assertTrue(mock.sentMessages[0].contains("auth_error"))
        assertTrue(mock.sentMessages[0].contains("Access denied"))
    }

    @Test
    fun nonAuthFirstMessage_returnsFailure() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = acceptAllVerifier,
        )

        // Send a non-auth message as the first message
        mock.simulateIncoming("""{"type":"action","data":"oops"}""")

        val result = authConn.awaitAuth(backgroundScope)

        assertIs<AuthResult.Failure>(result)
        assertTrue(result.reason.contains("Expected auth message"))

        // Verify auth_error was sent back
        assertEquals(1, mock.sentMessages.size)
        assertTrue(mock.sentMessages[0].contains("auth_error"))
    }

    @Test
    fun timeout_returnsFailure() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = acceptAllVerifier,
            config = AuthConfig(handshakeTimeout = 100.milliseconds),
        )

        // Don't send any message — should timeout
        val result = authConn.awaitAuth(backgroundScope)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Auth handshake timed out", result.reason)
    }

    @Test
    fun specificTokenVerifier_acceptsValidToken() = runTest {
        val mock = MockServerConnection()
        val verifier = tokenVerifier("secret-123")
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = verifier,
        )

        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("secret-123"))
        val result = authConn.awaitAuth(backgroundScope)
        assertIs<AuthResult.Success<TestPrincipal>>(result)
        assertEquals("user-1", result.principal.userId)
    }

    @Test
    fun specificTokenVerifier_rejectsInvalidToken() = runTest {
        val mock = MockServerConnection()
        val verifier = tokenVerifier("secret-123")
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = verifier,
        )

        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("wrong-token"))
        val result = authConn.awaitAuth(backgroundScope)
        assertIs<AuthResult.Failure>(result)
        assertEquals("Invalid token", result.reason)
    }

    @Test
    fun authMessages_filteredFromForwarding() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = acceptAllVerifier,
        )

        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("user-1"))
        val result = authConn.awaitAuth(backgroundScope)
        assertIs<AuthResult.Success<TestPrincipal>>(result)

        turbineScope {
            val messages = authConn.incoming.testIn(backgroundScope)

            // Auth message should be filtered out
            mock.simulateIncoming(AuthProtocol.encodeAuthRequest("rogue-token"))
            // Normal message should come through
            mock.simulateIncoming("""{"data":"real-message"}""")
            assertEquals("""{"data":"real-message"}""", messages.awaitItem())

            messages.cancel()
        }
    }
}
