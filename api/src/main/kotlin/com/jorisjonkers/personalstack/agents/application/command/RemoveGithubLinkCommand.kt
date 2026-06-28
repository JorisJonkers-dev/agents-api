package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.common.command.Command

data class RemoveGithubLinkCommand(
    val linkId: GithubLinkId,
) : Command
