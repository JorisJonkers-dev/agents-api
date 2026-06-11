package com.jorisjonkers.personalstack.agents.application.rag

import com.jorisjonkers.personalstack.agents.config.RagProperties
import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.TurnId
import com.jorisjonkers.personalstack.agents.domain.model.TurnRole
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.domain.port.KnowledgeWritePort
import com.jorisjonkers.personalstack.agents.domain.port.TurnRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant

class LessonAutoCaptureTest {
    private val workspaces = mockk<WorkspaceRepository>()
    private val sessions = mockk<WorkspaceAgentSessionRepository>()
    private val turns = mockk<TurnRepository>()
    private val extractor = LessonExtractor()
    private val kbWrite = mockk<KnowledgeWritePort>(relaxed = true)
    private val rag =
        RagProperties(
            enabled = true,
            knowledgeMcpUrl = "http://kb",
            knowledgeMcpToken = "",
            lightragUrl = "http://lr",
        )

    private val capture = LessonAutoCapture(workspaces, sessions, turns, extractor, kbWrite, rag)

    @Test
    fun `capture ingests one note per extracted candidate`() {
        val ws = workspace(repoUrl = "git@github.com:owner/agents.git")
        val s = session(ws.id)
        every { sessions.findById(s.id) } returns s
        every { workspaces.findById(ws.id) } returns ws
        every { kbWrite.findDuplicateEvidence(any(), any()) } returns null
        every { turns.findBySessionId(s.id, any()) } returns
            listOf(
                turn(TurnRole.USER, "how does flannel work over tailscale?", 1, s.id),
                turn(TurnRole.AGENT, "Lesson: It uses --flannel-iface=tailscale0. ".repeat(40), 2, s.id),
            )

        capture.capture(s.id)

        verify {
            kbWrite.ingestNote(
                match {
                    it.title.contains("how does flannel") &&
                        it.body.contains("Trigger:") &&
                        it.body.contains("Capture policy:") &&
                        it.scope == "project:agents" &&
                        it.source == "agents-ui:auto-capture:${s.id}" &&
                        it.sessionId == s.id.toString() &&
                        (it.confidence ?: 0.0) >= 0.55 &&
                        it.tags.containsAll(
                            listOf(
                                "auto-capture",
                                "agents-ui",
                                "agent:claude",
                                "dedupe:checked",
                                "repo:agents",
                            ),
                        )
                },
            )
        }
    }

    @Test
    fun `capture is a no-op when RAG is disabled`() {
        val disabledRag = rag.copy(enabled = false)
        val withDisabled = LessonAutoCapture(workspaces, sessions, turns, extractor, kbWrite, disabledRag)
        withDisabled.capture(WorkspaceAgentSessionId.random())
        verify(exactly = 0) { kbWrite.ingestNote(any<KnowledgeWritePort.CaptureRequest>()) }
    }

    @Test
    fun `capture bucket caps writes per session`() {
        val ws = workspace()
        val s = session(ws.id)
        every { sessions.findById(s.id) } returns s
        every { workspaces.findById(ws.id) } returns ws
        every { kbWrite.findDuplicateEvidence(any(), any()) } returns null
        // Five capture-worthy pairs in a row, bucket capacity is 3.
        val pairs =
            (1..5).flatMap { i ->
                listOf(
                    turn(TurnRole.USER, "how about $i?", i * 2L, s.id),
                    turn(TurnRole.AGENT, "long answer $i. ".repeat(30), i * 2L + 1, s.id),
                )
            }
        every { turns.findBySessionId(s.id, any()) } returns pairs

        capture.capture(s.id)

        verify(exactly = 3) { kbWrite.ingestNote(any<KnowledgeWritePort.CaptureRequest>()) }
    }

    @Test
    fun `capture skips likely duplicates before consuming write budget`() {
        val ws = workspace(repoUrl = "git@github.com:owner/agents.git")
        val s = session(ws.id)
        every { sessions.findById(s.id) } returns s
        every { workspaces.findById(ws.id) } returns ws
        every { kbWrite.findDuplicateEvidence(any(), 0.86) } returns
            KnowledgeWritePort.DuplicateEvidence(
                id = "01KDUPLICATE",
                source = "kb:project:agents:Existing lesson",
                score = 0.91,
            )
        every { turns.findBySessionId(s.id, any()) } returns
            listOf(
                turn(TurnRole.USER, "how does duplicate capture work?", 1, s.id),
                turn(TurnRole.AGENT, "Lesson: duplicate answer. ".repeat(40), 2, s.id),
            )

        capture.capture(s.id)

        verify(exactly = 0) { kbWrite.ingestNote(any<KnowledgeWritePort.CaptureRequest>()) }
    }

    @Test
    fun `capture routes weak candidates to inbox for curator review`() {
        val ws = workspace(repoUrl = "git@github.com:owner/agents.git")
        val s = session(ws.id)
        every { sessions.findById(s.id) } returns s
        every { workspaces.findById(ws.id) } returns ws
        every { kbWrite.findDuplicateEvidence(any(), any()) } returns null
        every { turns.findBySessionId(s.id, any()) } returns
            listOf(
                turn(TurnRole.USER, "how does low confidence capture work?", 1, s.id),
                turn(TurnRole.AGENT, "short but still capture-worthy explanation. ".repeat(8), 2, s.id),
            )

        capture.capture(s.id)

        verify {
            kbWrite.ingestNote(
                match {
                    it.scope == "_inbox" &&
                        it.body.contains("inferred_scope: project:agents") &&
                        it.body.contains("capture_scope: _inbox") &&
                        it.tags.contains("confidence:low")
                },
            )
        }
    }

    private fun workspace(repoUrl: String? = null) =
        Workspace(
            id = WorkspaceId.random(),
            name = "demo",
            repoUrl = repoUrl,
            branch = null,
            podName = null,
            pvcName = null,
            gatewayEndpoint = null,
            status = WorkspaceStatus.READY,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun session(workspaceId: WorkspaceId) =
        WorkspaceAgentSession(
            id = WorkspaceAgentSessionId.random(),
            workspaceId = workspaceId,
            kind = WorkspaceAgentKind.CLAUDE,
            gatewayAgentId = "abc12345",
            status = WorkspaceAgentSessionStatus.RUNNING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun turn(
        role: TurnRole,
        body: String,
        sec: Long,
        sid: WorkspaceAgentSessionId,
    ) = Turn(
        id = TurnId.random(),
        sessionId = sid,
        role = role,
        body = body,
        createdAt = Instant.parse("2026-05-19T10:00:00Z").plusSeconds(sec),
    )
}
