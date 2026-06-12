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
    // Single fluent jOOQ upsert keeps insert/update column parity visible.
    @Suppress("LongMethod")
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
            .set(EPOCH, session.epoch)
            .set(GENERATION, session.generation)
            .set(GATEWAY_BOUND_AT, session.gatewayBoundAt?.atOffset(ZoneOffset.UTC))
            .set(RETAINED_UNTIL, session.retainedUntil?.atOffset(ZoneOffset.UTC))
            .set(CLEANUP_REQUESTED_AT, session.cleanupRequestedAt?.atOffset(ZoneOffset.UTC))
            .set(CREATED_AT, createdAt)
            .set(UPDATED_AT, updatedAt)
            .onConflict(ID)
            .doUpdate()
            .set(GATEWAY_AGENT_ID, session.gatewayAgentId)
            .set(STATUS, session.status.name)
            .set(CLI_SESSION_ID, session.cliSessionId)
            .set(RUN_MODE, session.runMode)
            .set(EPOCH, session.epoch)
            .set(GENERATION, session.generation)
            .set(GATEWAY_BOUND_AT, session.gatewayBoundAt?.atOffset(ZoneOffset.UTC))
            .set(RETAINED_UNTIL, session.retainedUntil?.atOffset(ZoneOffset.UTC))
            .set(CLEANUP_REQUESTED_AT, session.cleanupRequestedAt?.atOffset(ZoneOffset.UTC))
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

    override fun beginGeneration(
        id: WorkspaceAgentSessionId,
        expectedGeneration: Long,
        nextEpoch: Long,
        now: java.time.Instant,
    ): Boolean {
        val updatedAt = now.atOffset(ZoneOffset.UTC)
        return dsl
            .update(WAS)
            .set(GATEWAY_AGENT_ID, null as String?)
            .set(STATUS, WorkspaceAgentSessionStatus.STARTING.name)
            .set(EPOCH, nextEpoch)
            .set(GENERATION, expectedGeneration + 1)
            .set(GATEWAY_BOUND_AT, null as OffsetDateTime?)
            .set(RETAINED_UNTIL, null as OffsetDateTime?)
            .set(CLEANUP_REQUESTED_AT, null as OffsetDateTime?)
            .set(UPDATED_AT, updatedAt)
            .where(ID.eq(id.value))
            .and(GENERATION.eq(expectedGeneration))
            .execute() == 1
    }

    override fun bindIfGeneration(
        id: WorkspaceAgentSessionId,
        expectedGeneration: Long,
        gatewayAgentId: String,
        cliSessionId: String?,
        now: java.time.Instant,
    ): Boolean {
        val updatedAt = now.atOffset(ZoneOffset.UTC)
        return dsl
            .update(WAS)
            .set(GATEWAY_AGENT_ID, gatewayAgentId)
            .set(STATUS, WorkspaceAgentSessionStatus.RUNNING.name)
            .set(CLI_SESSION_ID, cliSessionId)
            .set(GATEWAY_BOUND_AT, updatedAt)
            .set(RETAINED_UNTIL, null as OffsetDateTime?)
            .set(CLEANUP_REQUESTED_AT, null as OffsetDateTime?)
            .set(UPDATED_AT, updatedAt)
            .where(ID.eq(id.value))
            .and(GENERATION.eq(expectedGeneration))
            .execute() == 1
    }

    override fun clearGatewayBindingIfGeneration(
        id: WorkspaceAgentSessionId,
        expectedGeneration: Long,
        now: java.time.Instant,
    ): Boolean =
        dsl
            .update(WAS)
            .set(GATEWAY_AGENT_ID, null as String?)
            .set(GATEWAY_BOUND_AT, null as OffsetDateTime?)
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(GENERATION.eq(expectedGeneration))
            .execute() == 1

    override fun markLifecycleIfGeneration(
        id: WorkspaceAgentSessionId,
        expectedGeneration: Long,
        status: WorkspaceAgentSessionStatus,
        retainedUntil: java.time.Instant?,
        clearGatewayBinding: Boolean,
        now: java.time.Instant,
    ): Boolean {
        val update =
            dsl
                .update(WAS)
                .set(STATUS, status.name)
                .set(RETAINED_UNTIL, retainedUntil?.atOffset(ZoneOffset.UTC))
                .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
        if (clearGatewayBinding) {
            update
                .set(GATEWAY_AGENT_ID, null as String?)
                .set(GATEWAY_BOUND_AT, null as OffsetDateTime?)
        }
        return update
            .where(ID.eq(id.value))
            .and(GENERATION.eq(expectedGeneration))
            .execute() == 1
    }

    override fun findReadyForCleanup(
        now: java.time.Instant,
        limit: Int,
    ): List<WorkspaceAgentSession> =
        dsl
            .selectFrom(WAS)
            .where(STATUS.`in`(WorkspaceAgentSessionStatus.STOPPED.name, WorkspaceAgentSessionStatus.FAILED.name))
            .and(RETAINED_UNTIL.isNotNull)
            .and(RETAINED_UNTIL.le(now.atOffset(ZoneOffset.UTC)))
            .and(CLEANUP_REQUESTED_AT.isNull)
            .orderBy(RETAINED_UNTIL.asc())
            .limit(limit)
            .fetch()
            .map { it.toSession() }

    override fun markCleanupRequested(
        id: WorkspaceAgentSessionId,
        now: java.time.Instant,
    ): Boolean =
        dsl
            .update(WAS)
            .set(CLEANUP_REQUESTED_AT, now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(CLEANUP_REQUESTED_AT.isNull)
            .execute() == 1

    override fun findCleanupRequested(limit: Int): List<WorkspaceAgentSession> =
        dsl
            .selectFrom(WAS)
            .where(CLEANUP_REQUESTED_AT.isNotNull)
            .orderBy(CLEANUP_REQUESTED_AT.asc())
            .limit(limit)
            .fetch()
            .map { it.toSession() }

    override fun delete(id: WorkspaceAgentSessionId): Boolean =
        dsl.deleteFrom(WAS).where(ID.eq(id.value)).execute() == 1

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
            epoch = this[EPOCH] ?: 1,
            generation = this[GENERATION] ?: 0,
            gatewayBoundAt = this[GATEWAY_BOUND_AT]?.toInstant(),
            retainedUntil = this[RETAINED_UNTIL]?.toInstant(),
            cleanupRequestedAt = this[CLEANUP_REQUESTED_AT]?.toInstant(),
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

        @JvmStatic val EPOCH = DSL.field("epoch", Long::class.javaObjectType)

        @JvmStatic val GENERATION = DSL.field("generation", Long::class.javaObjectType)

        @JvmStatic val GATEWAY_BOUND_AT = DSL.field("gateway_bound_at", OffsetDateTime::class.java)

        @JvmStatic val RETAINED_UNTIL = DSL.field("retained_until", OffsetDateTime::class.java)

        @JvmStatic val CLEANUP_REQUESTED_AT = DSL.field("cleanup_requested_at", OffsetDateTime::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)
    }
}
