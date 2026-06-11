package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.Turn
import com.jorisjonkers.personalstack.agents.domain.model.TurnId
import com.jorisjonkers.personalstack.agents.domain.model.TurnRole
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.port.TurnRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqTurnRepository(
    private val dsl: DSLContext,
) : TurnRepository {
    override fun save(turn: Turn): Turn {
        val createdAt = turn.createdAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(TURNS)
            .set(ID, turn.id.value)
            .set(SESSION_ID, turn.sessionId.value)
            .set(ROLE, turn.role.name)
            .set(BODY, turn.body)
            .set(CREATED_AT, createdAt)
            .execute()
        return turn
    }

    override fun findBySessionId(
        sessionId: WorkspaceAgentSessionId,
        limit: Int,
    ): List<Turn> =
        dsl
            .selectFrom(TURNS)
            .where(SESSION_ID.eq(sessionId.value))
            .orderBy(CREATED_AT.asc())
            .limit(limit)
            .fetch()
            .map { it.toTurn() }

    private fun Record.toTurn(): Turn =
        Turn(
            id = TurnId(this[ID] as UUID),
            sessionId = WorkspaceAgentSessionId(this[SESSION_ID] as UUID),
            role = TurnRole.valueOf(this[ROLE] as String),
            body = this[BODY] as String,
            createdAt = (this[CREATED_AT] as OffsetDateTime).toInstant(),
        )

    companion object {
        @JvmStatic val TURNS = DSL.table("turns")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val SESSION_ID = DSL.field("session_id", UUID::class.java)

        @JvmStatic val ROLE = DSL.field("role", String::class.java)

        @JvmStatic val BODY = DSL.field("body", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)
    }
}
