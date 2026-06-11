package com.jorisjonkers.personalstack.agents.application.rag

import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.TurnId
import com.jorisjonkers.personalstack.agents.domain.model.TurnRole
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class LessonExtractorTest {
    private val extractor = LessonExtractor()
    private val sessionId = WorkspaceAgentSessionId.random()

    @Test
    fun `extracts a question-agent pair when reply is substantive`() {
        val turns =
            listOf(
                turn(TurnRole.USER, "how do I configure flannel over tailscale?", 1),
                turn(TurnRole.AGENT, "Use --flannel-iface=tailscale0 on all nodes. ".repeat(20), 2),
            )
        val out = extractor.extract(workspace(), turns)
        assertThat(out).hasSize(1)
        assertThat(out[0].title).contains("how do I configure flannel")
        assertThat(out[0].body)
            .contains("Trigger:")
            .contains("Evidence:")
            .contains("Q: how do I")
            .contains("A: Use --flannel")
        assertThat(out[0].triggerTerms).contains("flannel", "tailscale")
        assertThat(out[0].excerpts).hasSize(2)
        assertThat(out[0].dedupeQuery).contains("flannel")
        assertThat(out[0].tags).contains("auto-capture", "agents-ui", "kind:turn-pair")
    }

    @Test
    fun `extracts when agent reply contains a TIL or Lesson marker even without a question`() {
        val turns =
            listOf(
                turn(TurnRole.USER, "running through deploys", 1),
                turn(TurnRole.AGENT, "TIL: the keel.sh/match-tag annotation is required. ".repeat(20), 2),
            )
        val out = extractor.extract(workspace(), turns)
        assertThat(out).hasSize(1)
        assertThat(out[0].tags).contains("has-marker")
    }

    @Test
    fun `skips short agent replies`() {
        val turns =
            listOf(
                turn(TurnRole.USER, "what is flannel?", 1),
                turn(TurnRole.AGENT, "A CNI plugin.", 2),
            )
        assertThat(extractor.extract(workspace(), turns)).isEmpty()
    }

    @Test
    fun `skips non-question pairs without a marker`() {
        val turns =
            listOf(
                turn(TurnRole.USER, "list the files", 1),
                turn(TurnRole.AGENT, "x\n".repeat(200), 2),
            )
        assertThat(extractor.extract(workspace(), turns)).isEmpty()
    }

    @Test
    fun `confidence scales with reply length and code-fence presence`() {
        val plain =
            listOf(
                turn(TurnRole.USER, "how to test this?", 1),
                turn(TurnRole.AGENT, "Use ./gradlew test. ".repeat(15), 2),
            )
        val withCode =
            listOf(
                turn(TurnRole.USER, "how to test this?", 1),
                turn(TurnRole.AGENT, "Run:\n```\n./gradlew test\n```\n" + "explanation ".repeat(40), 2),
            )
        val plainScore = extractor.extract(workspace(), plain).single().confidence
        val codeScore = extractor.extract(workspace(), withCode).single().confidence
        assertThat(codeScore).isGreaterThan(plainScore)
    }

    @Test
    fun `repo-backed workspaces add a repo tag`() {
        val ws = workspace(repoUrl = "git@github.com:owner/agents.git")
        val turns =
            listOf(
                turn(TurnRole.USER, "how does services/agents-api/src/main/App.kt work with kubectl?", 1),
                turn(TurnRole.AGENT, "long substantive answer using ./gradlew and kubectl. ".repeat(20), 2),
            )
        val out = extractor.extract(ws, turns).single()
        assertThat(out.tags).contains("repo:agents")
        assertThat(out.triggerTerms)
            .contains(
                "agents",
                "services/agents-api/src/main/App.kt",
                "kubectl",
                "./gradlew",
            )
    }

    private fun turn(
        role: TurnRole,
        body: String,
        sec: Long,
    ) = Turn(
        id = TurnId.random(),
        sessionId = sessionId,
        role = role,
        body = body,
        createdAt = Instant.parse("2026-05-19T10:00:00Z").plusSeconds(sec),
    )

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
}
