package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.common.command.Command

/**
 * Paste-the-key flow keyed by repository. The operator generates an
 * ed25519 pair locally, the wizard sends both halves here, the API
 * writes them to the per-repository Vault path and mirrors the
 * fingerprint into the Repository row.
 */
data class AttachRepositoryDeployKeyCommand(
    val repositoryId: RepositoryId,
    val privateKeyOpenssh: String,
    val publicKeyOpenssh: String,
    val knownHosts: String? = null,
) : Command
