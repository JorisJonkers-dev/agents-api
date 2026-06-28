package com.jorisjonkers.personalstack.agents.domain.model

/**
 * Workspace lifecycle. A workspace's status is the join of its own
 * desired state and the underlying runner Pod's observed phase:
 *
 *   PENDING   — record created, Pod not yet scheduled
 *   STARTING  — Pod scheduled, agent-gateway boot pending
 *   READY     — gateway answering, ready for agents
 *   IDLE      — no agents and no WS clients for N minutes; scaled to 0
 *   FAILED    — terminal; Pod crash-looping or gateway never came up
 *   DESTROYED — workspace torn down, PVC reclaimed
 */
enum class WorkspaceStatus { PENDING, STARTING, READY, IDLE, FAILED, DESTROYED }
