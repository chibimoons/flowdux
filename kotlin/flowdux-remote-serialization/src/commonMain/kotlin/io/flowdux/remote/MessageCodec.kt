package io.flowdux.remote

/**
 * Codec for encoding/decoding wire-level message envelopes.
 *
 * Handles the framing protocol between client and server:
 * - Client → Server: `{"type":"action","payload":{...}}`
 * - Server → Client: `{"type":"response","actions":[{...},...]}`
 */
interface MessageCodec {
    /** Wrap an action JSON string into a client→server message envelope. */
    fun encodeActionMessage(actionJson: String): String

    /** Parse a raw server→client message into a [ServerResponse]. */
    fun decodeServerMessage(raw: String): ServerResponse

    /** Extract the action JSON payload from a client→server message envelope. */
    fun decodeActionFromClient(raw: String): String

    /** Encode a server response (action JSONs) into a wire message. */
    fun encodeServerResponse(actions: List<String>): String
}
