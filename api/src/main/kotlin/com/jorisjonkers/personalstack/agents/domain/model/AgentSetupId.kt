package com.jorisjonkers.personalstack.agents.domain.model

@JvmInline
value class AgentSetupId(
    val value: String,
) {
    init {
        require(value.matches(VALID_ID)) {
            "agent setup id must start with a lower-case letter and contain only lower-case letters, digits, and dashes"
        }
    }

    override fun toString(): String = value

    companion object {
        private val VALID_ID = Regex("[a-z][a-z0-9-]{0,79}")

        fun default(): AgentSetupId = AgentSetupId("default")

        fun legacy(): AgentSetupId = AgentSetupId("legacy")
    }
}
