package com.jorisjonkers.personalstack.agents.infrastructure.credential

import com.jorisjonkers.personalstack.agents.domain.model.Repository
import com.jorisjonkers.personalstack.agents.domain.model.RepositoryId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class GitCredentialRepoScopeTest {
    private fun repo(url: String) =
        Repository(
            id = RepositoryId.random(),
            name = url.substringAfterLast("/").removeSuffix(".git"),
            repoUrl = url,
            defaultBranch = "main",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    @Test
    fun `allows a repo that is one of the workspace's own`() {
        val agents = repo("https://github.com/JorisJonkers-dev/agents.git")
        val other = repo("https://github.com/JorisJonkers-dev/other.git")

        val decision =
            GitCredentialRepoScope.resolve(
                "https://github.com/JorisJonkers-dev/agents.git",
                listOf(agents, other),
            )

        assertThat(decision).isInstanceOf(GitCredentialRepoScope.Decision.Allowed::class.java)
        assertThat((decision as GitCredentialRepoScope.Decision.Allowed).requested).isEqualTo(agents)
        assertThat(decision.workspaceRepos).containsExactly(agents, other)
    }

    @Test
    fun `matches across url forms — git@ vs https, trailing dot-git`() {
        val agents = repo("https://github.com/JorisJonkers-dev/agents.git")

        val decision = GitCredentialRepoScope.resolve("git@github.com:JorisJonkers-dev/agents.git", listOf(agents))

        assertThat(decision).isInstanceOf(GitCredentialRepoScope.Decision.Allowed::class.java)
    }

    @Test
    fun `denies a repo the workspace does not own`() {
        val agents = repo("https://github.com/JorisJonkers-dev/agents.git")

        val decision =
            GitCredentialRepoScope.resolve(
                "https://github.com/some-other-org/private-repo.git",
                listOf(agents),
            )

        assertThat(decision).isInstanceOf(GitCredentialRepoScope.Decision.Denied::class.java)
        assertThat((decision as GitCredentialRepoScope.Decision.Denied).reason)
            .isEqualTo("repository is not attached to this workspace")
    }

    @Test
    fun `denies an unparseable repo url`() {
        val decision = GitCredentialRepoScope.resolve("not-a-url", emptyList())

        assertThat(decision).isInstanceOf(GitCredentialRepoScope.Decision.Denied::class.java)
        assertThat((decision as GitCredentialRepoScope.Decision.Denied).reason).isEqualTo("unparseable repository URL")
    }
}
