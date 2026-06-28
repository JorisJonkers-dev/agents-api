package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion

interface AgentSetupRepository {
    fun saveDefinition(definition: AgentSetupDefinition): AgentSetupDefinition

    fun saveAvailability(availability: AgentSetupAvailability): AgentSetupAvailability

    fun saveCatalogEntry(entry: AgentSetupCatalogEntry): AgentSetupCatalogEntry {
        saveDefinition(entry.definition)
        saveAvailability(entry.availability)
        return entry
    }

    fun findDefinition(
        id: AgentSetupId,
        version: AgentSetupVersion,
    ): AgentSetupDefinition?

    fun findAvailability(
        id: AgentSetupId,
        version: AgentSetupVersion,
    ): AgentSetupAvailability?

    fun findSelectable(): List<AgentSetupCatalogEntry>

    fun findDefaultSelectable(): AgentSetupCatalogEntry?

    fun backfillRuntimeDerivedSelections(
        setupId: AgentSetupId,
        version: AgentSetupVersion,
    ): Int
}
