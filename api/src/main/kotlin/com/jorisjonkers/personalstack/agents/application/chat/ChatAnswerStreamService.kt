package com.jorisjonkers.personalstack.agents.application.chat

import com.jorisjonkers.personalstack.agents.application.command.AppendConversationMessageCommand
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageRole
import com.jorisjonkers.personalstack.agents.domain.port.ChatGenerationPort
import com.jorisjonkers.personalstack.agents.domain.port.ConversationMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.common.command.CommandBus
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.Executor

@Service
class ChatAnswerStreamService(
    private val conversations: ConversationRepository,
    private val messages: ConversationMessageRepository,
    private val commandBus: CommandBus,
    private val generation: ChatGenerationPort,
    @param:Qualifier("chatStreamExecutor") private val executor: Executor,
) {
    fun stream(
        conversationId: ConversationId,
        userBody: String,
    ): SseEmitter {
        require(userBody.isNotBlank()) { "chat message body must not be blank" }
        conversations.findById(conversationId)
            ?: throw NotFoundException("Conversation", conversationId.value.toString())

        val emitter = SseEmitter(TIMEOUT_MILLIS)
        executor.execute {
            generateAnswer(conversationId, userBody, emitter)
        }
        return emitter
    }

    private fun generateAnswer(
        conversationId: ConversationId,
        userBody: String,
        emitter: SseEmitter,
    ) {
        runCatching {
            val prompt = buildPrompt(conversationId, userBody)
            val full =
                generation.generate(prompt) { piece ->
                    sendEvent(emitter, "chunk", mapOf("text" to piece))
                }
            check(full.isNotBlank()) { "no answer produced" }
            persistAgentMessage(conversationId, full, emitter)
        }.onFailure {
            sendTerminalError(emitter, it)
        }
    }

    private fun buildPrompt(
        conversationId: ConversationId,
        userBody: String,
    ): String {
        val history =
            messages
                .findAllByConversationIdOrderedByTime(conversationId)
                .takeLast(MAX_HISTORY_MESSAGES)
        if (history.isEmpty()) return userBody

        // The caller may or may not have persisted the current user turn before
        // streaming. Only append it when history does not already end with it, so
        // the prompt is identical regardless of persist-then-stream ordering.
        val currentTurnAlreadyPersisted =
            history.last().let { it.role == ConversationMessageRole.USER && it.body == userBody }
        return buildString {
            for (message in history) {
                append(labelFor(message.role))
                append(": ")
                append(message.body)
                append('\n')
            }
            if (!currentTurnAlreadyPersisted) {
                append("User: ")
                append(userBody)
            }
        }.trimEnd('\n')
    }

    private fun labelFor(role: ConversationMessageRole): String =
        when (role) {
            ConversationMessageRole.USER -> "User"
            ConversationMessageRole.ASSISTANT -> "Assistant"
            ConversationMessageRole.SYSTEM -> "System"
        }

    private fun persistAgentMessage(
        conversationId: ConversationId,
        full: String,
        emitter: SseEmitter,
    ) {
        val messageId = ConversationMessageId.random()
        commandBus.dispatch(
            AppendConversationMessageCommand(
                messageId = messageId,
                conversationId = conversationId,
                role = ConversationMessageRole.ASSISTANT,
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
        private const val MAX_HISTORY_MESSAGES = 20
    }
}
