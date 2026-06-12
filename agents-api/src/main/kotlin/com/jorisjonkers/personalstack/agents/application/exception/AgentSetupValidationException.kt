package com.jorisjonkers.personalstack.agents.application.exception

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult

class AgentSetupValidationException(
    val result: AgentSetupValidationResult,
) : RuntimeException(result.message())
