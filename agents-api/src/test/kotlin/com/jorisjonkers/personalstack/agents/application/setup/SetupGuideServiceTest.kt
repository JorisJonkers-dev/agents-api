package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import com.jorisjonkers.personalstack.agents.domain.model.GithubLinkId
import com.jorisjonkers.personalstack.agents.domain.model.ProjectId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class SetupGuideServiceTest {
    private val service = SetupGuideService()

    @Test
    fun `renders all placeholders for an ssh remote`() {
        val md = service.render(link(repoUrl = "git@github.com:ExtraToast/agents.git"))
        assertThat(md).contains("Deploy-key setup for `auth-service`")
        assertThat(md).contains("git@github.com:ExtraToast/agents.git")
        assertThat(md).contains("secret/data/agents/projects/")
        assertThat(md).contains("https://github.com/ExtraToast/agents/settings/keys")
        assertThat(md).doesNotContain("{{")
    }

    @Test
    fun `renders all placeholders for an https remote with trailing slash`() {
        val md = service.render(link(repoUrl = "https://github.com/ExtraToast/dotfiles/"))
        assertThat(md).contains("https://github.com/ExtraToast/dotfiles/settings/keys")
        assertThat(md).doesNotContain("{{")
    }

    @Test
    fun `unknown remote shapes leave repo URL unchanged for the deploy page link`() {
        val md = service.render(link(repoUrl = "git://other.example.com/r"))
        assertThat(md).contains("git://other.example.com/r")
        assertThat(md).doesNotContain("{{")
    }

    private fun link(repoUrl: String) =
        GithubLink(
            id = GithubLinkId.random(),
            projectId = ProjectId.random(),
            name = "auth-service",
            repoUrl = repoUrl,
            defaultBranch = "main",
            vaultKeyPath = "secret/data/agents/projects/abc/repos/def",
            deployKeyFingerprint = null,
            deployKeyAddedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
}
