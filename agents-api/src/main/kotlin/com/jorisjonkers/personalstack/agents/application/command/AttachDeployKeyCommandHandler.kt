package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.common.command.CommandHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Static fallback for `known_hosts` if the wizard didn't provide
 * one. Hard-coded values come from GitHub's published SSH host
 * keys (https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints).
 * Update by hand on rotation — the public list shows when GitHub
 * rolls keys (last on March 2023).
 */
private val GITHUB_KNOWN_HOSTS_FALLBACK =
    """
    github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl
    github.com ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=
    github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=
    """.trimIndent()

/**
 * `deployKeysProvider` is an [ObjectProvider] because the Vault-
 * backed [DeployKeyStore] is `@ConditionalOnProperty(spring.cloud
 * .vault.enabled)`: in tests where Vault is disabled the bean is
 * absent and the constructor would otherwise fail to autowire.
 * Resolving lazily lets the handler exist at boot and surface a
 * clear "vault disabled" error only when an operator actually
 * attempts to attach a key.
 */
@Component
class AttachDeployKeyCommandHandler(
    private val links: GithubLinkRepository,
    private val deployKeysProvider: ObjectProvider<DeployKeyStore>,
) : CommandHandler<AttachDeployKeyCommand> {
    @Transactional
    override fun handle(command: AttachDeployKeyCommand) {
        val link =
            links.findById(command.linkId)
                ?: error("github link not found: ${command.linkId}")
        require(command.privateKeyOpenssh.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) {
            "private key must be OpenSSH-format (starts with `-----BEGIN OPENSSH PRIVATE KEY-----`)"
        }
        require(command.publicKeyOpenssh.matches(Regex("^ssh-(ed25519|rsa)\\s.+", RegexOption.DOT_MATCHES_ALL))) {
            "public key must be an OpenSSH single-line key (ssh-ed25519 / ssh-rsa preferred)"
        }
        val deployKeys =
            deployKeysProvider.ifAvailable
                ?: error("vault disabled — deploy-key storage adapter is not loaded")
        val stored =
            deployKeys.store(
                projectId = link.projectId,
                linkId = link.id,
                privateKeyOpenssh = command.privateKeyOpenssh,
                publicKeyOpenssh = command.publicKeyOpenssh,
                knownHosts = command.knownHosts?.takeIf { it.isNotBlank() } ?: GITHUB_KNOWN_HOSTS_FALLBACK,
            )
        links.save(
            link.copy(
                vaultKeyPath = stored.vaultPath,
                deployKeyFingerprint = stored.fingerprint,
                deployKeyAddedAt = Instant.now(),
                updatedAt = Instant.now(),
            ),
        )
    }
}
