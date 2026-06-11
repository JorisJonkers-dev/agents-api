package com.jorisjonkers.personalstack.agents.application.chat

import com.jorisjonkers.personalstack.agents.application.command.AppendChatMessageCommand
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageRole
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import com.jorisjonkers.personalstack.agents.infrastructure.integration.LightRagClient
import com.jorisjonkers.personalstack.common.command.CommandBus
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.Executor

@Service
class ChatAnswerStreamService(
    private val sessions: ChatSessionRepository,
    private val commandBus: CommandBus,
    private val lightRag: LightRagClient,
    @param:Qualifier("chatStreamExecutor") private val executor: Executor,
) {
    fun stream(
        sessionId: ChatSessionId,
        userBody: String,
    ): SseEmitter {
        require(userBody.isNotBlank()) { "chat message body must not be blank" }
        sessions.findById(sessionId)
            ?: throw NotFoundException("ChatSession", sessionId.value.toString())

        val emitter = SseEmitter(TIMEOUT_MILLIS)
        executor.execute {
            generateAnswer(sessionId, userBody, emitter)
        }
        return emitter
    }

    private fun generateAnswer(
        sessionId: ChatSessionId,
        userBody: String,
        emitter: SseEmitter,
    ) {
        runCatching {
            val full =
                lightRag.streamQuery(userBody) { piece ->
                    sendEvent(emitter, "chunk", mapOf("text" to piece))
                }
            check(full.isNotBlank()) { "no answer produced" }
            persistAgentMessage(sessionId, full, emitter)
        }.onFailure {
            sendTerminalError(emitter, it)
        }
    }

    private fun persistAgentMessage(
        sessionId: ChatSessionId,
        full: String,
        emitter: SseEmitter,
    ) {
        val messageId = ChatMessageId.random()
        commandBus.dispatch(
            AppendChatMessageCommand(
                messageId = messageId,
                sessionId = sessionId,
                role = ChatMessageRole.ASSISTANT,
                body = full,
            ),
        )
        sendEvent(emitter, "done", mapOf("messageId" to messageId.value.toString()))
        emitter.complete()
    }

    private fun sendTerminalError(
        emitter: SseEmitter,
        failure: Throwable,
    ) {
        runCatching {
            val message = failure.message ?: "answer generation failed"
            sendEvent(emitter, "error", mapOf("message" to message, "retryable" to true))
        }
        emitter.complete()
    }

    private fun sendEvent(
        emitter: SseEmitter,
        name: String,
        data: Map<String, Any>,
    ) {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON))
    }

    private companion object {
        private const val TIMEOUT_MILLIS = 120_000L
    }
}
