package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssue
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssueCode
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.port.AgentSetupRepository
import org.springframework.stereotype.Service

@Service
class AgentSetupSelectionService(
    private val setups: AgentSetupRepository,
) {
    fun defaultSelectable(): AgentSetupCatalogEntry =
        setups.findDefaultSelectable()
            ?: throw validationException(
                ref = AgentSetupRef(AgentSetupId.default(), AgentSetupVersion.initial()),
                code = AgentSetupValidationIssueCode.TARGET_NOT_FOUND,
                message = "no default-selectable agent setup is registered",
            )

    fun select(
        id: AgentSetupId?,
        version: AgentSetupVersion?,
    ): AgentSetupCatalogEntry =
        if (id == null && version == null) {
            defaultSelectable()
        } else {
            require(id != null && version != null) { "setup id and version must be supplied together" }
            requireSelectable(id, version)
        }

    fun requireSelectable(
        id: AgentSetupId,
        version: AgentSetupVersion,
    ): AgentSetupCatalogEntry {
        val ref = AgentSetupRef(id, version)
        val definition = setups.findDefinition(id, version)
        val availability = setups.findAvailability(id, version)
        if (definition == null || availability == null) {
            throw validationException(
                ref = ref,
                code = AgentSetupValidationIssueCode.TARGET_NOT_FOUND,
                message = "agent setup ${id.value}@${version.value} is not registered",
            )
        }
        if (!availability.selectable) {
            throw validationException(
                ref = ref,
                code = AgentSetupValidationIssueCode.TARGET_NOT_SELECTABLE,
                message =
                    availability.unavailableReason
                        ?: "agent setup ${id.value}@${version.value} is not selectable",
            )
        }
        return AgentSetupCatalogEntry(definition = definition, availability = availability)
    }

    private fun validationException(
        ref: AgentSetupRef,
        code: AgentSetupValidationIssueCode,
        message: String,
    ) = AgentSetupValidationException(
        AgentSetupValidationResult(
            target = ref,
            valid = false,
            issues =
                listOf(
                    AgentSetupValidationIssue(
                        code = code,
                        message = message,
                    ),
                ),
        ),
    )
}
