package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.common.command.Command

data class DeleteRepositoryCommand(
    val repositoryId: RepositoryId,
) : Command
