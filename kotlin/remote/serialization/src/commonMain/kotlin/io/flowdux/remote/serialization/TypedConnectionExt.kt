package io.flowdux.remote.serialization

import io.flowdux.Action
import io.flowdux.remote.ClientConnection
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.server.connection.ServerConnection
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.typed
import io.flowdux.remote.typed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Creates a [TypedClientConnection] using JSON serialization.
 *
 * Convenience shortcut that combines [actionCodecOf] and [JsonMessageCodec]:
 * ```kotlin
 * // Before
 * val typed = connection.typed(actionCodecOf<MyAction>(), JsonMessageCodec())
 *
 * // After
 * val typed = connection.typedJson<MyAction>()
 * ```
 *
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified A : Action> ClientConnection.typedJson(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedClientConnection<A> = typed(actionCodecOf<A>(json), JsonMessageCodec())

/**
 * Creates a [TypedServerConnection] using JSON serialization.
 *
 * Convenience shortcut that combines [actionCodecOf] and [JsonMessageCodec]:
 * ```kotlin
 * // Before
 * val typed = connection.typed(actionCodecOf<MyAction>(), JsonMessageCodec())
 *
 * // After
 * val typed = connection.typedJson<MyAction>()
 * ```
 *
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified A : Action> ServerConnection.typedJson(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedServerConnection<A> = typed(actionCodecOf<A>(json), JsonMessageCodec())

/**
 * Creates a [TypedClientConnection] using JSON serialization and upcasts to a supertype.
 *
 * Combines [typedJson] and [upcast] into a single call:
 * ```kotlin
 * // Before
 * val connection = rawConnection
 *     .typedJson<SharedAction>()
 *     .upcast<SharedAction, AppAction>()
 *
 * // After
 * val connection = rawConnection.typedJsonAs<SharedAction, AppAction>()
 * ```
 *
 * @param Wire The narrow serializable action type used on the wire
 * @param A The broader action type that [Wire] extends, used by the store/middleware
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified Wire : A, reified A : Action> ClientConnection.typedJsonAs(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedClientConnection<A> = typedJson<Wire>(json).upcast<Wire, A>()

/**
 * Creates a [TypedServerConnection] using JSON serialization and upcasts to a supertype.
 *
 * Combines [typedJson] and [upcast] into a single call:
 * ```kotlin
 * // Before
 * val connection = rawConnection
 *     .typedJson<SharedAction>()
 *     .upcast<SharedAction, AppAction>()
 *
 * // After
 * val connection = rawConnection.typedJsonAs<SharedAction, AppAction>()
 * ```
 *
 * @param Wire The narrow serializable action type used on the wire
 * @param A The broader action type that [Wire] extends, used by the store/middleware
 * @param json Custom [Json] instance for action serialization. Defaults to
 *   [SerializableActionCodec.DefaultJson] (with `classDiscriminator = "type"`).
 */
inline fun <reified Wire : A, reified A : Action> ServerConnection.typedJsonAs(
    json: Json = SerializableActionCodec.DefaultJson,
): TypedServerConnection<A> = typedJson<Wire>(json).upcast<Wire, A>()

/**
 * Upcasts this [TypedClientConnection] to work with a supertype action.
 *
 * This is useful when the connection is typed with a narrow serializable type (e.g., `SharedAction`)
 * but the middleware/store uses a broader action type (e.g., `AppAction`).
 *
 * **Safety contract:**
 * - Incoming actions from the server are always [Sub] type (safe upcast to [Super])
 * - Only [Sub] actions should be sent through the connection (enforced at runtime)
 *
 * **Tip:** If starting from a raw [ClientConnection], prefer [typedJsonAs] which combines
 * `typedJson` and `upcast` in a single call.
 *
 * Example:
 * ```kotlin
 * // From a TypedClientConnection
 * val connection = typedConnection.upcast<SharedAction, AppAction>()
 * ```
 *
 * @param Sub The narrow action type this connection is typed with
 * @param Super The broader action type that [Sub] extends
 * @throws IllegalArgumentException if [send] is called with an action that is not a [Sub] instance
 */
inline fun <reified Sub : Super, reified Super : Action> TypedClientConnection<Sub>.upcast(): TypedClientConnection<Super> =
    UpcastTypedClientConnection(this, Sub::class)

/**
 * Upcasts this [TypedServerConnection] to work with a supertype action.
 *
 * This is useful when the connection is typed with a narrow serializable type (e.g., `SharedAction`)
 * but the middleware/store uses a broader action type (e.g., `AppAction`).
 *
 * **Safety contract:**
 * - Incoming actions from the client are always [Sub] type (safe upcast to [Super])
 * - Only [Sub] actions should be sent through the connection (enforced at runtime)
 *
 * **Tip:** If starting from a raw [ServerConnection], prefer [typedJsonAs] which combines
 * `typedJson` and `upcast` in a single call.
 *
 * Example:
 * ```kotlin
 * // From a TypedServerConnection
 * val connection = typedConnection.upcast<SharedAction, AppAction>()
 * ```
 *
 * @param Sub The narrow action type this connection is typed with
 * @param Super The broader action type that [Sub] extends
 * @throws IllegalArgumentException if [send] is called with an action that is not a [Sub] instance
 */
inline fun <reified Sub : Super, reified Super : Action> TypedServerConnection<Sub>.upcast(): TypedServerConnection<Super> =
    UpcastTypedServerConnection(this, Sub::class)

@PublishedApi
internal class UpcastTypedClientConnection<Sub : Super, Super : Action>(
    private val delegate: TypedClientConnection<Sub>,
    private val subClass: kotlin.reflect.KClass<Sub>,
) : TypedClientConnection<Super> {
    override val connectionState: StateFlow<ConnectionState> = delegate.connectionState
    override val incoming: Flow<Super> = delegate.incoming.map { it }

    override suspend fun send(action: Super) {
        require(subClass.isInstance(action)) {
            "Cannot send ${action::class.simpleName} through connection typed for ${subClass.simpleName}"
        }
        @Suppress("UNCHECKED_CAST")
        delegate.send(action as Sub)
    }

    override suspend fun connect() = delegate.connect()
    override suspend fun disconnect() = delegate.disconnect()
}

@PublishedApi
internal class UpcastTypedServerConnection<Sub : Super, Super : Action>(
    private val delegate: TypedServerConnection<Sub>,
    private val subClass: kotlin.reflect.KClass<Sub>,
) : TypedServerConnection<Super> {
    override val incoming: Flow<Super> = delegate.incoming.map { it }

    override suspend fun send(action: Super) {
        require(subClass.isInstance(action)) {
            "Cannot send ${action::class.simpleName} through connection typed for ${subClass.simpleName}"
        }
        @Suppress("UNCHECKED_CAST")
        delegate.send(action as Sub)
    }
}
