package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSession
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.WorkspaceAgentSessionRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqWorkspaceAgentSessionRepository(
    private val dsl: DSLContext,
) : WorkspaceAgentSessionRepository {
    override fun save(session: WorkspaceAgentSession): WorkspaceAgentSession {
        val createdAt = session.createdAt.atOffset(ZoneOffset.UTC)
        val updatedAt = session.updatedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(WAS)
            .set(ID, session.id.value)
            .set(WORKSPACE_ID, session.workspaceId.value)
            .set(KIND, session.kind.name)
            .set(GATEWAY_AGENT_ID, session.gatewayAgentId)
            .set(STATUS, session.status.name)
            .set(CLI_SESSION_ID, session.cliSessionId)
            .set(RUN_MODE, session.runMode)
            .set(CREATED_AT, createdAt)
            .set(UPDATED_AT, updatedAt)
            .onConflict(ID)
            .doUpdate()
            .set(GATEWAY_AGENT_ID, session.gatewayAgentId)
            .set(STATUS, session.status.name)
            .set(CLI_SESSION_ID, session.cliSessionId)
            .set(RUN_MODE, session.runMode)
            .set(UPDATED_AT, updatedAt)
            .execute()
        return session
    }

    override fun findById(id: WorkspaceAgentSessionId): WorkspaceAgentSession? =
        dsl
            .selectFrom(WAS)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toSession()

    override fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<WorkspaceAgentSession> =
        dsl
            .selectFrom(WAS)
            .where(WORKSPACE_ID.eq(workspaceId.value))
            .orderBy(CREATED_AT.asc())
            .fetch()
            .map { it.toSession() }

    override fun delete(id: WorkspaceAgentSessionId) {
        dsl.deleteFrom(WAS).where(ID.eq(id.value)).execute()
    }

    private fun Record.toSession(): WorkspaceAgentSession =
        WorkspaceAgentSession(
            id = WorkspaceAgentSessionId(this[ID]),
            workspaceId = WorkspaceId(this[WORKSPACE_ID]),
            kind = WorkspaceAgentKind.valueOf(this[KIND]),
            gatewayAgentId = this[GATEWAY_AGENT_ID],
            status = WorkspaceAgentSessionStatus.valueOf(this[STATUS]),
            createdAt = this[CREATED_AT].toInstant(),
            updatedAt = this[UPDATED_AT].toInstant(),
            cliSessionId = this[CLI_SESSION_ID],
            runMode = this[RUN_MODE] ?: "INTERACTIVE",
        )

    companion object {
        @JvmStatic val WAS = DSL.table("workspace_agent_sessions")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val WORKSPACE_ID = DSL.field("workspace_id", UUID::class.java)

        @JvmStatic val KIND = DSL.field("kind", String::class.java)

        @JvmStatic val GATEWAY_AGENT_ID = DSL.field("gateway_agent_id", String::class.java)

        @JvmStatic val STATUS = DSL.field("status", String::class.java)

        @JvmStatic val CLI_SESSION_ID = DSL.field("cli_session_id", String::class.java)

        @JvmStatic val RUN_MODE = DSL.field("run_mode", String::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)
    }
}
