package com.jorisjonkers.personalstack.agents.infrastructure.integration

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.web.client.RestClient

class GitHubBranchProtectionClientTest {
    private fun props(
        token: String = "",
        base: String = "https://api.github.com",
    ) = AgentRuntimeProperties(
        namespace = "agents-system",
        image = "img",
        serviceAccount = "sa",
        claudeCredentialsPvc = "c",
        codexCredentialsPvc = "x",
        githubDeployKeySecret = "k",
        githubApiToken = token,
        githubApiBaseUrl = base,
    )

    @ParameterizedTest
    @CsvSource(
        // ssh scp-like, with and without .git
        "git@github.com:JorisJonkers-dev/agents.git, JorisJonkers-dev, agents",
        "git@github.com:JorisJonkers-dev/agents,     JorisJonkers-dev, agents",
        // ssh url form
        "ssh://git@github.com/JorisJonkers-dev/agents.git, JorisJonkers-dev, agents",
        // https with and without .git, trailing slash
        "https://github.com/JorisJonkers-dev/agents.git,  JorisJonkers-dev, agents",
        "https://github.com/JorisJonkers-dev/agents,       JorisJonkers-dev, agents",
        "https://github.com/JorisJonkers-dev/agents/,      JorisJonkers-dev, agents",
        // https with embedded credentials
        "https://x-access-token:tok@github.com/JorisJonkers-dev/repo.git, JorisJonkers-dev, repo",
    )
    fun `parses owner and repo from ssh and https URLs`(
        url: String,
        owner: String,
        repo: String,
    ) {
        val parsed = GitHubBranchProtectionClient.parseOwnerRepo(url)
        assertThat(parsed).isNotNull
        assertThat(parsed!!.owner).isEqualTo(owner)
        assertThat(parsed.repo).isEqualTo(repo)
    }

    @ParameterizedTest
    @CsvSource(
        "not-a-url",
        "git@github.com:onlyowner",
        "https://github.com/onlyowner",
        "''",
    )
    fun `returns null for unparseable repo URLs`(url: String) {
        assertThat(GitHubBranchProtectionClient.parseOwnerRepo(url)).isNull()
    }

    @Test
    fun `returns null when no token is configured — never hits the API`() {
        val restClient = mockk<RestClient>() // unused: must short-circuit before any call
        val client = GitHubBranchProtectionClient(restClient, props(token = ""))
        assertThat(client.isBranchProtected("git@github.com:JorisJonkers-dev/agents.git", "main")).isNull()
    }

    @Test
    fun `returns null when the repo URL cannot be parsed even with a token`() {
        val restClient = mockk<RestClient>()
        val client = GitHubBranchProtectionClient(restClient, props(token = "tok"))
        assertThat(client.isBranchProtected("not-a-url", "main")).isNull()
    }
}
