package com.jorisjonkers.personalstack.agents.application.rag

import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ScopeInferenceTest {
    @Test
    fun `repo-backed workspace yields project scope from URL tail without dot-git`() {
        val ws = workspace(name = "anything", repoUrl = "git@github.com:owner/agents.git")
        assertThat(ScopeInference.scopeFor(ws)).isEqualTo("project:agents")
    }

    @Test
    fun `https remote URL works the same as ssh`() {
        val ws = workspace(repoUrl = "https://github.com/owner/dotfiles")
        assertThat(ScopeInference.scopeFor(ws)).isEqualTo("project:dotfiles")
    }

    @Test
    fun `repo-less workspace yields agent scope from slugged name`() {
        val ws = workspace(name = "Migration Plan #1!", repoUrl = null)
        assertThat(ScopeInference.scopeFor(ws)).isEqualTo("agent:migration-plan-1")
    }

    @Test
    fun `empty name falls back to workspace literal`() {
        val ws = workspace(name = "   ", repoUrl = null)
        assertThat(ScopeInference.scopeFor(ws)).isEqualTo("agent:workspace")
    }

    @Test
    fun `repoSlug strips trailing dot-git and handles bare names`() {
        assertThat(ScopeInference.repoSlug("git@github.com:owner/repo.git")).isEqualTo("repo")
        assertThat(ScopeInference.repoSlug("https://github.com/owner/repo")).isEqualTo("repo")
        assertThat(ScopeInference.repoSlug("repo")).isEqualTo("repo")
        assertThat(ScopeInference.repoSlug("")).isEqualTo("unknown")
    }

    private fun workspace(
        name: String = "demo",
        repoUrl: String? = null,
    ) = Workspace(
        id = WorkspaceId.random(),
        name = name,
        repoUrl = repoUrl,
        branch = null,
        podName = null,
        pvcName = null,
        gatewayEndpoint = null,
        status = WorkspaceStatus.READY,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
