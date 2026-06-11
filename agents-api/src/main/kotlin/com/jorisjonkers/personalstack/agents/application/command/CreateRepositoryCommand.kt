package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.common.command.Command

data class CreateRepositoryCommand(
    val repositoryId: RepositoryId,
    val name: String,
    val repoUrl: String,
    val defaultBranch: String = "main",
) : Command
