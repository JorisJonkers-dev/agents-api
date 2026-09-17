package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessage
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationMessageRole
import com.jorisjonkers.personalstack.agents.domain.port.ConversationMessageRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqConversationMessageRepository(
    private val dsl: DSLContext,
) : ConversationMessageRepository {
    override fun save(message: ConversationMessage): ConversationMessage {
        val createdAt = message.createdAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(TABLE)
            .set(ID, message.id.value)
            .set(CONVERSATION_ID, message.conversationId.value)
            .set(ROLE, message.role.name)
            .set(BODY, message.body)
            .set(CREATED_AT, createdAt)
            .onConflict(ID)
            .doUpdate()
            .set(BODY, message.body)
            .execute()
        return message
    }

    override fun findById(id: ConversationMessageId): ConversationMessage? =
        dsl
            .selectFrom(TABLE)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toMessage()

    override fun findAllByConversationIdOrderedByTime(conversationId: ConversationId): List<ConversationMessage> =
        dsl
            .selectFrom(TABLE)
            .where(CONVERSATION_ID.eq(conversationId.value))
            .orderBy(CREATED_AT.asc())
            .fetch()
            .map { it.toMessage() }

    override fun deleteAllByConversationId(conversationId: ConversationId) {
        dsl.deleteFrom(TABLE).where(CONVERSATION_ID.eq(conversationId.value)).execute()
    }

    private fun Record.toMessage(): ConversationMessage =
        ConversationMessage(
            id = ConversationMessageId(this[ID]),
            conversationId = ConversationId(this[CONVERSATION_ID]),
            role = ConversationMessageRole.valueOf(this[ROLE]),
            body = this[BODY],
            createdAt = this[CREATED_AT].toInstant(),
        )

    companion object {
        @JvmStatic val TABLE = DSL.table("conversation_messages")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val CONVERSATION_ID = DSL.field("conversation_id", UUID::class.java)

        @JvmStatic val ROLE = DSL.field("role", String::class.java)

        @JvmStatic val BODY = DSL.field("body", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)
    }
}
