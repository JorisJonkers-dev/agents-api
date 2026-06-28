package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.port.AgentSetupRepository
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class AgentSetupCatalogInitializer(
    private val properties: AgentRuntimeProperties,
    private val setups: AgentSetupRepository,
) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        val now = Instant.now()
        val entries = properties.setupCatalogEntries(now)
        entries.forEach { setups.saveCatalogEntry(it) }
        entries
            .firstOrNull { it.availability.defaultSelectable }
            ?.let { setups.backfillRuntimeDerivedSelections(it.definition.id, it.definition.version) }
    }
}
