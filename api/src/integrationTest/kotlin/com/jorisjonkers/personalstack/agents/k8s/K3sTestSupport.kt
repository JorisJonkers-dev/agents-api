package com.jorisjonkers.personalstack.agents.k8s

import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared fixtures for the Fabric8 orchestrator integration tests.
 *
 * `K3sContainer` boots a real (single-node) Kubernetes API in ~20s
 * via Docker-in-Docker. The container is amd64-only; CI (ubuntu-
 * latest) supports this out of the box. On Apple Silicon locally,
 * Rosetta + Docker Desktop's emulation works; the tests will
 * otherwise be skipped if `docker info --format '{{.OSType}}'`
 * disagrees.
 *
 * The helpers below cover the three reusable pieces the orchestrator
 * tests need:
 *
 * 1. [createAdminClient] — a `KubernetesClient` authenticated as
 *    cluster-admin via the kubeconfig the K3sContainer emits. Used
 *    for test setup (namespaces, ServiceAccounts) and assertions.
 *
 * 2. [createServiceAccountScopedClient] — a `KubernetesClient` that
 *    impersonates the production agents-api ServiceAccount. RBAC
 *    enforces against the impersonated identity exactly as it would
 *    for a real authenticated request, so the orchestrator under
 *    test exercises the same permission boundary as production. Why
 *    impersonation over TokenRequest: it skips audience-binding +
 *    cert-trust mechanics that vary between Kubernetes distributions
 *    and would only obscure what the test is actually checking.
 *
 * 3. [applyProductionRbac] / [applyPre372RestrictedRbac] — apply the
 *    standalone repo RBAC contract, or a deliberately restricted copy
 *    that omits the `patch` verb on PVCs.
 */
object K3sTestSupport {
    /**
     * The image tag mirrors the cluster's k3s minor version. Bump
     * deliberately when the production cluster does — the orchestrator
     * only exercises core APIs that are stable across releases, but a
     * matched tag avoids surprises from API-validation drift.
     */
    private val K3S_IMAGE = DockerImageName.parse("rancher/k3s:v1.30.4-k3s1")

    /**
     * Two namespaces the orchestrator and its RoleBinding reference.
     * Production has these created via separate Flux Kustomizations; in
     * the test they're a one-liner per Namespace.
     */
    const val AGENTS_NAMESPACE = "agents-system"
    const val AGENTS_SERVICE_ACCOUNT = "agents-api"
    const val ROLE_NAME = "agents-api-runner-controller"

    /**
     * The runner Pods set `spec.serviceAccountName: agent-runner` (see
     * [AgentRuntimeProperties.serviceAccount]). K3s' admission chain
     * validates SA existence at Pod create time; missing SA → 403 with
     * "error looking up service account ... not found", so the test
     * pre-creates it. Production gets the same SA via the agents-
     * domain Kustomization; we just stamp it here.
     */
    const val RUNNER_SERVICE_ACCOUNT = "agent-runner"

    fun newContainer(): K3sContainer =
        K3sContainer(K3S_IMAGE)
            // No need for Traefik in tests; faster boot.
            .withCommand("server", "--disable=traefik", "--disable=servicelb", "--disable=metrics-server")

    fun createAdminClient(container: K3sContainer): KubernetesClient =
        KubernetesClientBuilder()
            .withConfig(
                io.fabric8.kubernetes.client.Config
                    .fromKubeconfig(container.kubeConfigYaml),
            ).build()

    /**
     * Bootstrap the two namespaces and the agents-api SA the
     * production RoleBinding subjects on. Idempotent (server-side
     * apply); safe to call once per test.
     */
    fun bootstrapNamespacesAndServiceAccount(admin: KubernetesClient) {
        admin
            .namespaces()
            .resource(
                NamespaceBuilder()
                    .withNewMetadata()
                    .withName(AGENTS_NAMESPACE)
                    .endMetadata()
                    .build(),
            ).serverSideApply()
        admin
            .serviceAccounts()
            .inNamespace(AGENTS_NAMESPACE)
            .resource(
                ServiceAccountBuilder()
                    .withNewMetadata()
                    .withName(AGENTS_SERVICE_ACCOUNT)
                    .withNamespace(AGENTS_NAMESPACE)
                    .endMetadata()
                    .build(),
            ).serverSideApply()
        admin
            .serviceAccounts()
            .inNamespace(AGENTS_NAMESPACE)
            .resource(
                ServiceAccountBuilder()
                    .withNewMetadata()
                    .withName(RUNNER_SERVICE_ACCOUNT)
                    .withNamespace(AGENTS_NAMESPACE)
                    .endMetadata()
                    .build(),
            ).serverSideApply()
    }

