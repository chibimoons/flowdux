package io.flowdux.remote.auth.server

import io.flowdux.remote.auth.TestPrincipal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthResultTest {

    @Test
    fun getOrElse_returnsPrincipalOnSuccess() {
        val principal = TestPrincipal(userId = "user-1", name = "Alice")
        val result: AuthResult<TestPrincipal> = AuthResult.Success(principal)

        val actual = result.getOrElse { throw AssertionError("Should not be called") }
        assertEquals(principal, actual)
    }

    @Test
    fun getOrElse_invokesOnFailureWithReason() {
        val result: AuthResult<TestPrincipal> = AuthResult.Failure("Invalid token")

        val exception = assertFailsWith<IllegalStateException> {
            result.getOrElse { reason ->
                throw IllegalStateException("Auth failed: $reason")
            }
        }
        assertEquals("Auth failed: Invalid token", exception.message)
    }

    @Test
    fun getOrElse_failureReasonIsPreserved() {
        val reason = "Token expired at 2025-01-01"
        val result: AuthResult<TestPrincipal> = AuthResult.Failure(reason)

        var capturedReason: String? = null
        try {
            result.getOrElse { r ->
                capturedReason = r
                throw RuntimeException("stop")
            }
        } catch (_: RuntimeException) { /* expected */ }

        assertEquals(reason, capturedReason)
    }
}
