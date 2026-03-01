package io.flowdux.remote

/**
 * Represents a response received from the server.
 *
 * @property actions Serialized action JSON strings to be dispatched to the local store.
 */
data class ServerResponse(val actions: List<String>)
