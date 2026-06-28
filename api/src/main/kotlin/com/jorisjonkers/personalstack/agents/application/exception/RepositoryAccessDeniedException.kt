package com.jorisjonkers.personalstack.agents.application.exception

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId

/**
 * The deploy key for a repo-backed workspace cannot read its
 * repository, so creating the workspace would leave a runner that can
 * never clone. Thrown by [CreateWorkspaceCommandHandler] when the
 * gateway verify reports `read == false`; mapped to a 422 so the UI
 * surfaces a fix-the-key message instead of a generic 500.
 *
 * `write == false` and an unprotected default branch are NOT fatal —
 * those degrade to recorded warnings, since the chosen posture is
 * read-write + protected main and an unprotected main is an operator
 * concern fixed on GitHub, not a reason to block workspace creation.
 */
class RepositoryAccessDeniedException(
    val repositoryId: RepositoryId,
    val reason: String,
) : RuntimeException("deploy key cannot read repository ${repositoryId.value}: $reason")
