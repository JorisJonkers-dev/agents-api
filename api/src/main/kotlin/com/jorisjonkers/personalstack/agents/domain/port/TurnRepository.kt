package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId

interface TurnRepository {
    fun save(turn: Turn): Turn

    fun findBySessionId(
        sessionId: WorkspaceAgentSessionId,
        limit: Int = 200,
    ): List<Turn>
}
