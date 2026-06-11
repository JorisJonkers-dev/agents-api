package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.application.VerifyRepositoryAccess
import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.agents.domain.port.RepositoryRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Static fallback for `known_hosts` when the wizard left the field
 * blank. The published GitHub SSH host keys
 * (https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints).
 * Update by hand on rotation — last GitHub rotation was March 2023.
 */
private val GITHUB_KNOWN_HOSTS_FALLBACK =
    """
    github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl
    github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=
    github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=
    """.trimIndent()

/**
 * `deployKeysProvider` is an [ObjectProvider] for the same reason
 * [AttachDeployKeyCommandHandler] uses one — the Vault-backed
 * [DeployKeyStore] is `@ConditionalOnProperty(spring.cloud.vault.enabled)`.
 */
@Component
class AttachRepositoryDeployKeyCommandHandler(
    private val repositories: RepositoryRepository,
    private val deployKeysProvider: ObjectProvider<DeployKeyStore>,
    private val verifyAccess: VerifyRepositoryAccess,
) : CommandHandler<AttachRepositoryDeployKeyCommand> {
    @Transactional
    override fun handle(command: AttachRepositoryDeployKeyCommand) {
        val repository =
            repositories.findById(command.repositoryId)
                ?: error("repository not found: ${command.repositoryId}")
        validateKeyMaterial(command)
        val deployKeys =
            deployKeysProvider.ifAvailable
                ?: error("vault disabled — deploy-key storage adapter is not loaded")
        // Re-running attach IS the key-replacement path: the Vault
        // store overwrites the secret at the repository's fixed path
        // (DeployKeyStore.store does an unconditional writeSecret), the
        // fingerprint mirror below replaces the old one, and the
        // verification re-runs against the new key.
        val stored =
            deployKeys.store(
                repositoryId = repository.id,
                privateKeyOpenssh = command.privateKeyOpenssh,
                publicKeyOpenssh = command.publicKeyOpenssh,
                knownHosts = command.knownHosts?.takeIf { it.isNotBlank() } ?: GITHUB_KNOWN_HOSTS_FALLBACK,
            )
        val result = verifyAccess.verify(repository.repoUrl, repository.defaultBranch)
        repositories.save(
            repository.copy(
                vaultKeyPath = stored.vaultPath,
                deployKeyFingerprint = stored.fingerprint,
                deployKeyAddedAt = Instant.now(),
                updatedAt = Instant.now(),
                verification = result.toAccessVerification(),
            ),
        )
    }

    private fun validateKeyMaterial(command: AttachRepositoryDeployKeyCommand) {
        require(command.privateKeyOpenssh.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) {
            "private key must be OpenSSH-format (starts with `-----BEGIN OPENSSH PRIVATE KEY-----`)"
        }
        require(command.publicKeyOpenssh.matches(Regex("^ssh-(ed25519|rsa)\\s.+", RegexOption.DOT_MATCHES_ALL))) {
            "public key must be an OpenSSH single-line key (ssh-ed25519 / ssh-rsa preferred)"
        }
    }
}
