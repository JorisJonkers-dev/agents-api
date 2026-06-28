package com.jorisjonkers.personalstack.agents.domain.model

/**
 * Discriminator for the three workspace flavours surfaced in the UI:
 *
 * - `REPO_BACKED` — Pod with a git clone. Repository is required.
 * - `SCRATCH`     — Pod without a clone. Repository is null.
 * - `CHAT`        — no Pod. A plain LLM conversation surface that
 *                   lives outside the workspaces table (see
 *                   [ChatSession]); the enum value exists here so a
 *                   workspace row with `kind = CHAT` can never be
 *                   created — the type system gives the orchestrator
 *                   a single value to switch on.
 */
enum class WorkspaceKind {
    REPO_BACKED,
    SCRATCH,
    CHAT,
}
