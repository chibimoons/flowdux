package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction

/**
 * Marker interface for [ClientSharedAction]s that should be sent per-session
 * instead of broadcast uniformly to all clients.
 *
 * When a `ClientSharedAction` also implements `SessionAwareAction`,
 * [MultiClientServerRemoteMiddleware] calls [forSession] for each connected session
 * and sends the returned action to that session individually.
 *
 * Return `null` from [forSession] to skip sending to that session.
 *
 * Example:
 * ```kotlin
 * data class SyncGameState(val fullState: GameState)
 *     : GameAction, ClientSharedAction, SessionAwareAction<GameAction> {
 *     override fun forSession(sessionId: String): GameAction? {
 *         return SyncPlayerView(fullState.toPlayerView(sessionId))
 *     }
 * }
 * ```
 *
 * @param A The action type produced for each session.
 */
interface SessionAwareAction<A : Action> {
    /**
     * Produce the action to send to the given session.
     *
     * @param sessionId The session identifier.
     * @return The action to send, or `null` to skip this session.
     */
    fun forSession(sessionId: String): A?
}

/**
 * Internal bridge that adapts a session-aware state mapper `(S, String) -> A?`
 * into a [ClientSharedAction] + [SessionAwareAction].
 *
 * Used by [createSessionAwareRemoteServer] to delegate to the existing
 * [createRemoteServerSession] infrastructure while providing per-session mapping.
 */
internal class SessionAwareBroadcast<S : State, A : Action>(
    private val state: S,
    private val mapper: (S, String) -> A?,
) : Action, ClientSharedAction, SessionAwareAction<A> {
    override fun forSession(sessionId: String): A? = mapper(state, sessionId)
}
