package com.jorisjonkers.personalstack.agents.domain.port

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.Project
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId

interface ProjectsRepository {
    fun save(project: Project): Project

    fun findById(id: ProjectId): Project?

    fun findBySlug(slug: String): Project?

    fun findAll(): List<Project>

    fun delete(id: ProjectId)
}

interface GithubLinkRepository {
    fun save(link: GithubLink): GithubLink

    fun findById(id: GithubLinkId): GithubLink?

    fun findAllByProjectId(projectId: ProjectId): List<GithubLink>

    fun delete(id: GithubLinkId)
}

/**
 * Driven port for the Vault-backed deploy key storage. One pair of
 * (private_key, public_key, known_hosts, fingerprint) per
 * GithubLink. Adapters write to the canonical
 * `secret/data/agents/projects/<pid>/repos/<rid>` path and return
 * the fingerprint that the GithubLink row will mirror.
 */
interface DeployKeyStore {
    data class StoredKey(
        val fingerprint: String,
        val vaultPath: String,
    )

    data class KeyMaterial(
        val privateKey: String,
        val publicKey: String,
        val knownHosts: String,
        val fingerprint: String,
    )

    /**
     * Persist the supplied OpenSSH-format ed25519 keypair at the
     * canonical path for the given link. Returns the SHA-256
     * fingerprint of the public key (in the OpenSSH "SHA256:..."
     * form), which the row mirrors so the UI doesn't have to query
     * Vault to render.
     */
    fun store(
        projectId: ProjectId,
        linkId: GithubLinkId,
        privateKeyOpenssh: String,
        publicKeyOpenssh: String,
        knownHosts: String,
    ): StoredKey

    fun remove(
        projectId: ProjectId,
        linkId: GithubLinkId,
    )

    /**
     * Return the public key half (only) at the link's path. Used
     * by the setup-guide endpoint when the operator paged in
     * mid-flow and we need to remind them which key to paste into
     * GitHub.
     */
    fun readPublicKey(
        projectId: ProjectId,
        linkId: GithubLinkId,
    ): String?

    /**
     * Load the full key material for a link. Returns null if no
     * key is attached. Used by the orchestrator to stamp a
     * workspace-scoped k8s Secret out of the Vault-stored bytes
     * at Pod-creation time.
     */
    fun loadKey(
        projectId: ProjectId,
        linkId: GithubLinkId,
    ): KeyMaterial?

    /**
     * Repository-keyed write path. The redesigned UI invokes this
     * via the repository deploy-key wizard; the underlying Vault
     * path keeps the legacy `secret/data/agents/projects/<pid>/repos/<rid>`
     * shape during the V9 migration window, so the adapter
     * supplies a "synthetic" project id (the repository's own UUID)
     * to retain backwards-compatibility with the orchestrator's
     * legacy loader.
     */
    fun store(
        repositoryId: RepositoryId,
        privateKeyOpenssh: String,
        publicKeyOpenssh: String,
        knownHosts: String,
    ): StoredKey

    fun remove(repositoryId: RepositoryId)

    fun readPublicKey(repositoryId: RepositoryId): String?

    fun loadKey(repositoryId: RepositoryId): KeyMaterial?
}
