package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.common.command.Command

data class OpenPullRequestCommand(
    val workspaceId: WorkspaceId,
    val repoDir: String,
    val title: String,
    val body: String,
    val base: String = "main",
) : Command
