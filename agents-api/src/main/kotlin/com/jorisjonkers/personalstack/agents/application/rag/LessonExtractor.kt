package com.jorisjonkers.personalstack.agents.application.rag

import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.TurnRole
import com.jorisjonkers.personalstack.agents.domain.model.Workspace
import org.springframework.stereotype.Component

/**
 * Heuristic extractor that turns a session's transcript into
 * candidate KB lessons. v1 is intentionally simple:
 *
 * - Pair each USER turn with the immediately-following AGENT
 *   turn(s) up to the next USER turn.
 * - Keep the pair only if the agent reply is substantive
 *   (>= minBodyChars) AND either contains an explicit lesson
 *   marker ("TIL:", "Note:", "Lesson:") OR the user turn is a
 *   question (ends with "?" or starts with "how/why/what/...").
 * - The title is the user's prompt, truncated; the body includes
 *   trigger terms, short evidence excerpts, and a Q/A lesson section
 *   so the captured note can be reviewed and recalled later.
 *
 * The actual quality bar is enforced at the curator step downstream
 * (it routes low-confidence notes into `_inbox/_needs-review`), so
 * this side stays cheap and lets the curator pick.
 */
@Component
class LessonExtractor(
    private val minBodyChars: Int = MIN_BODY_CHARS,
    private val maxBodyChars: Int = MAX_BODY_CHARS,
) {
    private val markerRegex = Regex("\\b(TIL|Note|Lesson|Key takeaway):", RegexOption.IGNORE_CASE)
    private val questionStarter =
        Regex("^\\s*(how|why|what|when|where|which|who|can|does|do|should)\\b", RegexOption.IGNORE_CASE)

    data class Candidate(
        val title: String,
        val body: String,
        val tags: List<String>,
        val confidence: Double,
        val triggerTerms: List<String>,
        val excerpts: List<String>,
        val dedupeQuery: String,
    )

    private data class Pair(
        val user: Turn,
        val agentBody: String,
    )

    fun extract(
        workspace: Workspace,
        turns: List<Turn>,
    ): List<Candidate> {
        val ordered = turns.sortedBy { it.createdAt }
        return collectPairs(ordered)
            .filter { it.agentBody.length >= minBodyChars && isWorthCapturing(it.user.body, it.agentBody) }
            .map { toCandidate(workspace, it) }
    }

    private fun collectPairs(ordered: List<Turn>): List<Pair> {
        val pairs = mutableListOf<Pair>()
        var i = 0
        while (i < ordered.size) {
            val turn = ordered[i]
            if (turn.role == TurnRole.USER) {
                val (agentBody, next) = collectAgentReplies(ordered, i + 1)
                if (agentBody.isNotBlank()) pairs.add(Pair(turn, agentBody))
                i = next
            } else {
                i += 1
            }
        }
        return pairs
    }

    private fun collectAgentReplies(
        turns: List<Turn>,
        from: Int,
    ): kotlin.Pair<String, Int> {
        val sb = StringBuilder()
        var j = from
        while (j < turns.size && turns[j].role != TurnRole.USER) {
            if (turns[j].role == TurnRole.AGENT) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(turns[j].body)
            }
            j += 1
        }
        return sb.toString() to j
    }

    private fun toCandidate(
        workspace: Workspace,
        pair: Pair,
    ): Candidate {
        val tags =
            buildList {
                add("auto-capture")
                add("agents-ui")
                add("kind:turn-pair")
                if (markerRegex.containsMatchIn(pair.agentBody)) add("has-marker")
                workspace.repoUrl?.let { add("repo:${ScopeInference.repoSlug(it)}") }
            }
        val triggerTerms = triggerTerms(workspace, pair.user.body, pair.agentBody)
        val excerpts = excerpts(pair.user.body, pair.agentBody)
        val title = makeTitle(workspace, pair.user.body)
        return Candidate(
            title = title,
            body = makeBody(pair.user.body, pair.agentBody, triggerTerms, excerpts),
            tags = tags,
            confidence = scoreFor(pair.agentBody),
            triggerTerms = triggerTerms,
            excerpts = excerpts,
            dedupeQuery = (listOf(title) + triggerTerms + excerpts).joinToString(" ").take(DEDUPE_QUERY_CHARS),
        )
    }

    private fun isWorthCapturing(
        userBody: String,
        agentBody: String,
    ): Boolean {
        val isQuestion = userBody.trim().endsWith("?") || questionStarter.containsMatchIn(userBody)
        val hasMarker = markerRegex.containsMatchIn(agentBody)
        return isQuestion || hasMarker
    }

    private fun makeTitle(
        workspace: Workspace,
        userBody: String,
    ): String {
        val prefix = workspace.name.take(TITLE_PREFIX_CHARS)
        val tail =
            userBody
                .lines()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
                .take(TITLE_TAIL_CHARS)
        return if (tail.isBlank()) prefix else "$prefix — $tail"
    }

    private fun makeBody(
        userBody: String,
        agentBody: String,
        triggerTerms: List<String>,
        excerpts: List<String>,
    ): String {
        val q = userBody.trim().take(maxBodyChars / Q_FRACTION_DENOMINATOR)
        val a = agentBody.trim().take(maxBodyChars * A_FRACTION_NUMERATOR / A_FRACTION_DENOMINATOR)
        return buildString {
            append("Trigger:\n")
            triggerTerms.forEach { append("- ").append(it).append('\n') }
            append("\nEvidence:\n")
            excerpts.forEach { append("- ").append(it).append('\n') }
            append("\nLesson:\n")
            append("Q: ").append(q).append("\n\n")
            append("A: ").append(a)
        }
    }

    private fun scoreFor(agentBody: String): Double {
        val markerBonus = if (markerRegex.containsMatchIn(agentBody)) MARKER_BONUS else 0.0
        val lengthBonus = (agentBody.length.coerceAtMost(LENGTH_CAP) / LENGTH_DIVISOR)
        val codeFenceBonus = if (agentBody.contains("```")) CODE_FENCE_BONUS else 0.0
        return (BASELINE + markerBonus + lengthBonus + codeFenceBonus).coerceIn(0.0, MAX_CONFIDENCE)
    }

    private fun triggerTerms(
        workspace: Workspace,
        userBody: String,
        agentBody: String,
    ): List<String> {
        val text = "$userBody\n$agentBody"
        val triggers = linkedSetOf<String>()
        workspace.repoUrl?.let { triggers += ScopeInference.repoSlug(it) }
        pathRegex.findAll(text).take(MAX_PATH_TRIGGERS).forEach { triggers += it.value }
        commandRegex.findAll(text).take(MAX_COMMAND_TRIGGERS).forEach { triggers += it.value }
        wordRegex.findAll(userBody).forEach { match ->
            val word = match.value.lowercase()
            if (word !in stopWords) triggers += word
            if (triggers.size >= MAX_TRIGGER_TERMS) return triggers.toList()
        }
        wordRegex.findAll(agentBody).forEach { match ->
            val word = match.value.lowercase()
            if (word !in stopWords) triggers += word
            if (triggers.size >= MAX_TRIGGER_TERMS) return triggers.toList()
        }
        return triggers.take(MAX_TRIGGER_TERMS)
    }

    private fun excerpts(
        userBody: String,
        agentBody: String,
    ): List<String> =
        listOfNotNull(
            compactExcerpt("user", userBody),
            compactExcerpt("agent", agentBody),
        )

    private fun compactExcerpt(
        label: String,
        text: String,
    ): String? {
        val normalized =
            text
                .lines()
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString(" ")
        if (normalized.isBlank()) return null
        return "$label: ${normalized.take(EXCERPT_CHARS)}"
    }

    companion object {
        const val MIN_BODY_CHARS = 240
        const val MAX_BODY_CHARS = 4_000

        private const val TITLE_PREFIX_CHARS = 40
        private const val TITLE_TAIL_CHARS = 120
        private const val Q_FRACTION_DENOMINATOR = 4
        private const val A_FRACTION_NUMERATOR = 3
        private const val A_FRACTION_DENOMINATOR = 4

        private const val BASELINE = 0.35
        private const val MARKER_BONUS = 0.15
        private const val LENGTH_CAP = 2_000
        private const val LENGTH_DIVISOR = 4_000.0
        private const val CODE_FENCE_BONUS = 0.05
        private const val MAX_CONFIDENCE = 0.85
        private const val MAX_TRIGGER_TERMS = 14
        private const val MAX_PATH_TRIGGERS = 4
        private const val MAX_COMMAND_TRIGGERS = 4
        private const val EXCERPT_CHARS = 320
        private const val DEDUPE_QUERY_CHARS = 1_200

        private val pathRegex = Regex("""[\w./-]+\.(?:kt|kts|ts|tsx|vue|py|ya?ml|sh|nix|md|sql|json|toml)""")
        private val commandRegex =
            Regex("""(?:\./gradlew|gradle|npm|pnpm|kubectl|helm|flux|kustomize|docker|git|gh|nix|uv|python3?)\b""")
        private val wordRegex = Regex("""[A-Za-z][A-Za-z0-9_-]{3,}""")
        private val stopWords =
            setOf(
                "about",
                "after",
                "agent",
                "also",
                "answer",
                "because",
                "before",
                "does",
                "from",
                "have",
                "into",
                "should",
                "that",
                "their",
                "there",
                "this",
                "turn",
                "what",
                "when",
                "where",
                "which",
                "with",
                "work",
                "would",
            )
    }
}
