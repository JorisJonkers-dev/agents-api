package com.jorisjonkers.personalstack.agents.infrastructure.k8s

import com.jorisjonkers.personalstack.agents.config.AgentRuntimeProperties
import com.jorisjonkers.personalstack.agents.domain.model.AgentCredentialProvider
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.port.AgentCredentialRepository
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import java.util.Base64

/**
 * Manages the per-workspace credential Secret in Kubernetes. Extracted from
 * Fabric8AgentRunnerOrchestrator to keep that class below the TooManyFunctions
 * and LargeClass thresholds.
 */
internal class RunnerCredentialSecretManager(
    private val client: KubernetesClient,
    private val props: AgentRuntimeProperties,
    private val credentialsProvider: ObjectProvider<AgentCredentialRepository>,
) {
    private val log = LoggerFactory.getLogger(RunnerCredentialSecretManager::class.java)

    internal data class CredentialSecret(
        val name: String,
        val hasClaude: Boolean,
        val hasClaudeCredentialsJson: Boolean,
        val hasClaudeAccountJson: Boolean,
        val hasCodex: Boolean,
        val hasCodexConfig: Boolean,
    )

    fun credentialSecretName(short: String): String = "agent-runner-credentials-$short"

    fun ensureCredentialSecret(
        workspace: Workspace,
        short: String,
    ): CredentialSecret? {
        val name = credentialSecretName(short)
        val data =
            credentialSecretData(workspace)
                ?: run {
                    client
                        .secrets()
                        .inNamespace(props.namespace)
                        .withName(name)
                        .delete()
                    return null
                }
        client
            .secrets()
            .inNamespace(props.namespace)
            .resource(
                SecretBuilder()
                    .withNewMetadata()
                    .withName(name)
                    .withNamespace(props.namespace)
                    .withLabels<String, String>(
                        mapOf(
                            "app.kubernetes.io/part-of" to "agent-runner",
                            "agent-runner/workspace-id" to short,
                        ),
                    ).endMetadata()
                    .withType("Opaque")
                    .withData<String, String>(data)
                    .build(),
            ).serverSideApply()
        return CredentialSecret(
            name = name,
            hasClaude = data.containsKey("claude_oauth_token"),
            hasClaudeCredentialsJson = data.containsKey("claude_credentials_json"),
            hasClaudeAccountJson = data.containsKey("claude_account_json"),
            hasCodex = data.containsKey("codex_auth_json"),
            hasCodexConfig = data.containsKey("codex_config_toml"),
        )
    }

    private fun credentialSecretData(workspace: Workspace): Map<String, String>? {
        val owner = workspace.ownerUserId?.takeIf { it.isNotBlank() } ?: return null
        val store = credentialsProvider.ifAvailable ?: return null
        val data =
            buildMap {
                val claude = loadCredential(store, owner, AgentCredentialProvider.CLAUDE)
                claude?.payload?.get("oauth_token")?.takeIf { it.isNotBlank() }?.let {
                    put("claude_oauth_token", b64(it))
                }
                claude?.payload?.get("credentials_json")?.takeIf { it.isNotBlank() }?.let {
                    put("claude_credentials_json", b64(it))
                }
                claude?.payload?.get("account_json")?.takeIf { it.isNotBlank() }?.let {
                    put("claude_account_json", b64(it))
                }
                val codex = loadCredential(store, owner, AgentCredentialProvider.CODEX)
                val codexAuth = codex?.payload?.get("auth_json")?.takeIf { it.isNotBlank() }
                val codexConfig = codex?.payload?.get("config_toml")?.takeIf { it.isNotBlank() }
                if (codexAuth != null) {
                    put("codex_auth_json", b64(codexAuth))
                    codexConfig?.let { put("codex_config_toml", b64(it)) }
                }
            }
        return data.takeIf { it.isNotEmpty() }
    }

    private fun loadCredential(
        store: AgentCredentialRepository,
        owner: String,
        provider: AgentCredentialProvider,
    ) = runCatching { store.find(owner, provider) }
        .onFailure { log.warn("could not load {} credential for workspace owner", provider) }
        .getOrNull()
        ?.takeUnless { it.valid == false }

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())
}
