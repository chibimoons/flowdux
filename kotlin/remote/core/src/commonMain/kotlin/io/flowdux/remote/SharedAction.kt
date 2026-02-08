package io.flowdux.remote

import io.flowdux.Action

/**
 * Marker interface for actions that are shared between client and server.
 *
 * Use the directional sub-interfaces to specify the intended destination:
 * - [ServerSharedAction]: client → server (intercepted by `SyncMiddleware`)
 * - [ClientSharedAction]: server → client (intercepted by `SingleClientSyncMiddleware`)
 */
interface SharedAction : Action

/**
 * Marker for actions sent from client to server.
 *
 * Actions implementing this interface will be intercepted by `SyncMiddleware`,
 * serialized, and sent to the server via the configured `ClientConnection`.
 * They will NOT be dispatched to the local (client) reducer.
 *
 * Example:
 * ```kotlin
 * data class SendMessage(val text: String) : ChatAction, ServerSharedAction
 * ```
 */
interface ServerSharedAction : SharedAction

/**
 * Marker for actions sent from server to client.
 *
 * Actions implementing this interface will be intercepted by `SingleClientSyncMiddleware`,
 * serialized, and sent to the client. They will NOT be dispatched to the local (server) reducer.
 *
 * Example:
 * ```kotlin
 * data class MessageReceived(val text: String) : ChatAction, ClientSharedAction
 * ```
 */
interface ClientSharedAction : SharedAction
