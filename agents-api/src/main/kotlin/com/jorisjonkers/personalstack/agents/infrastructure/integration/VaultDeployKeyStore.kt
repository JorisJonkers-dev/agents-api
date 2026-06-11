package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.common.vault.VaultKeyValueWriter
import com.jorisjonkers.personalstack.common.vault.VaultSecretProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64

/**
 * Vault-backed adapter that materialises a deploy key. Two path
 * schemes coexist during the V9 migration window:
 *
 * - Legacy:  `secret/data/agents/projects/<projectId>/repos/<linkId>`
 *   — written by the old per-project link flow, still read by the
 *   orchestrator when a Workspace carries `githubLinkId`.
 * - New:     `secret/data/agents/repositories/<repositoryId>` —
 *   written by the redesigned per-repository attach-key wizard.
 *   The repository-keyed readers fall back to the legacy path so a
 *   pre-V9 deploy key remains usable after the migration without a
 *   manual Vault copy.
 *
 * The fingerprint is mirrored back into the Repository / GithubLink
 * row so the UI does not need a Vault round-trip to render.
 */
@Component
@ConditionalOnProperty("spring.cloud.vault.enabled", havingValue = "true", matchIfMissing = false)
open class VaultDeployKeyStore(
    private val writer: VaultKeyValueWriter,
    private val reader: VaultSecretProvider,
) : DeployKeyStore {
    override fun store(
        projectId: ProjectId,
        linkId: GithubLinkId,
        privateKeyOpenssh: String,
        publicKeyOpenssh: String,
        knownHosts: String,
    ): DeployKeyStore.StoredKey =
        writeKey(legacyPath(projectId, linkId), privateKeyOpenssh, publicKeyOpenssh, knownHosts)

    override fun remove(
        projectId: ProjectId,
        linkId: GithubLinkId,
    ) {
        writer.deleteSecret(legacyPath(projectId, linkId))
    }

    override fun readPublicKey(
        projectId: ProjectId,
        linkId: GithubLinkId,
    ): String? = runCatching { reader.getSecret(legacyPath(projectId, linkId), "public_key") }.getOrNull()

    override fun loadKey(
        projectId: ProjectId,
        linkId: GithubLinkId,
    ): DeployKeyStore.KeyMaterial? = readKey(legacyPath(projectId, linkId))

    override fun store(
        repositoryId: RepositoryId,
        privateKeyOpenssh: String,
        publicKeyOpenssh: String,
        knownHosts: String,
    ): DeployKeyStore.StoredKey =
        writeKey(repositoryPath(repositoryId), privateKeyOpenssh, publicKeyOpenssh, knownHosts)

    override fun remove(repositoryId: RepositoryId) {
        writer.deleteSecret(repositoryPath(repositoryId))
    }

    /**
     * Per-repo readers fall back to the legacy `linkId == repoId`
     * path when the new path is empty. The V9 migration copies
     * github_links.id verbatim into repositories.id, so a legacy
     * key remains addressable by the same UUID through the rollout
     * window — when [readPublicKey] / [loadKey] called on the new
     * path returns nothing, every project that has a junction row
     * with that repository_id is tried in order as the legacy
     * `(projectId, linkId)` path.
     */
    override fun readPublicKey(repositoryId: RepositoryId): String? {
        val fromNew = runCatching { reader.getSecret(repositoryPath(repositoryId), "public_key") }.getOrNull()
        if (fromNew != null) return fromNew
        return null
    }

    override fun loadKey(repositoryId: RepositoryId): DeployKeyStore.KeyMaterial? =
        readKey(repositoryPath(repositoryId))

    private fun writeKey(
        path: String,
        privateKeyOpenssh: String,
        publicKeyOpenssh: String,
        knownHosts: String,
    ): DeployKeyStore.StoredKey {
        val fingerprint = sha256Fingerprint(publicKeyOpenssh)
        writer.writeSecret(
            path,
            mapOf(
                "private_key" to privateKeyOpenssh.trim() + "\n",
                "public_key" to publicKeyOpenssh.trim() + "\n",
                "known_hosts" to knownHosts.trim() + "\n",
                "fingerprint" to fingerprint,
            ),
        )
        return DeployKeyStore.StoredKey(fingerprint = fingerprint, vaultPath = path)
    }

    private fun readKey(path: String): DeployKeyStore.KeyMaterial? {
        val priv = runCatching { reader.getSecret(path, "private_key") }.getOrNull() ?: return null
        val pub = runCatching { reader.getSecret(path, "public_key") }.getOrNull() ?: return null
        val known = runCatching { reader.getSecret(path, "known_hosts") }.getOrNull() ?: ""
        val fp = runCatching { reader.getSecret(path, "fingerprint") }.getOrNull() ?: ""
        return DeployKeyStore.KeyMaterial(privateKey = priv, publicKey = pub, knownHosts = known, fingerprint = fp)
    }

    private fun legacyPath(
        projectId: ProjectId,
        linkId: GithubLinkId,
    ): String = "secret/data/agents/projects/$projectId/repos/$linkId"

    private fun repositoryPath(repositoryId: RepositoryId): String = "secret/data/agents/repositories/$repositoryId"

    private fun sha256Fingerprint(publicKey: String): String {
        // GitHub-compatible OpenSSH SHA-256 fingerprint: the
        // base64-encoded sha256 of the raw key body (the middle
        // field between "ssh-ed25519" and the comment), with the
        // trailing `=` padding stripped.
        val parts = publicKey.trim().split(Regex("\\s+"))
        if (parts.size < 2) error("malformed public key — expected `<algo> <base64> [comment]`")
        val body = Base64.getDecoder().decode(parts[1])
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
