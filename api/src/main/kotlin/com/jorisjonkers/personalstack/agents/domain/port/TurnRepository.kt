package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.Turn

interface TurnRepository {
    fun save(turn: Turn): Turn

    fun findBySessionId(
        sessionId: AgentSessionId,
        limit: Int = 200,
    ): List<Turn>
}
