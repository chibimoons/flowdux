package io.flowdux.remote.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthProtocolTest {

    @Test
    fun encodeAuthRequest_producesValidJson() {
        val encoded = AuthProtocol.encodeAuthRequest("my-token-123")
        assertTrue(encoded.contains("\"type\":\"auth\""))
        assertTrue(encoded.contains("\"token\":\"my-token-123\""))
    }

    @Test
    fun encodeAuthSuccess_producesValidJson() {
        val encoded = AuthProtocol.encodeAuthSuccess()
        assertTrue(encoded.contains("\"type\":\"auth_ok\""))
    }

    @Test
    fun encodeAuthError_producesValidJson() {
        val encoded = AuthProtocol.encodeAuthError("Token expired")
        assertTrue(encoded.contains("\"type\":\"auth_error\""))
        assertTrue(encoded.contains("\"reason\":\"Token expired\""))
    }

    @Test
    fun isAuthMessage_detectsAuthRequest() {
        val message = AuthProtocol.encodeAuthRequest("token")
        assertTrue(AuthProtocol.isAuthMessage(message))
    }

    @Test
    fun isAuthMessage_detectsAuthSuccess() {
        val message = AuthProtocol.encodeAuthSuccess()
        assertTrue(AuthProtocol.isAuthMessage(message))
    }

    @Test
    fun isAuthMessage_detectsAuthError() {
        val message = AuthProtocol.encodeAuthError("reason")
        assertTrue(AuthProtocol.isAuthMessage(message))
    }

    @Test
    fun isAuthMessage_returnsFalseForNonAuthJson() {
        assertFalse(AuthProtocol.isAuthMessage("""{"type":"action","payload":"data"}"""))
    }

    @Test
    fun isAuthMessage_returnsFalseForInvalidJson() {
        assertFalse(AuthProtocol.isAuthMessage("not json at all"))
    }

    @Test
    fun isAuthMessage_returnsFalseForEmptyObject() {
        assertFalse(AuthProtocol.isAuthMessage("{}"))
    }

    @Test
    fun decodeAuthRequest_extractsToken() {
        val encoded = AuthProtocol.encodeAuthRequest("secret-token")
        val token = AuthProtocol.decodeAuthRequest(encoded)
        assertEquals("secret-token", token)
    }

    @Test
    fun decodeAuthRequest_throwsOnMissingToken() {
        assertFailsWith<IllegalArgumentException> {
            AuthProtocol.decodeAuthRequest("""{"type":"auth"}""")
        }
    }

    @Test
    fun decodeAuthResponse_decodesSuccess() {
        val encoded = AuthProtocol.encodeAuthSuccess()
        val response = AuthProtocol.decodeAuthResponse(encoded)
        assertIs<AuthProtocolResponse.Success>(response)
    }

    @Test
    fun decodeAuthResponse_decodesError() {
        val encoded = AuthProtocol.encodeAuthError("Expired")
        val response = AuthProtocol.decodeAuthResponse(encoded)
        assertIs<AuthProtocolResponse.Error>(response)
        assertEquals("Expired", response.reason)
    }

    @Test
    fun decodeAuthResponse_throwsOnUnknownType() {
        assertFailsWith<IllegalArgumentException> {
            AuthProtocol.decodeAuthResponse("""{"type":"unknown"}""")
        }
    }

    @Test
    fun roundTrip_authRequest() {
        val originalToken = "eyJhbGciOiJIUzI1NiJ9.test-payload"
        val encoded = AuthProtocol.encodeAuthRequest(originalToken)
        assertTrue(AuthProtocol.isAuthMessage(encoded))
        val decoded = AuthProtocol.decodeAuthRequest(encoded)
        assertEquals(originalToken, decoded)
    }

    @Test
    fun roundTrip_authSuccess() {
        val encoded = AuthProtocol.encodeAuthSuccess()
        assertTrue(AuthProtocol.isAuthMessage(encoded))
        val response = AuthProtocol.decodeAuthResponse(encoded)
        assertIs<AuthProtocolResponse.Success>(response)
    }

    @Test
    fun roundTrip_authError() {
        val reason = "Token expired at 2024-01-01"
        val encoded = AuthProtocol.encodeAuthError(reason)
        assertTrue(AuthProtocol.isAuthMessage(encoded))
        val response = AuthProtocol.decodeAuthResponse(encoded)
        assertIs<AuthProtocolResponse.Error>(response)
        assertEquals(reason, response.reason)
    }
}
