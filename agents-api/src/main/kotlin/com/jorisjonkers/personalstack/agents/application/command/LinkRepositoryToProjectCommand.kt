package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.common.command.Command

data class LinkRepositoryToProjectCommand(
    val projectId: ProjectId,
    val repositoryId: RepositoryId,
) : Command
