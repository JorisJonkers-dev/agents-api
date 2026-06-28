package com.jorisjonkers.personalstack.agents.application.rag

import com.jorisjonkers.personalstack.agents.config.RagProperties
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.port.KnowledgeWritePort
import com.jorisjonkers.personalstack.agents.domain.port.TurnRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Locale

/**
 * Orchestrates the auto-capture pipeline:
 *
 *   sessions ─► turns ─► LessonExtractor ─► TokenBucket gate
 *                                      ─► KnowledgeWritePort.ingestNote
 *
 * `capture(sessionId)` is the public entrypoint and is `@Async` so
 * the WS handler's hot path stays out of the LLM/MCP round trip.
 * The token bucket defaults to capacity = 3 with a 15-minute refill,
 * so a single session can't flood the KB even if every turn is
 * marker-flagged. Dedupe runs before token consumption so skipped
 * candidates do not burn the write budget.
 */
@Component
open class LessonAutoCapture(
    private val workspaces: WorkspaceRepository,
    private val sessions: WorkspaceAgentSessionRepository,
    private val turns: TurnRepository,
    private val extractor: LessonExtractor,
    private val knowledgeWrite: KnowledgeWritePort,
    private val rag: RagProperties,
) {
    private val log = LoggerFactory.getLogger(LessonAutoCapture::class.java)
    private val bucket =
        TokenBucket(
            capacity = rag.autoCaptureSessionCapacity,
            refillInterval = Duration.ofMinutes(rag.autoCaptureBucketRefillMinutes),
        )

    @Async
    open fun capture(sessionId: WorkspaceAgentSessionId) {
        if (!rag.captureEnabled) return
        val resolved = resolveSession(sessionId) ?: return
        val history = turns.findBySessionId(sessionId, limit = TURN_FETCH_LIMIT)
        val candidates = extractor.extract(resolved.workspace, history)
        if (candidates.isEmpty()) return
        ingestUpToBucket(resolved.session, resolved.workspace, candidates)
    }

    private data class Resolved(
        val session: WorkspaceAgentSession,
        val workspace: Workspace,
    )

    private data class CapturePolicyContext(
        val sessionId: String,
        val agentKind: String,
        val inferredScope: String,
    )

    private fun resolveSession(sessionId: WorkspaceAgentSessionId): Resolved? {
        val session = sessions.findById(sessionId) ?: return null
        val workspace = workspaces.findById(session.workspaceId) ?: return null
        return Resolved(session, workspace)
    }

    private fun ingestUpToBucket(
        session: WorkspaceAgentSession,
        workspace: Workspace,
        candidates: List<LessonExtractor.Candidate>,
    ) {
        val context =
            CapturePolicyContext(
                sessionId = session.id.toString(),
                agentKind = session.kind.name.lowercase(),
                inferredScope = ScopeInference.scopeFor(workspace),
            )
        for (c in candidates) {
            if (isDuplicate(context, c)) continue
            if (!bucket.tryAcquire(context.sessionId)) {
                log.info("auto-capture bucket exhausted for session {}", context.sessionId)
                return
            }
            ingestCandidate(context, c)
        }
    }

    private fun isDuplicate(
        context: CapturePolicyContext,
        candidate: LessonExtractor.Candidate,
    ): Boolean {
        val duplicate =
            runCatching { knowledgeWrite.findDuplicateEvidence(candidate.dedupeQuery, rag.autoCaptureDedupeScore) }
                .onFailure { log.warn("auto-capture dedupe failed: {}", it.message) }
                .getOrNull()
        if (duplicate == null) return false
        log.info(
            "auto-capture skipped duplicate for session {} source={} score={}",
            context.sessionId,
            duplicate.source,
            duplicate.score,
        )
        return true
    }

    private fun ingestCandidate(
        context: CapturePolicyContext,
        candidate: LessonExtractor.Candidate,
    ) {
        val request = captureRequest(context, candidate)
        runCatching { knowledgeWrite.ingestNote(request) }
            .onFailure { log.warn("auto-capture ingest failed: {}", it.message) }
    }

    private fun captureRequest(
        context: CapturePolicyContext,
        candidate: LessonExtractor.Candidate,
    ): KnowledgeWritePort.CaptureRequest {
        val scope = captureScope(context, candidate)
        return KnowledgeWritePort.CaptureRequest(
            title = candidate.title,
            body = captureBody(context, candidate, scope),
            scope = scope,
            tags = captureTags(context, candidate),
            source = "agents-ui:auto-capture:${context.sessionId}",
            sessionId = context.sessionId,
            confidence = candidate.confidence,
        )
    }

    private fun captureScope(
        context: CapturePolicyContext,
        candidate: LessonExtractor.Candidate,
    ): String =
        if (candidate.confidence >= rag.autoCaptureScopedMinConfidence) {
            context.inferredScope
        } else {
            INBOX_SCOPE
        }

    private fun captureTags(
        context: CapturePolicyContext,
        candidate: LessonExtractor.Candidate,
    ): List<String> =
        (
            candidate.tags +
                listOf(
                    "agent:${context.agentKind}",
                    confidenceTag(candidate.confidence),
                    "dedupe:checked",
                ) +
                candidate.triggerTerms.take(TRIGGER_TAG_LIMIT).map { "trigger:$it" }
        ).distinct()

    private fun captureBody(
        context: CapturePolicyContext,
        candidate: LessonExtractor.Candidate,
        scope: String,
    ): String =
        buildString {
            append(candidate.body)
            append("\n\nCapture policy:\n")
            append("- source: agents-ui:auto-capture:${context.sessionId}\n")
            append("- session_id: ${context.sessionId}\n")
            append("- agent_kind: ${context.agentKind}\n")
            append("- inferred_scope: ${context.inferredScope}\n")
            append("- capture_scope: $scope\n")
            append("- confidence: ${String.format(Locale.ROOT, "%.2f", candidate.confidence)}\n")
            append("- dedupe: checked recall threshold ${rag.autoCaptureDedupeScore}; no duplicate hit\n")
        }

    private fun confidenceTag(confidence: Double): String =
        when {
            confidence >= HIGH_CONFIDENCE -> "confidence:high"
            confidence >= MEDIUM_CONFIDENCE -> "confidence:medium"
            else -> "confidence:low"
        }

    companion object {
        private const val TURN_FETCH_LIMIT = 50
        private const val INBOX_SCOPE = "_inbox"
        private const val HIGH_CONFIDENCE = 0.75
        private const val MEDIUM_CONFIDENCE = 0.55
        private const val TRIGGER_TAG_LIMIT = 6
    }
}
