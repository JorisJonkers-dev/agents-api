package com.jorisjonkers.personalstack.agents.application.sessionbinding

import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.AgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant

class RunnerSessionBindingRouterTest {
    private val podBinding = mockk<RunnerSessionBinder>(relaxed = true)
    private val inContainerBinding = mockk<InContainerSessionBindingService>(relaxed = true)
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<AgentSessionRepository>()
    private val router = RunnerSessionBindingRouter(podBinding, inContainerBinding, workspaces, sessions)

    @Test
    fun `start for a Shell session in a Scratch workspace binds in-container`() {
        val workspaceId = WorkspaceId.random()
        every { workspaces.findById(workspaceId) } returns workspace(workspaceId, WorkspaceKind.SCRATCH)
        val request =
            StartRunnerSessionBindingInput(
                workspaceId = workspaceId,
                sessionId = AgentSessionId.random(),
                kind = WorkspaceAgentKind.SHELL,
            )

        router.start(request)

        verify { inContainerBinding.start(request) }
        verify(exactly = 0) { podBinding.start(any()) }
    }

    // #64: Claude and Codex join Shell in-container, so the CLI reads its Agent
    // Login off the home volume. Before this they went to a runner Pod, which
    // got its login injected as a Secret -- the mechanism ADR 0002 replaces.
    @Test
    fun `start for a Claude session in a Scratch workspace binds in-container`() {
        val workspaceId = WorkspaceId.random()
        every { workspaces.findById(workspaceId) } returns workspace(workspaceId, WorkspaceKind.SCRATCH)
        val request =
            StartRunnerSessionBindingInput(
                workspaceId = workspaceId,
                sessionId = AgentSessionId.random(),
                kind = WorkspaceAgentKind.CLAUDE,
            )

        router.start(request)

        verify { inContainerBinding.start(request) }
        verify(exactly = 0) { podBinding.start(any()) }
    }

    @Test
    fun `start for a Codex session in a Scratch workspace binds in-container`() {
        val workspaceId = WorkspaceId.random()
        every { workspaces.findById(workspaceId) } returns workspace(workspaceId, WorkspaceKind.SCRATCH)
        val request =
            StartRunnerSessionBindingInput(
                workspaceId = workspaceId,
                sessionId = AgentSessionId.random(),
                kind = WorkspaceAgentKind.CODEX,
            )

        router.start(request)

        verify { inContainerBinding.start(request) }
        verify(exactly = 0) { podBinding.start(any()) }
    }

    // A Repo-backed Workspace keeps going to the Pod binder, for every Agent
    // Kind. The in-container binder does not clone a repo -- that is the Pod
    // entrypoint's job until #67 moves the whole path across -- so routing one
    // here would start an Agent Session in an empty directory. The cost is that
    // a Claude session in a Repo-backed Workspace has no Agent Login during the
    // transition, because #64 removes the Secret injection that used to supply
    // one. #67 closes it.
    @Test
    fun `start for a Claude session in a repo-backed workspace still goes to the pod binder`() {
        val workspaceId = WorkspaceId.random()
        every { workspaces.findById(workspaceId) } returns workspace(workspaceId, WorkspaceKind.REPO_BACKED)
        val request =
            StartRunnerSessionBindingInput(
                workspaceId = workspaceId,
                sessionId = AgentSessionId.random(),
                kind = WorkspaceAgentKind.CLAUDE,
            )

        router.start(request)

        verify { podBinding.start(request) }
        verify(exactly = 0) { inContainerBinding.start(any()) }
    }

    @Test
    fun `start for a repo-backed workspace goes to the pod binder`() {
        val workspaceId = WorkspaceId.random()
        every { workspaces.findById(workspaceId) } returns workspace(workspaceId, WorkspaceKind.REPO_BACKED)
        val request =
            StartRunnerSessionBindingInput(
                workspaceId = workspaceId,
                sessionId = AgentSessionId.random(),
                kind = WorkspaceAgentKind.SHELL,
            )

        router.start(request)

        verify { podBinding.start(request) }
        verify(exactly = 0) { inContainerBinding.start(any()) }
    }

    // A Scratch Workspace created before #62 can still hold a Claude or Codex
    // session bound to a runner Pod, and those rows survive this deploy. Sending
    // one to the in-container binder strands it: restart() throws
    // UnsupportedOperationException, and ensureBound() answers with this
    // container's workspace directory instead of the Pod's /workspace.
    @Test
    fun `start for a Scratch workspace that still has a runner Pod goes to the pod binder`() {
        val workspaceId = WorkspaceId.random()
        every { workspaces.findById(workspaceId) } returns
            workspace(workspaceId, WorkspaceKind.SCRATCH).copy(podName = "agent-runner-legacy")
        val request =
            StartRunnerSessionBindingInput(
                workspaceId = workspaceId,
                sessionId = AgentSessionId.random(),
                kind = WorkspaceAgentKind.CLAUDE,
            )

        router.start(request)

        verify { podBinding.start(request) }
        verify(exactly = 0) { inContainerBinding.start(any()) }
    }

    @Test
    fun `ensureBound resolves the workspace and session kind to pick the binder`() {
        val workspaceId = WorkspaceId.random()
        val sessionId = AgentSessionId.random()
        every { sessions.findById(sessionId) } returns shellSession(sessionId, workspaceId)
        every { workspaces.findById(workspaceId) } returns workspace(workspaceId, WorkspaceKind.SCRATCH)
        val request = EnsureRunnerSessionBoundInput(sessionId = sessionId)

        router.ensureBound(request)

        verify { inContainerBinding.ensureBound(request) }
        verify(exactly = 0) { podBinding.ensureBound(any()) }
    }

    private fun workspace(
        id: WorkspaceId,
        kind: WorkspaceKind,
    ) = Workspace(
        id = id,
        name = "demo",
        repoUrl = null,
        branch = null,
        podName = null,
        pvcName = null,
        gatewayEndpoint = if (kind == WorkspaceKind.SCRATCH) null else "http://gw:8090",
        status = WorkspaceStatus.PREPARING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        kind = kind,
    )

    private fun shellSession(
        id: AgentSessionId,
        workspaceId: WorkspaceId,
    ) = AgentSession(
        id = id,
        workspaceId = workspaceId,
        kind = WorkspaceAgentKind.SHELL,
        gatewayAgentId = "abc12345",
        status = AgentSessionStatus.RUNNING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
