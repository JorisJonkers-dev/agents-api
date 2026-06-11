package com.jorisjonkers.personalstack.agents.application.command

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import com.jorisjonkers.personalstack.agents.domain.port.DeployKeyStore
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class AttachDeployKeyCommandHandlerTest {
    private val links = mockk<GithubLinkRepository>()
    private val deployKeys = mockk<DeployKeyStore>()
    private val deployKeysProvider =
        mockk<org.springframework.beans.factory.ObjectProvider<DeployKeyStore>> {
            every { ifAvailable } returns deployKeys
        }
    private val handler = AttachDeployKeyCommandHandler(links, deployKeysProvider)

    private val sampleLink =
        GithubLink(
            id = GithubLinkId.random(),
            projectId = ProjectId.random(),
            name = "agents",
            repoUrl = "git@github.com:ExtraToast/agents.git",
            defaultBranch = "main",
            vaultKeyPath = "secret/data/agents/projects/x/repos/y",
            deployKeyFingerprint = null,
            deployKeyAddedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private val validPrivate =
        """
        -----BEGIN OPENSSH PRIVATE KEY-----
        deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef
        -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    private val validPublic =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl test@laptop"

    @Test
    fun `handle stores the key in Vault and mirrors the fingerprint`() {
        every { links.findById(sampleLink.id) } returns sampleLink
        every {
            deployKeys.store(any(), any(), any(), any(), any())
        } returns
            DeployKeyStore.StoredKey(
                fingerprint = "SHA256:abcdef",
                vaultPath = sampleLink.vaultKeyPath,
            )
        val saved = slot<GithubLink>()
        every { links.save(capture(saved)) } answers { saved.captured }

        handler.handle(
            AttachDeployKeyCommand(
                linkId = sampleLink.id,
                privateKeyOpenssh = validPrivate,
                publicKeyOpenssh = validPublic,
                knownHosts = null,
            ),
        )

        assertThat(saved.captured.deployKeyFingerprint).isEqualTo("SHA256:abcdef")
        assertThat(saved.captured.deployKeyAddedAt).isNotNull
        verify { deployKeys.store(sampleLink.projectId, sampleLink.id, validPrivate, validPublic, any()) }
    }

    @Test
    fun `handle uses static known_hosts fallback when none supplied`() {
        every { links.findById(sampleLink.id) } returns sampleLink
        val fallback = slot<String>()
        every {
            deployKeys.store(any(), any(), any(), any(), capture(fallback))
        } returns DeployKeyStore.StoredKey("SHA256:x", sampleLink.vaultKeyPath)
        every { links.save(any()) } answers { firstArg() }

        handler.handle(
            AttachDeployKeyCommand(
                linkId = sampleLink.id,
                privateKeyOpenssh = validPrivate,
                publicKeyOpenssh = validPublic,
                knownHosts = "  ",
            ),
        )

        assertThat(fallback.captured).contains("github.com ssh-ed25519")
    }

    @Test
    fun `handle rejects malformed private key`() {
        every { links.findById(sampleLink.id) } returns sampleLink
        assertThrows<IllegalArgumentException> {
            handler.handle(
                AttachDeployKeyCommand(
                    linkId = sampleLink.id,
                    privateKeyOpenssh = "not a key",
                    publicKeyOpenssh = validPublic,
                ),
            )
        }
    }
}
