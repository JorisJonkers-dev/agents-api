package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqConversationRepository(
    private val dsl: DSLContext,
) : ConversationRepository {
    override fun save(conversation: Conversation): Conversation {
        val createdAt = conversation.createdAt.atOffset(ZoneOffset.UTC)
        val updatedAt = conversation.updatedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(TABLE)
            .set(ID, conversation.id.value)
            .set(USER_ID, conversation.userId)
            .set(TITLE, conversation.title)
            .set(STATUS, conversation.status.name)
            .set(KIND, conversation.kind.name)
            .set(CREATED_AT, createdAt)
            .set(UPDATED_AT, updatedAt)
            .onConflict(ID)
            .doUpdate()
            .set(TITLE, conversation.title)
            .set(STATUS, conversation.status.name)
            .set(UPDATED_AT, updatedAt)
            .execute()
        return conversation
    }

    override fun findById(id: ConversationId): Conversation? =
        dsl
            .selectFrom(TABLE)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toConversation()

    override fun findAllByUserId(userId: UUID): List<Conversation> =
        dsl
            .selectFrom(TABLE)
            .where(USER_ID.eq(userId))
            .orderBy(CREATED_AT.desc())
            .fetch()
            .map { it.toConversation() }

    override fun delete(id: ConversationId) {
        dsl.deleteFrom(TABLE).where(ID.eq(id.value)).execute()
    }

    private fun Record.toConversation(): Conversation =
        Conversation(
            id = ConversationId(this[ID]),
            userId = this[USER_ID],
            title = this[TITLE],
            status = ConversationStatus.valueOf(this[STATUS]),
            kind = ConversationKind.valueOf(this[KIND]),
            createdAt = this[CREATED_AT].toInstant(),
            updatedAt = this[UPDATED_AT].toInstant(),
        )

    companion object {
        @JvmStatic val TABLE = DSL.table("conversations")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val USER_ID = DSL.field("user_id", UUID::class.java)

        @JvmStatic val TITLE = DSL.field("title", String::class.java)

        @JvmStatic val STATUS = DSL.field("status", String::class.java)

        @JvmStatic val KIND = DSL.field("kind", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)
    }
}
