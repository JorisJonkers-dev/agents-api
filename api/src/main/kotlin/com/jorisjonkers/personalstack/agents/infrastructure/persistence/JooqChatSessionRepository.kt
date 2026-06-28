package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.ChatSession
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionId
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionKind
import com.jorisjonkers.personalstack.agents.domain.model.ChatSessionStatus
import com.jorisjonkers.personalstack.agents.domain.port.ChatSessionRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqChatSessionRepository(
    private val dsl: DSLContext,
) : ChatSessionRepository {
    override fun save(session: ChatSession): ChatSession {
        val createdAt = session.createdAt.atOffset(ZoneOffset.UTC)
        val updatedAt = session.updatedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(TABLE)
            .set(ID, session.id.value)
            .set(USER_ID, session.userId)
            .set(TITLE, session.title)
            .set(STATUS, session.status.name)
            .set(KIND, session.kind.name)
            .set(CREATED_AT, createdAt)
            .set(UPDATED_AT, updatedAt)
            .onConflict(ID)
            .doUpdate()
            .set(TITLE, session.title)
            .set(STATUS, session.status.name)
            .set(UPDATED_AT, updatedAt)
            .execute()
        return session
    }

    override fun findById(id: ChatSessionId): ChatSession? =
        dsl
            .selectFrom(TABLE)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toSession()

    override fun findAllByUserId(userId: UUID): List<ChatSession> =
        dsl
            .selectFrom(TABLE)
            .where(USER_ID.eq(userId))
            .orderBy(CREATED_AT.desc())
            .fetch()
            .map { it.toSession() }

    override fun delete(id: ChatSessionId) {
        dsl.deleteFrom(TABLE).where(ID.eq(id.value)).execute()
    }

    private fun Record.toSession(): ChatSession =
        ChatSession(
            id = ChatSessionId(this[ID]),
            userId = this[USER_ID],
            title = this[TITLE],
            status = ChatSessionStatus.valueOf(this[STATUS]),
            kind = ChatSessionKind.valueOf(this[KIND]),
            createdAt = this[CREATED_AT].toInstant(),
            updatedAt = this[UPDATED_AT].toInstant(),
        )

    companion object {
        @JvmStatic val TABLE = DSL.table("chat_sessions")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val USER_ID = DSL.field("user_id", UUID::class.java)

        @JvmStatic val TITLE = DSL.field("title", String::class.java)

        @JvmStatic val STATUS = DSL.field("status", String::class.java)

        @JvmStatic val KIND = DSL.field("kind", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)
    }
}
