package com.jorisjonkers.personalstack.agents.application.setup

import com.jorisjonkers.personalstack.agents.domain.model.GithubLink
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets

/**
 * Renders the deploy-key setup guide from a classpath markdown
 * template. Placeholders use `{{NAME}}` syntax — kept simple
 * because the rendering target is a small fixed set of fields
 * and pulling in Mustache/Pebble for this would be over-engineered.
 *
 * Anything in the template that the API cannot meaningfully fill in
 * is left as the literal placeholder, surfaced to the operator as
 * "the wizard is missing data" rather than rendering empty values.
 */
@Service
class SetupGuideService {
    private val template: String by lazy {
        ClassPathResource("templates/deploy-key-setup.md")
            .inputStream
            .use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    fun render(link: GithubLink): String =
        template
            .replace("{{LINK_NAME}}", link.name)
            .replace("{{REPO_URL}}", link.repoUrl)
            .replace("{{VAULT_KEY_PATH}}", link.vaultKeyPath)
            .replace("{{DEPLOY_KEY_PAGE_URL}}", deployKeyPageUrl(link.repoUrl))

    /**
     * Constructs the GitHub web URL that opens the deploy-keys
     * settings page for the linked repository. Best-effort:
     *
     * - `git@github.com:owner/repo.git`     → https://github.com/owner/repo/settings/keys
     * - `https://github.com/owner/repo.git` → same
     * - anything else                       → owner of the repoUrl
     *                                         unchanged, the operator
     *                                         can paste it into a
     *                                         browser themselves
     */
    private fun deployKeyPageUrl(repoUrl: String): String {
        val trimmed = repoUrl.trim().removeSuffix(".git")
        val sshMatch = Regex("^git@github\\.com:([^/]+)/(.+)$").find(trimmed)
        if (sshMatch != null) {
            val (owner, repo) = sshMatch.destructured
            return "https://github.com/$owner/$repo/settings/keys"
        }
        val httpsMatch = Regex("^https?://github\\.com/([^/]+)/([^/?#]+).*$").find(trimmed)
        if (httpsMatch != null) {
            val (owner, repo) = httpsMatch.destructured
            return "https://github.com/$owner/$repo/settings/keys"
        }
        return repoUrl
    }
}
