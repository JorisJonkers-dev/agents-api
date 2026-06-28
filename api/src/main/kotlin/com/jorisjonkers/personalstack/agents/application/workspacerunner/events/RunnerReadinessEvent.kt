package com.jorisjonkers.personalstack.agents.application.workspacerunner.events

data class RunnerReadinessEvent(
    val workspaceId: String,
    val readiness: String,
    val ts: String,
)

data class RunnerKeepaliveEvent(
    val ts: String,
)
