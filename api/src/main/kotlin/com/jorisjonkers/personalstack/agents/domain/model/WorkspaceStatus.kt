package com.jorisjonkers.personalstack.agents.domain.model

/**
 * Workspace lifecycle. A workspace's status is the join of its own
 * desired state and the underlying runner Pod's observed phase:
 *
 *   PREPARING — record created, Pod not yet scheduled or still starting up
 *   READY     — ready for agents, including scaled-to-zero-but-usable
 *   FAILED    — terminal; carries [Workspace.failureReason] for the UI
 *   DESTROYED — workspace torn down, PVC reclaimed
 *
 * STARTING and IDLE were folded into PREPARING and READY respectively by
 * #63 — V25 rewrites every surviving row so the four values above are the
 * complete set the column ever holds.
 */
enum class WorkspaceStatus { PREPARING, READY, FAILED, DESTROYED }
