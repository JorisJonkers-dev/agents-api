package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.Message
import com.jorisjonkers.personalstack.agents.domain.model.MessageId
import com.jorisjonkers.personalstack.agents.domain.model.MessageRole
import com.jorisjonkers.personalstack.agents.domain.port.MessageRepository
import com.jorisjonkers.personalstack.agents.jooq.tables.Message.MESSAGE
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqMessageRepository(
    private val dsl: DSLContext,
) : MessageRepository {
    override fun save(message: Message): Message {
        val createdAt = message.createdAt.atOffset(ZoneOffset.UTC).toLocalDateTime()
        dsl
            .insertInto(MESSAGE)
            .set(MESSAGE.ID, message.id.value)
            .set(MESSAGE.CONVERSATION_ID, message.conversationId.value)
            .set(MESSAGE.ROLE, message.role.name)
            .set(MESSAGE.CONTENT, message.content)
            .set(MESSAGE.CREATED_AT, createdAt)
            .execute()
        return message
    }

    override fun findByConversationId(id: ConversationId): List<Message> =
        dsl
            .selectFrom(MESSAGE)
            .where(MESSAGE.CONVERSATION_ID.eq(id.value))
            .orderBy(MESSAGE.CREATED_AT.asc())
            .fetch()
            .map { it.toMessage() }

    private fun Record.toMessage(): Message =
        Message(
            id = MessageId(this[MESSAGE.ID] as UUID),
            conversationId = ConversationId(this[MESSAGE.CONVERSATION_ID] as UUID),
            role = MessageRole.valueOf(this[MESSAGE.ROLE] as String),
            content = this[MESSAGE.CONTENT] as String,
            createdAt =
                (this[MESSAGE.CREATED_AT] as java.time.LocalDateTime)
                    .toInstant(ZoneOffset.UTC),
        )
}
