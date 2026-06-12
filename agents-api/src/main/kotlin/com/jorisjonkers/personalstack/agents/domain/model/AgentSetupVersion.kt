package com.jorisjonkers.personalstack.agents.domain.model

@JvmInline
value class AgentSetupVersion(
    val value: Long,
) {
    init {
        require(value > 0) { "agent setup version must be positive" }
    }

    override fun toString(): String = value.toString()

    companion object {
        fun initial(): AgentSetupVersion = AgentSetupVersion(1)
    }
}
