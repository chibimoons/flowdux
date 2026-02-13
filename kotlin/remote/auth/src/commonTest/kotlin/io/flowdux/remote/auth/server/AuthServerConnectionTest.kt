package io.flowdux.remote.auth.server

import app.cash.turbine.turbineScope
import io.flowdux.remote.auth.AuthConfig
import io.flowdux.remote.auth.AuthProtocol
import io.flowdux.remote.auth.MockServerConnection
import io.flowdux.remote.auth.TestPrincipal
import io.flowdux.remote.auth.acceptAllVerifier
import io.flowdux.remote.auth.rejectAllVerifier
import io.flowdux.remote.auth.throwingVerifier
import io.flowdux.remote.auth.tokenVerifier
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

    @Test
    fun connectionClosedBeforeAuth_returnsFailure() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = acceptAllVerifier,
        )

        // Close the connection before sending any message
        mock.closeIncoming()

        val result = authConn.awaitAuth(backgroundScope)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Connection closed before auth", result.reason)
    }

    @Test
    fun malformedAuthMessage_returnsFailure() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = acceptAllVerifier,
        )

        // Send an auth message with missing token field
        mock.simulateIncoming("""{"type":"auth"}""")

        val result = authConn.awaitAuth(backgroundScope)

        assertIs<AuthResult.Failure>(result)
        assertTrue(result.reason.contains("Malformed auth message"))

        // Verify auth_error was sent back
        assertTrue(mock.sentMessages.any { it.contains("auth_error") })
    }

    @Test
    fun verifierThrows_returnsFailureAndSendsAuthError() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = throwingVerifier,
        )

        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("any-token"))

        val result = authConn.awaitAuth(backgroundScope)

        assertIs<AuthResult.Failure>(result)
        assertTrue(result.reason.contains("Verifier error"))
        assertTrue(result.reason.contains("JWT decode failed"))

        // Verify auth_error was sent back (not a crash)
        assertTrue(mock.sentMessages.any { it.contains("auth_error") })
    }

    @Test
    fun authOkAsFirstMessage_returnsFailure() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = acceptAllVerifier,
        )

        // Client sends auth_ok instead of auth request — unexpected auth type
        mock.simulateIncoming(AuthProtocol.encodeAuthSuccess())

        val result = authConn.awaitAuth(backgroundScope)

        assertIs<AuthResult.Failure>(result)
        // isAuthMessage returns true for auth_ok, but decodeAuthRequest fails (no token)
        assertTrue(result.reason.contains("Malformed auth message"))
    }

    // --- maxAuthAttempts retry tests ---

    @Test
    fun retryAuth_secondAttemptSucceeds() = runTest {
        val mock = MockServerConnection()
        val verifier = tokenVerifier("valid-token")
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = verifier,
            config = AuthConfig(maxAuthAttempts = 2),
        )

        // First: bad token, Second: valid token
        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("bad-token"))
        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("valid-token"))

        val result = authConn.awaitAuth(backgroundScope)

        assertIs<AuthResult.Success<TestPrincipal>>(result)
        assertEquals("user-1", result.principal.userId)

        // Verify: auth_error for first, auth_ok for second
        assertEquals(2, mock.sentMessages.size)
        assertTrue(mock.sentMessages[0].contains("auth_error"))
        assertTrue(mock.sentMessages[1].contains("auth_ok"))
    }

    @Test
    fun retryAuth_bothAttemptsFail() = runTest {
        val mock = MockServerConnection()
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = rejectAllVerifier,
            config = AuthConfig(maxAuthAttempts = 2),
        )

        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("bad-1"))
        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("bad-2"))

        val result = authConn.awaitAuth(backgroundScope)

        assertIs<AuthResult.Failure>(result)
        assertEquals("Access denied", result.reason)

        // Two auth_error messages sent
        assertEquals(2, mock.sentMessages.size)
        assertTrue(mock.sentMessages.all { it.contains("auth_error") })
    }

    @Test
    fun retryAuth_messagesForwardedAfterRetrySuccess() = runTest {
        val mock = MockServerConnection()
        val verifier = tokenVerifier("valid-token")
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = verifier,
            config = AuthConfig(maxAuthAttempts = 2),
        )

        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("bad-token"))
        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("valid-token"))

        val result = authConn.awaitAuth(backgroundScope)
        assertIs<AuthResult.Success<TestPrincipal>>(result)

        turbineScope {
            val messages = authConn.incoming.testIn(backgroundScope)
            mock.simulateIncoming("""{"data":"after-retry"}""")
            assertEquals("""{"data":"after-retry"}""", messages.awaitItem())
            messages.cancel()
        }
    }

    @Test
    fun defaultMaxAttempts_singleAttemptOnly() = runTest {
        val mock = MockServerConnection()
        val verifier = tokenVerifier("valid-token")
        val authConn = AuthServerConnection(
            delegate = mock,
            verifier = verifier,
            // default: maxAuthAttempts = 1
        )

        mock.simulateIncoming(AuthProtocol.encodeAuthRequest("bad-token"))

        val result = authConn.awaitAuth(backgroundScope)

        // Should fail immediately — no retry
        assertIs<AuthResult.Failure>(result)
        assertEquals("Invalid token", result.reason)
        assertEquals(1, mock.sentMessages.size)
    }
}
