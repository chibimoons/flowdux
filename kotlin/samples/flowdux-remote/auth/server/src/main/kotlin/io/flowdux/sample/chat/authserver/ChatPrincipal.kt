package io.flowdux.sample.chat.authserver

import io.flowdux.remote.auth.AuthPrincipal

data class ChatPrincipal(
    val userId: String,
    val displayName: String,
) : AuthPrincipal
