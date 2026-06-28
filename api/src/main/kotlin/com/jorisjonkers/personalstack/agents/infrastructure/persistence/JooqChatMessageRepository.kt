package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.ChatMessage
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ChatMessageRole
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.port.ChatMessageRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqChatMessageRepository(
    private val dsl: DSLContext,
) : ChatMessageRepository {
    override fun save(message: ChatMessage): ChatMessage {
        val createdAt = message.createdAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(TABLE)
            .set(ID, message.id.value)
            .set(SESSION_ID, message.sessionId.value)
            .set(ROLE, message.role.name)
            .set(BODY, message.body)
            .set(CREATED_AT, createdAt)
            .onConflict(ID)
            .doUpdate()
            .set(BODY, message.body)
            .execute()
        return message
    }

    override fun findById(id: ChatMessageId): ChatMessage? =
        dsl
            .selectFrom(TABLE)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toMessage()

    override fun findAllBySessionIdOrderedByTime(sessionId: ChatSessionId): List<ChatMessage> =
        dsl
            .selectFrom(TABLE)
            .where(SESSION_ID.eq(sessionId.value))
            .orderBy(CREATED_AT.asc())
            .fetch()
            .map { it.toMessage() }

    override fun deleteAllBySessionId(sessionId: ChatSessionId) {
        dsl.deleteFrom(TABLE).where(SESSION_ID.eq(sessionId.value)).execute()
    }

    private fun Record.toMessage(): ChatMessage =
        ChatMessage(
            id = ChatMessageId(this[ID]),
            sessionId = ChatSessionId(this[SESSION_ID]),
            role = ChatMessageRole.valueOf(this[ROLE]),
            body = this[BODY],
            createdAt = this[CREATED_AT].toInstant(),
        )

    companion object {
        @JvmStatic val TABLE = DSL.table("chat_session_messages")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val SESSION_ID = DSL.field("chat_session_id", UUID::class.java)

        @JvmStatic val ROLE = DSL.field("role", String::class.java)

        @JvmStatic val BODY = DSL.field("body", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)
    }
}
