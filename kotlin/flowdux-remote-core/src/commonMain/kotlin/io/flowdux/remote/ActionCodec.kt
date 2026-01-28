package io.flowdux.remote

import io.flowdux.Action

/**
 * Codec for serializing and deserializing actions.
 *
 * Applications must implement this interface to define how their
 * action types are converted to/from JSON strings for wire transmission.
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
}
