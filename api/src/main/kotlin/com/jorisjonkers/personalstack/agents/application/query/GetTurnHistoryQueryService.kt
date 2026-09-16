package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.port.TurnRepository
import org.springframework.stereotype.Service

@Service
class GetTurnHistoryQueryService(
    private val turns: TurnRepository,
) {
    fun history(
        sessionId: AgentSessionId,
        limit: Int = 200,
    ): List<Turn> = turns.findBySessionId(sessionId, limit)
}
