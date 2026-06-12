package com.jorisjonkers.personalstack.agents.contract

import com.jorisjonkers.personalstack.agents.application.RepositoryVerificationService
import com.jorisjonkers.personalstack.agents.application.chat.ChatAnswerStreamService
import com.jorisjonkers.personalstack.agents.application.maintenance.RunnerMaintenanceService
import com.jorisjonkers.personalstack.agents.application.query.ChatSessionQueryService
import com.jorisjonkers.personalstack.agents.application.query.GetConversationQueryService
import com.jorisjonkers.personalstack.agents.application.query.GetMessageQueryService
import com.jorisjonkers.personalstack.agents.application.query.GetTurnHistoryQueryService
import com.jorisjonkers.personalstack.agents.application.query.GetWorkspaceQueryService
import com.jorisjonkers.personalstack.agents.application.query.ListWorkspacesQueryService
import com.jorisjonkers.personalstack.agents.application.query.ProjectQueryService
import com.jorisjonkers.personalstack.agents.application.query.RepositoryQueryService
import com.jorisjonkers.personalstack.agents.application.sessionbinding.RestartAgentSessionService
import com.jorisjonkers.personalstack.agents.application.setup.SetupGuideService
import com.jorisjonkers.personalstack.agents.config.OpenApiConfig
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.GithubLinkRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.GitHubAppInstallationTokenClient
import com.jorisjonkers.personalstack.agents.infrastructure.web.AdminRunnerController
import com.jorisjonkers.personalstack.agents.infrastructure.web.AgentRunnerUnavailableExceptionHandler
import com.jorisjonkers.personalstack.agents.infrastructure.web.AgentSessionController
import com.jorisjonkers.personalstack.agents.infrastructure.web.ChatSessionController
import com.jorisjonkers.personalstack.agents.infrastructure.web.ConversationController
import com.jorisjonkers.personalstack.agents.infrastructure.web.GitController
import com.jorisjonkers.personalstack.agents.infrastructure.web.HealthController
import com.jorisjonkers.personalstack.agents.infrastructure.web.InternalGitHubTokenController
import com.jorisjonkers.personalstack.agents.infrastructure.web.KubernetesExceptionHandler
import com.jorisjonkers.personalstack.agents.infrastructure.web.MessageController
import com.jorisjonkers.personalstack.agents.infrastructure.web.ProjectController
import com.jorisjonkers.personalstack.agents.infrastructure.web.RepositoryAccessDeniedExceptionHandler
import com.jorisjonkers.personalstack.agents.infrastructure.web.RepositoryController
import com.jorisjonkers.personalstack.agents.infrastructure.web.SetupGuideController
import com.jorisjonkers.personalstack.agents.infrastructure.web.WorkspaceController
import com.jorisjonkers.personalstack.common.command.CommandBus
import com.jorisjonkers.personalstack.common.test.openapi.OpenApiSliceExporter
import com.jorisjonkers.personalstack.common.test.openapi.OpenApiWebMvcSliceConfiguration
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import java.nio.file.Path
import java.nio.file.Paths

// Pinned contract: hitting /api/v1/api-docs in the springdoc MVC slice
// produces the OpenAPI spec checked into the repo. The `exportOpenApiSpec`
// Gradle task runs only this tag and writes the JSON output to
// services/agents-api/openapi.json.
@Tag("contract-export")
@WebMvcTest(
    controllers = [
        AdminRunnerController::class,
        AgentSessionController::class,
        ChatSessionController::class,
        ConversationController::class,
        GitController::class,
        HealthController::class,
        InternalGitHubTokenController::class,
        MessageController::class,
        ProjectController::class,
        RepositoryController::class,
        SetupGuideController::class,
        WorkspaceController::class,
    ],
    properties = [
        "springdoc.api-docs.path=/api/v1/api-docs",
    ],
)
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(
    classes = [
        OpenApiSpecExportTest.Application::class,
        OpenApiSpecExportTest.Collaborators::class,
        OpenApiWebMvcSliceConfiguration::class,
        OpenApiConfig::class,
        GlobalExceptionHandler::class,
        AgentRunnerUnavailableExceptionHandler::class,
        KubernetesExceptionHandler::class,
        RepositoryAccessDeniedExceptionHandler::class,
        AdminRunnerController::class,
        AgentSessionController::class,
        ChatSessionController::class,
        ConversationController::class,
        GitController::class,
        HealthController::class,
        InternalGitHubTokenController::class,
        MessageController::class,
        ProjectController::class,
        RepositoryController::class,
        SetupGuideController::class,
        WorkspaceController::class,
    ],
)
class OpenApiSpecExportTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `export OpenAPI spec to repo root`() {
        OpenApiSliceExporter.writeJson(mockMvc, resolveOpenApiSpecPath(), "/api/v1/api-docs")
    }

    private fun resolveOpenApiSpecPath(): Path {
        // The Gradle task sets `openapi.spec.output` to the canonical
        // committed location. Fallback to `<cwd>/openapi.json` when run
        // directly from the IDE so a one-off invocation still works.
        val override = System.getProperty("openapi.spec.output")
        if (override != null) {
            return Paths.get(override)
        }
        return Paths.get(System.getProperty("user.dir")).resolve("openapi.json")
    }

    @SpringBootConfiguration
    class Application

    @TestConfiguration(proxyBeanMethods = false)
    class Collaborators {
        @Bean
        fun agentGatewayClient(): AgentGatewayClient = mockk(relaxed = true)

        @Bean
        fun chatSessionQueryService(): ChatSessionQueryService = mockk(relaxed = true)

        @Bean
        fun chatAnswerStreamService(): ChatAnswerStreamService = mockk()

        @Bean
        fun commandBus(): CommandBus = mockk(relaxed = true)

        @Bean
        fun getConversationQueryService(): GetConversationQueryService = mockk(relaxed = true)

        @Bean
        fun getMessageQueryService(): GetMessageQueryService = mockk(relaxed = true)

        @Bean
        fun getTurnHistoryQueryService(): GetTurnHistoryQueryService = mockk(relaxed = true)

        @Bean
        fun getWorkspaceQueryService(): GetWorkspaceQueryService = mockk(relaxed = true)

        @Bean
        fun githubAppInstallationTokenClient(): GitHubAppInstallationTokenClient = mockk(relaxed = true)

        @Bean
        fun githubLinkRepository(): GithubLinkRepository = mockk(relaxed = true)

        @Bean
        fun listWorkspacesQueryService(): ListWorkspacesQueryService = mockk(relaxed = true)

        @Bean
        fun projectQueryService(): ProjectQueryService = mockk(relaxed = true)

        @Bean
        fun repositoryQueryService(): RepositoryQueryService = mockk(relaxed = true)

        @Bean
        fun repositoryVerificationService(): RepositoryVerificationService = mockk(relaxed = true)

        @Bean
        fun restartAgentSessionService(): RestartAgentSessionService = mockk(relaxed = true)

        @Bean
        fun runnerMaintenanceService(): RunnerMaintenanceService = mockk(relaxed = true)

        @Bean
        fun setupGuideService(): SetupGuideService = mockk(relaxed = true)

        @Bean
        fun workspaceAgentSessionRepository(): WorkspaceAgentSessionRepository = mockk(relaxed = true)

        @Bean
        fun workspaceRepository(): WorkspaceRepository = mockk(relaxed = true)
    }
}
