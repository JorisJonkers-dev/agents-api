package com.jorisjonkers.personalstack.agents.application.sessionstatus

data class SessionStatusEvent(
    val sessionId: String,
    val status: String,
    val idle: Boolean,
    val ts: String,
)

data class SessionRemoveEvent(
    val sessionId: String,
    val ts: String,
)

data class SessionKeepaliveEvent(
    val ts: String,
)
