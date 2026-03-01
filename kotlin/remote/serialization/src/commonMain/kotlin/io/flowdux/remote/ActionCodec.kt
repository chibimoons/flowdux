package io.flowdux.remote

import io.flowdux.Action
import kotlinx.coroutines.CancellationException

/**
 * Codec for serializing and deserializing actions.
 *
 * Applications must implement this interface to define how their
 * action types are converted to/from JSON strings for wire transmission.
 *
 * ## Version mismatch handling
 *
 * During rolling updates, clients and servers may temporarily run different
 * versions of the action schema. Use [decodeOrNull] to gracefully skip
 * unrecognized action types instead of crashing:
 *
 * ```kotlin
 * val action = codec.decodeOrNull(json) ?: run {
 *     logger.warn("Unknown action type, skipping: $json")
 *     return
 * }
 * ```
 *
 * The typed connection layer ([TypedClientConnection] / [io.flowdux.remote.server.connection.TypedServerConnection])
 * already handles decode failures via the `onDecodeError` callback —
 * unrecognized actions are skipped and processing continues.
 *
 * Example:
 * ```kotlin
 * class AppActionCodec : ActionCodec<AppAction> {
 *     override fun encode(action: AppAction): String {
 *         return Json.encodeToString(AppAction.serializer(), action)
 *     }
 *     override fun decode(json: String): AppAction {
 *         return Json.decodeFromString(AppAction.serializer(), json)
 *     }
 * }
 * ```
 */
interface ActionCodec<A : Action> {
    fun encode(action: A): String
    fun decode(json: String): A

    /**
     * Decodes the given JSON string, returning `null` if decoding fails.
     *
     * This is useful for graceful handling of action type version mismatches
     * during rolling updates — unknown or incompatible action types return `null`
     * instead of throwing an exception.
     */
    fun decodeOrNull(json: String): A? = try {
        decode(json)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
