package io.flowdux.remote.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wire protocol for authentication handshake messages.
 *
 * Auth messages use a separate `type` namespace that doesn't conflict with
 * existing action/response message types.
 *
 * ```
 * Client → Server:  {"type":"auth","token":"eyJhbG..."}
 * Server → Client:  {"type":"auth_ok"}
 * Server → Client:  {"type":"auth_error","reason":"Token expired"}
 * ```
 */
internal object AuthProtocol {
    private const val TYPE_KEY = "type"
    private const val TYPE_AUTH = "auth"
    private const val TYPE_AUTH_OK = "auth_ok"
    private const val TYPE_AUTH_ERROR = "auth_error"
    private const val TOKEN_KEY = "token"
    private const val REASON_KEY = "reason"

    private val json = Json { ignoreUnknownKeys = true }

    /** Encode a client → server auth request with the given [token]. */
    fun encodeAuthRequest(token: String): String = buildJsonObject {
        put(TYPE_KEY, TYPE_AUTH)
        put(TOKEN_KEY, token)
    }.toString()

    /** Encode a server → client auth success response. */
    fun encodeAuthSuccess(): String = buildJsonObject {
        put(TYPE_KEY, TYPE_AUTH_OK)
    }.toString()

    /** Encode a server → client auth error response with the given [reason]. */
    fun encodeAuthError(reason: String): String = buildJsonObject {
        put(TYPE_KEY, TYPE_AUTH_ERROR)
        put(REASON_KEY, reason)
    }.toString()

    /** Check whether a raw message is an auth protocol message. */
    fun isAuthMessage(raw: String): Boolean {
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val type = obj[TYPE_KEY]?.jsonPrimitive?.content ?: return false
            type == TYPE_AUTH || type == TYPE_AUTH_OK || type == TYPE_AUTH_ERROR
        } catch (_: Exception) {
            false
        }
    }

    /** Decode the token from a client auth request message. */
    fun decodeAuthRequest(raw: String): String {
        val obj = json.parseToJsonElement(raw).jsonObject
        return obj[TOKEN_KEY]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing token in auth request: ${raw.take(100)}")
    }

    /** Decode a server auth response (success or error). */
    fun decodeAuthResponse(raw: String): AuthProtocolResponse {
        val obj = json.parseToJsonElement(raw).jsonObject
        return when (obj[TYPE_KEY]?.jsonPrimitive?.content) {
            TYPE_AUTH_OK -> AuthProtocolResponse.Success
            TYPE_AUTH_ERROR -> {
                val reason = obj[REASON_KEY]?.jsonPrimitive?.content ?: "Unknown error"
                AuthProtocolResponse.Error(reason)
            }
            else -> throw IllegalArgumentException("Unknown auth response type: ${raw.take(100)}")
        }
    }
}

/** Server-side auth response parsed from wire protocol. */
internal sealed interface AuthProtocolResponse {
    data object Success : AuthProtocolResponse

    data class Error(val reason: String) : AuthProtocolResponse
}
