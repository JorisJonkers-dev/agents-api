package com.jorisjonkers.personalstack.agents.infrastructure.web

import com.jorisjonkers.personalstack.agents.application.query.GetWorkspaceQueryService
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerReadinessSnapshot
import com.jorisjonkers.personalstack.agents.application.workspacerunner.RunnerReadinessState
import com.jorisjonkers.personalstack.agents.application.workspacerunner.events.WorkspaceRunnerEventsBroadcaster
import com.jorisjonkers.personalstack.agents.config.XUserIdFilter
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.common.web.GlobalExceptionHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

class WorkspaceRunnerEventsControllerTest {
    private val broadcaster = mockk<WorkspaceRunnerEventsBroadcaster>()
    private val getQuery = mockk<GetWorkspaceQueryService>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(WorkspaceRunnerEventsController(broadcaster, getQuery))
                .setControllerAdvice(GlobalExceptionHandler())
                .addFilters<StandaloneMockMvcBuilder>(XUserIdFilter())
                .build()
    }

    @Test
    fun `GET workspace events returns SSE response headers`() {
        val id = UUID.randomUUID()
        every { getQuery.getSummary(WorkspaceId(id)) } returns workspace(id = WorkspaceId(id))
        every { broadcaster.subscribe(WorkspaceId(id)) } returns SseEmitter()

        mockMvc
            .perform(get("/api/v1/workspaces/$id/runner-events").header("X-User-Id", "user-1"))
            .andExpect(status().isOk)
            .andExpect(request().asyncStarted())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(header().string("X-Accel-Buffering", "no"))

        verify { broadcaster.subscribe(WorkspaceId(id)) }
    }

    @Test
    fun `GET workspace events returns 404 when workspace not found`() {
        val id = UUID.randomUUID()
        every { getQuery.getSummary(WorkspaceId(id)) } returns null

        mockMvc
            .perform(get("/api/v1/workspaces/$id/runner-events").header("X-User-Id", "user-1"))
            .andExpect(status().isNotFound)

        verify(exactly = 0) { broadcaster.subscribe(any()) }
    }

    @Test
    fun `GET workspace events without X-User-Id returns 401`() {
        mockMvc
            .perform(get("/api/v1/workspaces/${UUID.randomUUID()}/runner-events"))
            .andExpect(status().isUnauthorized)
    }

    private fun workspace(id: WorkspaceId): Workspace {
        val now = Instant.now()
        return Workspace(
            id = id,
            name = "demo",
            repoUrl = null,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.READY,
            createdAt = now,
            updatedAt = now,
            kind = WorkspaceKind.SCRATCH,
        )
    }
}

class WorkspaceRunnerEventsBroadcasterTest {
    private val now = Instant.parse("2026-06-12T09:00:00Z")
    private val broadcaster =
        WorkspaceRunnerEventsBroadcaster(
            executor = Executor { it.run() },
        )

    @Test
    fun `subscribe sends immediate keepalive and tracks emitter`() {
        val workspaceId = WorkspaceId.random()
        val emitter = emitter()

        broadcaster.subscribe(workspaceId, emitter)

        assertThat(broadcaster.emitterCount(workspaceId)).isEqualTo(1)
        verify { emitter.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `publish sends readiness event only to the target workspace`() {
        val workspaceA = WorkspaceId.random()
        val workspaceB = WorkspaceId.random()
        val emitterA = emitter()
        val emitterB = emitter()
        broadcaster.subscribe(workspaceA, emitterA)
        broadcaster.subscribe(workspaceB, emitterB)

        broadcaster.publish(snapshot(workspaceA, RunnerReadinessState.Ready))

        // emitterA receives: 1 subscribe keepalive + 1 readiness = 2
        verify(exactly = 2) { emitterA.send(any<SseEmitter.SseEventBuilder>()) }
        // emitterB receives only subscribe keepalive, not workspace-A's readiness event
        verify(exactly = 1) { emitterB.send(any<SseEmitter.SseEventBuilder>()) }
    }

    @Test
    fun `completed emitter is removed from registry`() {
        val workspaceId = WorkspaceId.random()
        val completion = slot<Runnable>()
        val emitter = emitter(completion = completion)
        broadcaster.subscribe(workspaceId, emitter)

        assertThat(broadcaster.emitterCount(workspaceId)).isEqualTo(1)
        completion.captured.run()
        assertThat(broadcaster.emitterCount(workspaceId)).isEqualTo(0)
    }

    @Test
    fun `publish to workspace with no subscribers is a no-op`() {
        broadcaster.publish(snapshot(WorkspaceId.random(), RunnerReadinessState.Ready))
        // no exception — just verifying it completes cleanly
    }

    private fun emitter(completion: io.mockk.CapturingSlot<Runnable>? = null): SseEmitter {
        val emitter = mockk<SseEmitter>(relaxed = true)
        if (completion != null) {
            every { emitter.onCompletion(capture(completion)) } returns Unit
        } else {
            every { emitter.onCompletion(any()) } returns Unit
        }
        every { emitter.onTimeout(any()) } returns Unit
        every { emitter.onError(any()) } returns Unit
        every { emitter.send(any<SseEmitter.SseEventBuilder>()) } returns Unit
        return emitter
    }

    private fun snapshot(
        workspaceId: WorkspaceId,
        state: RunnerReadinessState,
    ) = RunnerReadinessSnapshot(
        workspaceId = workspaceId,
        setupId = AgentSetupId.default(),
        setupVersion = AgentSetupVersion.initial(),
        state = state,
        checkedAt = now,
    )
}
