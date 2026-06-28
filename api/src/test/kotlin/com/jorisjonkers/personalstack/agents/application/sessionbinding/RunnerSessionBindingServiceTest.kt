package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.application.exception.AgentRunnerUnavailableException
import com.jorisjonkers.personalstack.agents.application.exception.AgentSetupValidationException
import com.jorisjonkers.personalstack.agents.application.observability.AgentsApiTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.FailureReasonLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationLabel
import com.jorisjonkers.personalstack.agents.application.observability.OperationTelemetry
import com.jorisjonkers.personalstack.agents.application.observability.OutcomeLabel
import com.jorisjonkers.personalstack.agents.application.observability.RunnerReprovisionTelemetry
import com.jorisjonkers.personalstack.agents.application.sessionstatus.SessionStatusPublisher
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupSelectionService
import com.jorisjonkers.personalstack.agents.application.setup.AgentSetupValidationService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerUnavailableReason
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupAvailability
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupCatalogEntry
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupDefinition
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupRef
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssue
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationIssueCode
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupValidationResult
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.RunnerSetupProvisioningSpec
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentGatewayClient
import com.jorisjonkers.personalstack.agents.domain.port.AgentRunnerOrchestrator
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.ResourceAccessException
import java.time.Instant

class RunnerSessionBindingServiceTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val gateway = mockk<AgentGatewayClient>()
    private val orchestrator = mockk<AgentRunnerOrchestrator>()
    private val setupSelection = mockk<AgentSetupSelectionService>()
    private val setupValidation = mockk<AgentSetupValidationService>()
    private val sessionStatus = mockk<SessionStatusPublisher>(relaxed = true)
    private val telemetry = RecordingAgentsApiTelemetry()
    private val setup = setupEntry()
    private val setupSpec = RunnerSetupProvisioningSpec.from(setup.definition)
    private val binder =
        RunnerSessionBinder(
            workspaces = workspaces,
            sessions = sessions,
            gateway = gateway,
            orchestrator = orchestrator,
            setupSelection = setupSelection,
            setupValidation = setupValidation,
            tx = RunnerSessionBindingTransactions(workspaces, sessions, sessionStatus),
            sessionStatus = sessionStatus,
            telemetry = telemetry,
            backoffInitialMs = 0,
        )

    init {
        // AgentSetupId/Version are value classes whose constructors validate their
        // input, so a MockK any() matcher (which builds a dummy by boxing an empty
        // string) trips the validation. Every fixture resolves to the default setup,
        // so match those concrete instances instead of any().
        every {
            setupSelection.requireSelectable(AgentSetupId.default(), AgentSetupVersion.initial())
        } returns setup
        every { setupSelection.defaultSelectable() } returns setup
        every { setupValidation.validate(any()) } returns validSetupResult()
        every { setupValidation.requireValid(any()) } returns validSetupResult()
    }

    @Test
    fun `start creates epoch 1 generation and binds spawned stable gateway session`() {
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        val created = slot<WorkspaceAgentSession>()
        every { workspaces.findById(ws.id) } returns ws
        every { orchestrator.isReady(ws, setupSpec.identity(ws.runnerSetupGeneration)) } returns true
        every { gateway.isReady(ws) } returns true
        every { sessions.save(capture(created)) } answers { created.captured }
        every {
            gateway.spawnAgent(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = sessionId,
                epoch = 1,
                continuation = null,
            )
        } returns gatewayAgent("abc12345", epoch = 1)
        every {
            sessions.bindIfGeneration(
                id = sessionId,
                expectedGeneration = 1,
                gatewayAgentId = "abc12345",
                cliSessionId = "native-1",
                now = any(),
            )
        } returns true
        every { sessions.findById(sessionId) } answers
            { created.captured.bindGatewayAgent("abc12345", "native-1") }

        val result =
            binder.start(
                StartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.CLAUDE,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        assertThat(created.captured.epoch).isEqualTo(1)
        assertThat(created.captured.generation).isEqualTo(1)
        assertThat(created.captured.currentSetupId).isEqualTo(setup.definition.id)
        assertThat(created.captured.currentSetupVersion).isEqualTo(setup.definition.version)
        verify(exactly = 0) { orchestrator.provision(any()) }
    }

    @Test
    fun `start returns Unavailable without saving session when setup operation is in progress`() {
        val ws =
            workspace().copy(
                pendingRunnerSetupId = AgentSetupId("gpu"),
                pendingRunnerSetupVersion = AgentSetupVersion(2),
            )
        val sessionId = WorkspaceAgentSessionId.random()
        every { workspaces.findById(ws.id) } returns ws

        val result =
            binder.start(
                StartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.CLAUDE,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Unavailable::class.java)
        assertThat((result as RunnerSessionBindingResult.Unavailable).runnerStatus)
            .isEqualTo(RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS.label)
        verify(exactly = 0) { sessions.save(any()) }
    }

    @Test
    fun `start returns Unavailable without saving session when runner is not ready`() {
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        every { workspaces.findById(ws.id) } returns ws
        every { orchestrator.isReady(ws, setupSpec.identity(ws.runnerSetupGeneration)) } returns false

        val result =
            binder.start(
                StartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.CLAUDE,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Unavailable::class.java)
        assertThat((result as RunnerSessionBindingResult.Unavailable).runnerStatus)
            .isEqualTo(RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label)
        verify(exactly = 0) { sessions.save(any()) }
    }

    @Test
    fun `ensureBound returns Unavailable without bumping generation when setup operation is in progress`() {
        val ws =
            workspace().copy(
                pendingRunnerSetupId = AgentSetupId("gpu"),
                pendingRunnerSetupVersion = AgentSetupVersion(2),
            )
        val session = session(ws.id, gatewayAgentId = "stale-agent").copy(epoch = 4, generation = 2)
        every { sessions.findById(session.id) } returns session
        every { workspaces.findById(ws.id) } returns ws

        val result =
            binder.ensureBound(
                EnsureRunnerSessionBoundInput(
                    sessionId = session.id,
                    workspaceId = ws.id,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Unavailable::class.java)
        assertThat((result as RunnerSessionBindingResult.Unavailable).runnerStatus)
            .isEqualTo(RunnerUnavailableReason.SETUP_OPERATION_IN_PROGRESS.label)
        verify(exactly = 0) { sessions.beginGeneration(any(), any(), any(), any()) }
    }

    @Test
    fun `ensureBound returns Unavailable without bumping generation when runner is not ready`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = null).copy(epoch = 4, generation = 2)
        every { sessions.findById(session.id) } returns session
        every { workspaces.findById(ws.id) } returns ws
        every { orchestrator.isReady(ws, setupSpec.identity(ws.runnerSetupGeneration)) } returns false

        val result =
            binder.ensureBound(
                EnsureRunnerSessionBoundInput(
                    sessionId = session.id,
                    workspaceId = ws.id,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Unavailable::class.java)
        assertThat((result as RunnerSessionBindingResult.Unavailable).runnerStatus)
            .isEqualTo(RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label)
        verify(exactly = 0) { sessions.beginGeneration(any(), any(), any(), any()) }
    }

    @Test
    fun `ensureBound rebinds unbound session without provisioning when runner is already ready`() {
        val ws = workspace()
        val session = session(ws.id, gatewayAgentId = null).copy(epoch = 4, generation = 2)
        every { sessions.findById(session.id) } returns session andThen
            session.beginGeneration(nextEpoch = 5).bindGatewayAgent("fresh")
        every { workspaces.findById(ws.id) } returns ws
        every { orchestrator.isReady(ws, setupSpec.identity(ws.runnerSetupGeneration)) } returns true
        every { gateway.isReady(ws) } returns true
        every {
            sessions.beginGeneration(
                id = session.id,
                expectedGeneration = 2,
                nextEpoch = 5,
                now = any(),
            )
        } returns true
        every {
            gateway.spawnAgent(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = session.id,
                epoch = 5,
                continuation = AgentGatewayClient.ContinuationMetadata(reason = "rebind", previousEpoch = 4),
                resumeCliSessionId = "native-old",
            )
        } returns gatewayAgent("fresh", epoch = 5)
        every {
            sessions.bindIfGeneration(
                id = session.id,
                expectedGeneration = 3,
                gatewayAgentId = "fresh",
                cliSessionId = "native-1",
                now = any(),
            )
        } returns true

        val result =
            binder.ensureBound(
                EnsureRunnerSessionBoundInput(
                    sessionId = session.id,
                    workspaceId = ws.id,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        verify { sessions.beginGeneration(session.id, 2, 5, any()) }
        verify { gateway.spawnAgent(any(), WorkspaceAgentKind.CLAUDE, null, session.id, 5, any(), "native-old") }
        verify(exactly = 0) { orchestrator.provision(any(), any(), any()) }
    }

    @Test
    fun `start retries transient spawn transport failures before binding`() {
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        val created = slot<WorkspaceAgentSession>()
        every { workspaces.findById(ws.id) } returns ws
        every { orchestrator.isReady(ws, setupSpec.identity(ws.runnerSetupGeneration)) } returns true
        every { gateway.isReady(ws) } returns true
        every { sessions.save(capture(created)) } answers { created.captured }
        every {
            gateway.spawnAgent(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = sessionId,
                epoch = 1,
                continuation = null,
            )
        }.throws(ResourceAccessException("Connection refused"))
            .andThenThrows(ResourceAccessException("Connection refused"))
            .andThen(gatewayAgent("abc12345", epoch = 1))
        every {
            sessions.bindIfGeneration(
                id = sessionId,
                expectedGeneration = 1,
                gatewayAgentId = "abc12345",
                cliSessionId = "native-1",
                now = any(),
            )
        } returns true
        every { sessions.findById(sessionId) } answers
            { created.captured.bindGatewayAgent("abc12345", "native-1") }

        binder.start(
            StartRunnerSessionBindingInput(
                workspaceId = ws.id,
                sessionId = sessionId,
                kind = WorkspaceAgentKind.CLAUDE,
            ),
        )

        verify(exactly = RunnerSessionBinder.MAX_SPAWN_ATTEMPTS) {
            gateway.spawnAgent(ws, WorkspaceAgentKind.CLAUDE, null, sessionId, 1, null)
        }
    }

    @Test
    fun `restart force provisions fresh runner and spawns with continuation metadata`() {
        telemetry.operations.clear()
        telemetry.reprovisions.clear()
        val ws = workspace()
        val session =
            session(ws.id, gatewayAgentId = "old-agent")
                .copy(epoch = 2, generation = 6)
        every { sessions.findById(session.id) } returns session andThen
            session.beginGeneration(nextEpoch = 3).bindGatewayAgent("fresh")
        every { workspaces.findById(ws.id) } returns ws
        every {
            sessions.beginGeneration(
                id = session.id,
                expectedGeneration = 6,
                nextEpoch = 3,
                now = any(),
            )
        } returns true
        every {
            workspaces.beginRunnerSetupOperation(
                ws.id,
                ws.runnerSetupGeneration,
                setup.definition.id,
                setup.definition.version,
                any(),
                any(),
            )
        } returns true
        every { orchestrator.scaleDown(any()) } returns Unit
        every { orchestrator.provision(any(), setupSpec, ws.runnerSetupGeneration + 1) } returns
            AgentRunnerOrchestrator.RunnerHandle(
                podName = "agent-runner-fresh001",
                pvcName = "workspace-abcdef01",
                gatewayEndpoint = "http://fresh:8090",
            )
        every { workspaces.save(any()) } answers { firstArg() }
        every {
            orchestrator.isReady(
                match { it.gatewayEndpoint == "http://fresh:8090" },
                setupSpec.identity(ws.runnerSetupGeneration + 1),
            )
        } returns true
        every { gateway.isReady(any()) } returns true
        every { workspaces.completeRunnerSetupOperation(ws.id, ws.runnerSetupGeneration + 1, any()) } returns true
        every {
            sessions.setPendingSetupIfCurrent(
                id = session.id,
                expectedCurrentSetupId = session.currentSetupId,
                expectedCurrentSetupVersion = session.currentSetupVersion,
                pendingSetupId = setup.definition.id,
                pendingSetupVersion = setup.definition.version,
                now = any(),
            )
        } returns true
        every {
            gateway.spawnAgent(
                workspace = any(),
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = session.id,
                epoch = 3,
                continuation = any(),
                resumeCliSessionId = "native-old",
            )
        } returns gatewayAgent("fresh", epoch = 3)
        every {
            sessions.bindIfGeneration(
                id = session.id,
                expectedGeneration = 7,
                gatewayAgentId = "fresh",
                cliSessionId = "native-1",
                now = any(),
            )
        } returns true
        every {
            sessions.promotePendingSetupIfCurrent(
                id = session.id,
                expectedPendingSetupId = setup.definition.id,
                expectedPendingSetupVersion = setup.definition.version,
                now = any(),
            )
        } returns true

        val result =
            binder.restart(
                RestartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    expectedGeneration = 6,
                    reason = "restart",
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        verify(exactly = 1) { orchestrator.scaleDown(any()) }
        verify(exactly = 1) { orchestrator.provision(any(), setupSpec, ws.runnerSetupGeneration + 1) }
        verify { gateway.spawnAgent(any(), WorkspaceAgentKind.CLAUDE, null, session.id, 3, any(), "native-old") }
        assertThat(telemetry.reprovisions)
            .anySatisfy {
                assertThat(it.outcome).isEqualTo(OutcomeLabel.SUCCESS)
                assertThat(it.reason).isEqualTo(FailureReasonLabel.NONE)
            }
        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.operation).isEqualTo(OperationLabel.REPROVISION_RUNNER)
                assertThat(it.outcome).isEqualTo(OutcomeLabel.SUCCESS)
                assertThat(it.reason).isEqualTo(FailureReasonLabel.NONE)
            }
    }

    @Test
    fun `restart leaves session awaiting rebind when the reprovisioned runner is not ready yet`() {
        telemetry.operations.clear()
        telemetry.reprovisions.clear()
        val ws = workspace()
        val session =
            session(ws.id, gatewayAgentId = "old-agent")
                .copy(epoch = 2, generation = 6)
        every { sessions.findById(session.id) } returns session
        every { workspaces.findById(ws.id) } returns ws
        every {
            sessions.beginGeneration(id = session.id, expectedGeneration = 6, nextEpoch = 3, now = any())
        } returns true
        every {
            sessions.setPendingSetupIfCurrent(
                id = session.id,
                expectedCurrentSetupId = session.currentSetupId,
                expectedCurrentSetupVersion = session.currentSetupVersion,
                pendingSetupId = setup.definition.id,
                pendingSetupVersion = setup.definition.version,
                now = any(),
            )
        } returns true
        every {
            workspaces.beginRunnerSetupOperation(
                ws.id,
                ws.runnerSetupGeneration,
                setup.definition.id,
                setup.definition.version,
                any(),
                any(),
            )
        } returns true
        every { orchestrator.scaleDown(any()) } returns Unit
        every { orchestrator.provision(any(), setupSpec, ws.runnerSetupGeneration + 1) } returns
            AgentRunnerOrchestrator.RunnerHandle(
                podName = "agent-runner-fresh001",
                pvcName = "workspace-abcdef01",
                gatewayEndpoint = "http://fresh:8090",
            )
        every { workspaces.save(any()) } answers { firstArg() }
        // The fresh runner is still booting (e.g. a cold image pull) within the window.
        every {
            orchestrator.isReady(
                match { it.gatewayEndpoint == "http://fresh:8090" },
                setupSpec.identity(ws.runnerSetupGeneration + 1),
            )
        } returns false
        every { gateway.isReady(any()) } returns false
        every { workspaces.completeRunnerSetupOperation(ws.id, ws.runnerSetupGeneration + 1, any()) } returns true
        every {
            sessions.markLifecycleIfGeneration(
                id = session.id,
                expectedGeneration = 7,
                status = WorkspaceAgentSessionStatus.RUNNING,
                retainedUntil = null,
                clearGatewayBinding = true,
                now = any(),
            )
        } returns true
        every {
            sessions.promotePendingSetupIfCurrent(
                id = session.id,
                expectedPendingSetupId = setup.definition.id,
                expectedPendingSetupVersion = setup.definition.version,
                now = any(),
            )
        } returns true

        val result =
            binder.restart(
                RestartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = session.id,
                    expectedGeneration = 6,
                    reason = "restart",
                ),
            )

        // Provisioned a fresh runner but did not fail the session: it is left
        // RUNNING+unbound so the next attach resumes it via ensureBound.
        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Unavailable::class.java)
        assertThat((result as RunnerSessionBindingResult.Unavailable).runnerStatus)
            .isEqualTo(RunnerUnavailableReason.NOT_READY_AFTER_PROVISION.label)
        verify(exactly = 1) { orchestrator.scaleDown(any()) }
        verify(exactly = 1) { orchestrator.provision(any(), setupSpec, ws.runnerSetupGeneration + 1) }
        verify(exactly = 1) {
            sessions.markLifecycleIfGeneration(
                id = session.id,
                expectedGeneration = 7,
                status = WorkspaceAgentSessionStatus.RUNNING,
                retainedUntil = null,
                clearGatewayBinding = true,
                now = any(),
            )
        }
        // No spawn/bind happened — the resume is deferred to the next attach.
        // (gateway.spawnAgent and sessions.bindIfGeneration are left unstubbed,
        // so taking the spawn path would fail this test outright.)
    }

    @Test
    fun `spawn transport failure records bounded reason without raw exception message`() {
        telemetry.operations.clear()
        telemetry.reprovisions.clear()
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        val created = slot<WorkspaceAgentSession>()
        every { workspaces.findById(ws.id) } returns ws
        every { orchestrator.isReady(ws, setupSpec.identity(ws.runnerSetupGeneration)) } returns true
        every { gateway.isReady(ws) } returns true
        every { sessions.save(capture(created)) } answers { created.captured }
        every {
            gateway.spawnAgent(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = sessionId,
                epoch = 1,
                continuation = null,
            )
        } throws ResourceAccessException("Connection refused at http://runner.internal/private")
        every {
            sessions.markLifecycleIfGeneration(
                id = sessionId,
                expectedGeneration = 1,
                status = WorkspaceAgentSessionStatus.FAILED,
                retainedUntil = null,
                clearGatewayBinding = true,
                now = any(),
            )
        } returns true

        assertThrows<AgentRunnerUnavailableException> {
            binder.start(
                StartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.CLAUDE,
                ),
            )
        }

        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.operation).isEqualTo(OperationLabel.START_SESSION)
                assertThat(it.outcome).isEqualTo(OutcomeLabel.FAILURE)
                assertThat(it.reason).isEqualTo(FailureReasonLabel.UPSTREAM_UNAVAILABLE)
            }
        assertThat(telemetry.operations.map { it.reason.label })
            .doesNotContain("Connection refused at http://runner.internal/private")
    }

    @Test
    fun `validation rejection records bounded reason without raw validation message`() {
        telemetry.operations.clear()
        telemetry.reprovisions.clear()
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        val rawMessage = "Secret agents/prod-token is missing"
        every { workspaces.findById(ws.id) } returns ws
        every { setupValidation.requireValid(any()) } throws
            AgentSetupValidationException(
                AgentSetupValidationResult(
                    target = AgentSetupRef(setup.definition.id, setup.definition.version),
                    valid = false,
                    issues =
                        listOf(
                            AgentSetupValidationIssue(
                                code = AgentSetupValidationIssueCode.SECRET_MISSING,
                                message = rawMessage,
                            ),
                        ),
                ),
            )

        assertThrows<AgentSetupValidationException> {
            binder.start(
                StartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.CLAUDE,
                    setupId = setup.definition.id,
                    setupVersion = setup.definition.version,
                ),
            )
        }

        assertThat(telemetry.operations)
            .anySatisfy {
                assertThat(it.operation).isEqualTo(OperationLabel.START_SESSION)
                assertThat(it.outcome).isEqualTo(OutcomeLabel.FAILURE)
                assertThat(it.reason).isEqualTo(FailureReasonLabel.INVALID_REQUEST)
            }
        assertThat(telemetry.operations.map { it.reason.label }).doesNotContain(rawMessage)
    }

    @Test
    fun `start resolves Bound on first write with RUNNING state and never returns Conflict`() {
        // Regression: the old flow saved a STARTING row then ran a bind CAS that could miss
        // under concurrent load — returning Conflict to the client. New flow: spawn first,
        // persist RUNNING+bound in one write, so Conflict is structurally impossible.
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        val saveSlot = slot<WorkspaceAgentSession>()
        every { workspaces.findById(ws.id) } returns ws
        every { orchestrator.isReady(ws, setupSpec.identity(ws.runnerSetupGeneration)) } returns true
        every { gateway.isReady(ws) } returns true
        every { sessions.save(capture(saveSlot)) } answers { saveSlot.captured }
        every {
            gateway.spawnAgent(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = sessionId,
                epoch = 1,
                continuation = null,
            )
        } returns gatewayAgent("abc12345", epoch = 1)

        val result =
            binder.start(
                StartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.CLAUDE,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Bound::class.java)
        assertThat(result).isNotInstanceOf(RunnerSessionBindingResult.Conflict::class.java)
        // Single write must be RUNNING+bound, never STARTING
        assertThat(saveSlot.captured.status).isEqualTo(WorkspaceAgentSessionStatus.RUNNING)
        assertThat(saveSlot.captured.gatewayAgentId).isEqualTo("abc12345")
    }

    @Test
    fun `start returns Unavailable on persistence failure without creating a STARTING row`() {
        // If the single RUNNING+bound save fails, we stop the spawned agent and return
        // Unavailable — no orphaned STARTING row is left in the database.
        val ws = workspace()
        val sessionId = WorkspaceAgentSessionId.random()
        val saveSlot = slot<WorkspaceAgentSession>()
        every { workspaces.findById(ws.id) } returns ws
        every { orchestrator.isReady(ws, setupSpec.identity(ws.runnerSetupGeneration)) } returns true
        every { gateway.isReady(ws) } returns true
        every { sessions.save(capture(saveSlot)) } throws RuntimeException("DB unavailable")
        every {
            gateway.spawnAgent(
                workspace = ws,
                kind = WorkspaceAgentKind.CLAUDE,
                workspacePath = null,
                stableSessionId = sessionId,
                epoch = 1,
                continuation = null,
            )
        } returns gatewayAgent("abc12345", epoch = 1)
        every { gateway.stopAgent(ws, "abc12345") } returns Unit

        val result =
            binder.start(
                StartRunnerSessionBindingInput(
                    workspaceId = ws.id,
                    sessionId = sessionId,
                    kind = WorkspaceAgentKind.CLAUDE,
                ),
            )

        assertThat(result).isInstanceOf(RunnerSessionBindingResult.Unavailable::class.java)
        // The attempted write was for RUNNING state — no STARTING row was ever created
        assertThat(saveSlot.captured.status).isEqualTo(WorkspaceAgentSessionStatus.RUNNING)
        assertThat(saveSlot.captured.gatewayAgentId).isEqualTo("abc12345")
        verify { gateway.stopAgent(ws, "abc12345") }
    }

    @Test
    fun `beginGeneration suppresses status publication when generation CAS fails`() {
        val session = session(WorkspaceId.random(), gatewayAgentId = "old-agent").copy(epoch = 2, generation = 6)
        val starting = session.beginGeneration(nextEpoch = 3)
        every {
            sessions.beginGeneration(
                id = session.id,
                expectedGeneration = 6,
                nextEpoch = 3,
                now = any(),
            )
        } returns false

        val result =
            RunnerSessionBindingTransactions(
                workspaces,
                sessions,
                sessionStatus,
            ).beginGeneration(session, starting)

        assertThat(result).isFalse
        verify(exactly = 0) { sessionStatus.publishStatus(any<WorkspaceAgentSession>(), any()) }
    }

    private fun gatewayAgent(
        id: String,
        epoch: Long,
    ) = AgentGatewayClient.GatewayAgent(
        id = id,
        kind = WorkspaceAgentKind.CLAUDE,
        cwd = "/workspace",
        cliSessionId = "native-1",
        stableSessionId = "stable",
        epoch = epoch,
    )

    private fun workspace(
        status: WorkspaceStatus = WorkspaceStatus.READY,
        gatewayEndpoint: String? = "http://x:8090",
    ) = Workspace(
        id = WorkspaceId.random(),
        name = "demo",
        repoUrl = null,
        branch = null,
        podName = "agent-runner-abcdef01",
        pvcName = "workspace-abcdef01",
        gatewayEndpoint = gatewayEndpoint,
        status = status,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun session(
        workspaceId: WorkspaceId,
        gatewayAgentId: String?,
    ) = WorkspaceAgentSession(
        id = WorkspaceAgentSessionId.random(),
        workspaceId = workspaceId,
        kind = WorkspaceAgentKind.CLAUDE,
        gatewayAgentId = gatewayAgentId,
        status = WorkspaceAgentSessionStatus.RUNNING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        cliSessionId = "native-old",
    )

    private fun setupEntry() =
        AgentSetupCatalogEntry(
            definition =
                AgentSetupDefinition(
                    id = AgentSetupId.default(),
                    version = AgentSetupVersion.initial(),
                    displayName = "Default",
                    description = null,
                    namespace = "agents-system",
                    image = "agent-runner:latest",
                    imagePullPolicy = "IfNotPresent",
                    serviceAccount = "agent-runner",
                    gatewayPort = 8090,
                    claudeCredentialsPvc = "claude",
                    codexCredentialsPvc = "codex",
                    githubDeployKeySecret = "github",
                    cliTools = emptyMap(),
                    knowledgeBaseUrl = "http://knowledge",
                    knowledgeBearerSecret = "knowledge",
                    knowledgeBearerSecretKey = "token",
                    mcpServersConfigMap = "mcp",
                    defaultMcpProfile = "minimal",
                    connectorConfig = emptyMap(),
                    toolProfiles = emptyList(),
                    toolAllowlist = emptyList(),
                    dockerSocketEnabled = false,
                    dockerSocketPath = "/var/run/docker.sock",
                    dockerSocketSupplementalGroups = emptyList(),
                    nodeSelector = emptyMap(),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                ),
            availability =
                AgentSetupAvailability(
                    id = AgentSetupId.default(),
                    version = AgentSetupVersion.initial(),
                    selectable = true,
                    defaultSelectable = true,
                    unavailableReason = null,
                    updatedAt = Instant.now(),
                ),
        )

    private fun validSetupResult() =
        AgentSetupValidationResult(
            target = AgentSetupRef(setup.definition.id, setup.definition.version),
            valid = true,
        )

    private class RecordingAgentsApiTelemetry : AgentsApiTelemetry {
        val operations = mutableListOf<OperationTelemetry>()
        val reprovisions = mutableListOf<RunnerReprovisionTelemetry>()

        override fun recordOperation(event: OperationTelemetry) {
            operations += event
        }

        override fun recordRunnerReprovision(event: RunnerReprovisionTelemetry) {
            reprovisions += event
        }
    }
}
