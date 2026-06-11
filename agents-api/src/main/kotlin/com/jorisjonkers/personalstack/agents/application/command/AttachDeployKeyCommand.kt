package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.common.command.Command

/**
 * The paste-the-key flow. Operator generates a deploy key locally
 * (the setup guide walks them through `ssh-keygen -t ed25519`),
 * pastes the private + public halves into the wizard, the API
 * lands the pair in Vault at the link's pre-allocated path and
 * mirrors the fingerprint into the row.
 */
data class AttachDeployKeyCommand(
    val linkId: GithubLinkId,
    val privateKeyOpenssh: String,
    val publicKeyOpenssh: String,
    /**
     * Optional override for `known_hosts`. The setup guide tells
     * the operator to run `ssh-keyscan github.com` and paste the
     * result; if blank we fall back to a static github.com entry
     * baked into the API (the keys rotate rarely; the runner image
     * also re-scans on its own at boot for belt-and-braces).
     */
    val knownHosts: String? = null,
) : Command
