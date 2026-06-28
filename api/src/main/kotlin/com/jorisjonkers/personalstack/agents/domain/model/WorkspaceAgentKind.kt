package com.jorisjonkers.personalstack.agents.domain.model

/**
 * The CLI the agent process is running. Matches the gateway's
 * AgentKind enum; kept as a separate type in the agents-api
 * domain so a CLI added at the gateway layer (a hypothetical
 * `gemini`, say) doesn't recompile half this module before its
 * domain semantics are settled.
 */
enum class WorkspaceAgentKind { CLAUDE, CODEX, SHELL }
