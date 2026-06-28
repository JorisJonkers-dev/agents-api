package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.port.ProjectsRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class CreateProjectCommandHandler(
    private val projects: ProjectsRepository,
) : CommandHandler<CreateProjectCommand> {
    @Transactional
    override fun handle(command: CreateProjectCommand) {
        require(command.name.isNotBlank()) { "project name must not be blank" }
        require(command.slug.matches(Regex("^[a-z0-9][a-z0-9-]{0,62}$"))) {
            "slug must match ^[a-z0-9][a-z0-9-]{0,62}$"
        }
        val existing = projects.findBySlug(command.slug)
        if (existing != null && existing.id != command.projectId) {
            error("slug already in use: ${command.slug}")
        }
        val now = Instant.now()
        projects.save(
            Project(
                id = command.projectId,
                name = command.name.trim(),
                slug = command.slug,
                description = command.description.trim(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
