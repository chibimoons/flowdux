package io.flowdux.sample.chat.authserver

import io.flowdux.remote.auth.server.AuthPrincipal

data class ChatPrincipal(
    val userId: String,
    val displayName: String,
) : AuthPrincipal