    /**
     * Apply the standalone Role + RoleBinding the API requires to
     * manage runner Pods, Services, PVCs, and per-workspace Secrets.
     */
    fun applyProductionRbac(admin: KubernetesClient) {
        applyRbac(admin, includePvcPatch = true)
    }

    /**
     * Programmatically build a Role + RoleBinding identical to the
     * production version EXCEPT for the missing `patch` verb on
     * persistentvolumeclaims. This is exactly the state #372 fixed —
     * the orchestrator's `serverSideApply()` (which is a PATCH) on
     * the workspace PVC will fail with a 403. Used by the negative
     * test that locks the regression in.
     */
    fun applyPre372RestrictedRbac(admin: KubernetesClient) {
        applyRbac(admin, includePvcPatch = false)
    }

    private fun applyRbac(
        admin: KubernetesClient,
        includePvcPatch: Boolean,
    ) {
        val pvcVerbs =
            buildList {
                addAll(listOf("get", "list", "watch", "create", "delete"))
                if (includePvcPatch) add("patch")
            }
        admin
            .rbac()
            .roles()
            .inNamespace(AGENTS_NAMESPACE)
            .resource(runnerControllerRole(pvcVerbs))
            .serverSideApply()
        admin
            .rbac()
            .roleBindings()
            .inNamespace(AGENTS_NAMESPACE)
            .resource(runnerControllerBinding())
            .serverSideApply()
    }

    private fun runnerControllerRole(pvcVerbs: List<String>) =
        RoleBuilder()
            .withNewMetadata()
            .withName(ROLE_NAME)
            .withNamespace(AGENTS_NAMESPACE)
            .endMetadata()
            .withRules(
                PolicyRuleBuilder()
                    .withApiGroups("")
                    .withResources("pods", "pods/log", "services")
                    .withVerbs("get", "list", "watch", "create", "delete", "patch")
                    .build(),
                // Deliberately omits "patch" — the regression.
                PolicyRuleBuilder()
                    .withApiGroups("")
                    .withResources("persistentvolumeclaims")
                    .withVerbs(pvcVerbs)
                    .build(),
                PolicyRuleBuilder()
                    .withApiGroups("")
                    .withResources("secrets")
                    .withVerbs("get", "list", "watch", "create", "delete", "patch")
                    .build(),
            ).build()

    private fun runnerControllerBinding() =
        RoleBindingBuilder()
            .withNewMetadata()
            .withName(ROLE_NAME)
            .withNamespace(AGENTS_NAMESPACE)
            .endMetadata()
            .withSubjects(
                SubjectBuilder()
                    .withKind("ServiceAccount")
                    .withName(AGENTS_SERVICE_ACCOUNT)
                    .withNamespace(AGENTS_NAMESPACE)
                    .build(),
            ).withRoleRef(
                RoleRefBuilder()
                    .withKind("Role")
                    .withName(ROLE_NAME)
                    .withApiGroup("rbac.authorization.k8s.io")
                    .build(),
            ).build()

    /**
     * Build a fresh client that impersonates the agents-api
     * ServiceAccount. RBAC enforces against the impersonated
     * identity exactly as it would for a real authenticated request,
     * which is what the orchestrator tests need to exercise. The
     * impersonation flags mirror what a real SA-bound request sees
     * via the API server's authentication chain — the user is
     * `system:serviceaccount:<ns>:<name>`, the groups include
     * `system:serviceaccounts`, `system:serviceaccounts:<ns>`, and
     * `system:authenticated`. We start from the admin
     * `kubeConfigYaml` to inherit the API URL, CA cert, and admin
     * credentials (impersonation requires the underlying client to
     * have `impersonate` verb cluster-admin grants by default).
     */
    fun createServiceAccountScopedClient(container: K3sContainer): KubernetesClient {
        val cfg =
            ConfigBuilder(
                io.fabric8.kubernetes.client.Config
                    .fromKubeconfig(container.kubeConfigYaml),
            ).withImpersonateUsername("system:serviceaccount:$AGENTS_NAMESPACE:$AGENTS_SERVICE_ACCOUNT")
                .withImpersonateGroups(
                    "system:serviceaccounts",
                    "system:serviceaccounts:$AGENTS_NAMESPACE",
                    "system:authenticated",
                ).build()
        return KubernetesClientBuilder().withConfig(cfg).build()
    }
}
