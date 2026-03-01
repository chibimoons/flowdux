package io.flowdux.remote.auth.client

import app.cash.turbine.turbineScope
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.auth.AuthConfig
import io.flowdux.remote.auth.AuthProtocol
import io.flowdux.remote.auth.AuthenticationException
import io.flowdux.remote.auth.MockClientConnection
import kotlinx.coroutines.delay
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
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "valid-token" },
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
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "token" },
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
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "bad-token" },
            )

        val ex =
            assertFailsWith<AuthenticationException> {
                launch {
                    mock.simulateIncoming(AuthProtocol.encodeAuthError("Invalid credentials"))
                }
                authConn.connect()
            }

        assertTrue(ex.message!!.contains("Authentication rejected by server"))
        assertTrue(ex.message!!.contains("Invalid credentials"))
    }

    @Test
    fun timeout_throwsAuthenticationException() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "token" },
                config = AuthConfig(handshakeTimeout = 100.milliseconds),
            )

        val ex =
            assertFailsWith<AuthenticationException> {
                authConn.connect()
            }

        assertEquals("Auth handshake timed out", ex.message)
    }

    @Test
    fun preAuthMessages_areNotForwarded() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "token" },
            )

        val connectJob = launch { authConn.connect() }

        // Send a non-auth message BEFORE auth completes
        mock.simulateIncoming("""{"data":"pre-auth-message"}""")

        // Now complete auth handshake
        mock.simulateIncoming(AuthProtocol.encodeAuthSuccess())
        authConn.connectionState.first { it == ConnectionState.CONNECTED }

        turbineScope {
            val messages = authConn.incoming.testIn(backgroundScope)

            // Send a message after auth — this one should come through
            mock.simulateIncoming("""{"data":"post-auth-message"}""")
            assertEquals("""{"data":"post-auth-message"}""", messages.awaitItem())

            // The pre-auth message should NOT have been forwarded
            messages.expectNoEvents()

            messages.cancel()
        }

        connectJob.cancel()
    }

    @Test
    fun connectionState_becomesDisconnectedAfterTransportCloses() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "token" },
            )

        val connectJob = launch { authConn.connect() }

        // Complete auth
        mock.simulateIncoming(AuthProtocol.encodeAuthSuccess())
        authConn.connectionState.first { it == ConnectionState.CONNECTED }

        // Simulate transport disconnect by cancelling the connect job
        connectJob.cancel()
        delay(100)

        // Connection state should be DISCONNECTED after connect() completes
        assertEquals(ConnectionState.DISCONNECTED, authConn.connectionState.value)
    }

    @Test
    fun sendBeforeAuth_suspendsUntilAuthThenProceeds() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "token" },
            )

        val connectJob = launch { authConn.connect() }

        // Launch a send before auth completes — should suspend
        val sendJob = launch { authConn.send("hello") }
        delay(50)

        // Send has not completed yet (still suspending on auth)
        assertTrue(sendJob.isActive)

        // Complete auth
        mock.simulateIncoming(AuthProtocol.encodeAuthSuccess())
        authConn.connectionState.first { it == ConnectionState.CONNECTED }

        // Wait for send to complete
        sendJob.join()

        // Verify the message was sent via delegate (after auth request)
        assertTrue(mock.sentMessages.size >= 2) // auth request + "hello"
        assertEquals("hello", mock.sentMessages.last())

        connectJob.cancel()
    }

    @Test
    fun authError_reasonIncludedInException() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "bad-token" },
            )

        launch {
            mock.simulateIncoming(AuthProtocol.encodeAuthError("Token expired at 2025-01-01"))
        }

        val ex = assertFailsWith<AuthenticationException> { authConn.connect() }
        assertTrue(ex.message!!.contains("Token expired at 2025-01-01"))
    }

    @Test
    fun sendAfterAuthFailure_throwsAuthenticationException() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "bad-token" },
            )

        // Connect and fail auth
        launch {
            mock.simulateIncoming(AuthProtocol.encodeAuthError("Invalid"))
        }
        assertFailsWith<AuthenticationException> { authConn.connect() }

        // Send after failure should throw
        assertFailsWith<AuthenticationException> {
            authConn.send("should-fail")
        }
    }

    // --- Token Refresh Tests ---

    @Test
    fun refreshOnAuthError_retriesAndSucceeds() = runTest {
        val mock = MockClientConnection()
        var refreshCalled = false
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "expired-token" },
                refreshProvider = {
                    refreshCalled = true
                    "fresh-token"
                },
            )

        val connectJob = launch { authConn.connect() }

        // Server rejects initial token
        mock.simulateIncoming(AuthProtocol.encodeAuthError("Token expired"))
        delay(100)

        // Refresh should have been called and a second auth request sent
        assertTrue(refreshCalled)
        assertEquals(2, mock.sentMessages.size)
        assertEquals("expired-token", AuthProtocol.decodeAuthRequest(mock.sentMessages[0]))
        assertEquals("fresh-token", AuthProtocol.decodeAuthRequest(mock.sentMessages[1]))

        // Server accepts refreshed token
        mock.simulateIncoming(AuthProtocol.encodeAuthSuccess())
        authConn.connectionState.first { it == ConnectionState.CONNECTED }

        assertEquals(ConnectionState.CONNECTED, authConn.connectionState.value)

        connectJob.cancel()
    }

    @Test
    fun refreshReturnsNull_propagatesOriginalFailure() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "expired-token" },
                refreshProvider = { null },
            )

        val ex =
            assertFailsWith<AuthenticationException> {
                launch {
                    mock.simulateIncoming(AuthProtocol.encodeAuthError("Token expired"))
                }
                authConn.connect()
            }

        assertTrue(ex.message!!.contains("Token expired"))
    }

    @Test
    fun refreshThrows_propagatesOriginalFailure() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "expired-token" },
                refreshProvider = { throw RuntimeException("Refresh server down") },
            )

        val ex =
            assertFailsWith<AuthenticationException> {
                launch {
                    mock.simulateIncoming(AuthProtocol.encodeAuthError("Token expired"))
                }
                authConn.connect()
            }

        assertTrue(ex.message!!.contains("Token expired"))
    }

    @Test
    fun refreshSucceedsButSecondAuthRejected_throws() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "expired-token" },
                refreshProvider = { "also-bad-token" },
            )

        val ex =
            assertFailsWith<AuthenticationException> {
                launch {
                    // First: reject initial token
                    mock.simulateIncoming(AuthProtocol.encodeAuthError("Token expired"))
                    delay(100)
                    // Second: reject refreshed token too
                    mock.simulateIncoming(AuthProtocol.encodeAuthError("Still invalid"))
                }
                authConn.connect()
            }

        // Error reason should be from the second rejection
        assertTrue(ex.message!!.contains("Still invalid"))
    }

    @Test
    fun refreshOnAuthError_messagesForwardedAfterRetry() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "expired-token" },
                refreshProvider = { "fresh-token" },
            )

        val connectJob = launch { authConn.connect() }

        // Reject → refresh → accept
        mock.simulateIncoming(AuthProtocol.encodeAuthError("Token expired"))
        delay(100)
        mock.simulateIncoming(AuthProtocol.encodeAuthSuccess())
        authConn.connectionState.first { it == ConnectionState.CONNECTED }

        // Verify messages are forwarded after successful refresh
        turbineScope {
            val messages = authConn.incoming.testIn(backgroundScope)

            mock.simulateIncoming("""{"data":"after-refresh"}""")
            assertEquals("""{"data":"after-refresh"}""", messages.awaitItem())

            messages.cancel()
        }

        connectJob.cancel()
    }

    @Test
    fun refreshTimeout_propagatesOriginalFailure() = runTest {
        val mock = MockClientConnection()
        val authConn =
            AuthClientConnection(
                delegate = mock,
                tokenProvider = { "expired-token" },
                config = AuthConfig(handshakeTimeout = 200.milliseconds),
                refreshProvider = { "fresh-token" },
            )

        val ex =
            assertFailsWith<AuthenticationException> {
                launch {
                    // Reject initial token
                    mock.simulateIncoming(AuthProtocol.encodeAuthError("Token expired"))
                    // Don't send any response to the retry — let it timeout
                }
                authConn.connect()
            }

        // Should fail with the original error (refresh timed out)
        assertTrue(ex.message!!.contains("Token expired"))
    }
}
