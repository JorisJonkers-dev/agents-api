package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.agents.jooq.tables.Conversation.CONVERSATION
import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqConversationRepository(
    private val dsl: DSLContext,
) : ConversationRepository {
    override fun findById(id: ConversationId): Conversation? =
        dsl
            .selectFrom(CONVERSATION)
            .where(CONVERSATION.ID.eq(id.value))
            .fetchOne()
            ?.toConversation()

    override fun findByUserId(userId: UUID): List<Conversation> =
        dsl
            .selectFrom(CONVERSATION)
            .where(CONVERSATION.USER_ID.eq(userId))
            .fetch()
            .map { it.toConversation() }

    override fun save(conversation: Conversation): Conversation {
        val createdAt = conversation.createdAt.atOffset(ZoneOffset.UTC).toLocalDateTime()
        val updatedAt = conversation.updatedAt.atOffset(ZoneOffset.UTC).toLocalDateTime()
        dsl
            .insertInto(CONVERSATION)
            .set(CONVERSATION.ID, conversation.id.value)
            .set(CONVERSATION.USER_ID, conversation.userId)
            .set(CONVERSATION.TITLE, conversation.title)
            .set(CONVERSATION.STATUS, conversation.status.name)
            .set(CONVERSATION.CREATED_AT, createdAt)
            .set(CONVERSATION.UPDATED_AT, updatedAt)
            .onConflict(CONVERSATION.ID)
            .doUpdate()
            .set(CONVERSATION.TITLE, conversation.title)
            .set(CONVERSATION.STATUS, conversation.status.name)
            .set(CONVERSATION.UPDATED_AT, updatedAt)
            .execute()
        return conversation
    }

    private fun Record.toConversation(): Conversation =
        Conversation(
            id = ConversationId(this[CONVERSATION.ID] as UUID),
            userId = this[CONVERSATION.USER_ID] as UUID,
            title = this[CONVERSATION.TITLE] as String,
            status = ConversationStatus.valueOf(this[CONVERSATION.STATUS] as String),
            createdAt =
                (this[CONVERSATION.CREATED_AT] as java.time.LocalDateTime)
                    .toInstant(ZoneOffset.UTC),
            updatedAt =
                (this[CONVERSATION.UPDATED_AT] as java.time.LocalDateTime)
                    .toInstant(ZoneOffset.UTC),
        )
}
