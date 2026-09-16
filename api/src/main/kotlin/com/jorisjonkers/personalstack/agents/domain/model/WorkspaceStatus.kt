package com.jorisjonkers.personalstack.agents.domain.model

/**
 * Workspace lifecycle. A workspace's status is the join of its own
 * desired state and the underlying runner Pod's observed phase:
 *
 *   PENDING   — record created, Pod not yet scheduled (glossary: Preparing)
 *   STARTING  — no longer written (#63); kept so old rows still deserialise
 *   READY     — ready for agents, including scaled-to-zero-but-usable
 *   IDLE      — no longer written (#63); kept so old rows still deserialise
 *   FAILED    — terminal; carries [Workspace.failureReason] for the UI
 *   DESTROYED — workspace torn down, PVC reclaimed
 */
enum class WorkspaceStatus { PENDING, STARTING, READY, IDLE, FAILED, DESTROYED }
