package com.jorisjonkers.personalstack.agents.application.workspacerunner

interface RunnerReadinessPublisher {
    fun publish(snapshot: RunnerReadinessSnapshot)
}
