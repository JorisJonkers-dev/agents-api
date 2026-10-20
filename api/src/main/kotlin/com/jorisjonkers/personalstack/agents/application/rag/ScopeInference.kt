package com.jorisjonkers.personalstack.agents.application.rag

import com.jorisjonkers.personalstack.agents.domain.model.Workspace

/**
 * Picks the retrieval scope for a workspace:
 *
 *   project:<github-repo-name>  — last path segment of the remote URL
 *                                 minus a trailing `.git`
 *   agent:<workspace-name-slug> — for repo-less Q&A workspaces
 *
 * The function is deterministic and exposed as a top-level helper
 * so unit tests can pin the slug derivation without booting any
 * Spring context.
 */
object ScopeInference {
    fun scopeFor(workspace: Workspace): String {
        val repo = workspace.repoUrl
        if (!repo.isNullOrBlank()) {
            return "project:${repoSlug(repo)}"
        }
        return "agent:${nameSlug(workspace.name)}"
    }

    fun repoSlug(repoUrl: String): String {
        val tail = repoUrl.trim().substringAfterLast('/').substringAfterLast(':')
        return tail.removeSuffix(".git").ifBlank { "unknown" }
    }

    fun nameSlug(name: String): String =
        name
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "workspace" }
}
