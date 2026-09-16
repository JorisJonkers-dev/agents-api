package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.AgentSession
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSessionStatus
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentKind
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.AgentSessionRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqAgentSessionRepository(
    private val dsl: DSLContext,
) : AgentSessionRepository {
    // Single fluent jOOQ upsert keeps insert/update column parity visible.
    override fun save(session: AgentSession): AgentSession {
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
            .set(CURRENT_SETUP_ID, session.currentSetupId.value)
            .set(CURRENT_SETUP_VERSION, session.currentSetupVersion.value)
            .set(PENDING_SETUP_ID, session.pendingSetupId?.value)
            .set(PENDING_SETUP_VERSION, session.pendingSetupVersion?.value)
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

    override fun findById(id: AgentSessionId): AgentSession? =
        dsl
            .selectFrom(WAS)
            .where(ID.eq(id.value))
            .fetchOne()
            ?.toSession()

    override fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<AgentSession> =
        dsl
            .selectFrom(WAS)
            .where(WORKSPACE_ID.eq(workspaceId.value))
            .orderBy(CREATED_AT.asc())
            .fetch()
            .map { it.toSession() }

    override fun beginGeneration(
        id: AgentSessionId,
        expectedGeneration: Long,
        nextEpoch: Long,
        now: java.time.Instant,
    ): Boolean {
        val updatedAt = now.atOffset(ZoneOffset.UTC)
        return dsl
            .update(WAS)
            .set(GATEWAY_AGENT_ID, null as String?)
            .set(STATUS, AgentSessionStatus.STARTING.name)
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
        id: AgentSessionId,
        expectedGeneration: Long,
        gatewayAgentId: String,
        cliSessionId: String?,
        now: java.time.Instant,
    ): Boolean {
        val updatedAt = now.atOffset(ZoneOffset.UTC)
        return dsl
            .update(WAS)
            .set(GATEWAY_AGENT_ID, gatewayAgentId)
            .set(STATUS, AgentSessionStatus.RUNNING.name)
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
        id: AgentSessionId,
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

    override fun markLifecycleIfGeneration(update: AgentSessionRepository.LifecycleUpdate): Boolean {
        val jooqUpdate =
            dsl
                .update(WAS)
                .set(STATUS, update.status.name)
                .set(RETAINED_UNTIL, update.retainedUntil?.atOffset(ZoneOffset.UTC))
                .set(UPDATED_AT, update.now.atOffset(ZoneOffset.UTC))
        val updateWithGatewayBinding =
            if (update.clearGatewayBinding) {
                jooqUpdate
                    .set(GATEWAY_AGENT_ID, null as String?)
                    .set(GATEWAY_BOUND_AT, null as OffsetDateTime?)
            } else {
                jooqUpdate
            }
        return updateWithGatewayBinding
            .where(ID.eq(update.id.value))
            .and(GENERATION.eq(update.expectedGeneration))
            .execute() == 1
    }

    override fun findReadyForCleanup(
        now: java.time.Instant,
        limit: Int,
    ): List<AgentSession> =
        dsl
            .selectFrom(WAS)
            .where(STATUS.`in`(AgentSessionStatus.STOPPED.name, AgentSessionStatus.FAILED.name))
            .and(RETAINED_UNTIL.isNotNull)
            .and(RETAINED_UNTIL.le(now.atOffset(ZoneOffset.UTC)))
            .and(CLEANUP_REQUESTED_AT.isNull)
            .orderBy(RETAINED_UNTIL.asc())
            .limit(limit)
            .fetch()
            .map { it.toSession() }

    override fun markCleanupRequested(
        id: AgentSessionId,
        now: java.time.Instant,
    ): Boolean =
        dsl
            .update(WAS)
            .set(CLEANUP_REQUESTED_AT, now.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(CLEANUP_REQUESTED_AT.isNull)
            .execute() == 1

    override fun setPendingSetupIfCurrent(update: AgentSessionRepository.PendingSetupUpdate): Boolean =
        dsl
            .update(WAS)
            .set(PENDING_SETUP_ID, update.pendingSetupId.value)
            .set(PENDING_SETUP_VERSION, update.pendingSetupVersion.value)
            .set(UPDATED_AT, update.now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(update.id.value))
            .and(CURRENT_SETUP_ID.eq(update.expectedCurrentSetupId.value))
            .and(CURRENT_SETUP_VERSION.eq(update.expectedCurrentSetupVersion.value))
            .and(PENDING_SETUP_ID.isNull)
            .execute() == 1

    override fun promotePendingSetupIfCurrent(
        id: AgentSessionId,
        expectedPendingSetupId: AgentSetupId,
        expectedPendingSetupVersion: AgentSetupVersion,
        now: java.time.Instant,
    ): Boolean =
        dsl
            .update(WAS)
            .set(CURRENT_SETUP_ID, PENDING_SETUP_ID)
            .set(CURRENT_SETUP_VERSION, PENDING_SETUP_VERSION)
            .set(PENDING_SETUP_ID, null as String?)
            .set(PENDING_SETUP_VERSION, null as Long?)
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(PENDING_SETUP_ID.eq(expectedPendingSetupId.value))
            .and(PENDING_SETUP_VERSION.eq(expectedPendingSetupVersion.value))
            .execute() == 1

    override fun clearPendingSetupIfCurrent(
        id: AgentSessionId,
        expectedPendingSetupId: AgentSetupId,
        expectedPendingSetupVersion: AgentSetupVersion,
        now: java.time.Instant,
    ): Boolean =
        dsl
            .update(WAS)
            .set(PENDING_SETUP_ID, null as String?)
            .set(PENDING_SETUP_VERSION, null as Long?)
            .set(UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .and(PENDING_SETUP_ID.eq(expectedPendingSetupId.value))
            .and(PENDING_SETUP_VERSION.eq(expectedPendingSetupVersion.value))
            .execute() == 1

    override fun findCleanupRequested(limit: Int): List<AgentSession> =
        dsl
            .selectFrom(WAS)
            .where(CLEANUP_REQUESTED_AT.isNotNull)
            .orderBy(CLEANUP_REQUESTED_AT.asc())
            .limit(limit)
            .fetch()
            .map { it.toSession() }

    override fun delete(id: AgentSessionId): Boolean = dsl.deleteFrom(WAS).where(ID.eq(id.value)).execute() == 1

    private fun Record.toSession(): AgentSession =
        AgentSession(
            id = AgentSessionId(this[ID]),
            workspaceId = WorkspaceId(this[WORKSPACE_ID]),
            kind = WorkspaceAgentKind.valueOf(this[KIND]),
            gatewayAgentId = this[GATEWAY_AGENT_ID],
            status = AgentSessionStatus.valueOf(this[STATUS]),
            createdAt = this[CREATED_AT].toInstant(),
            updatedAt = this[UPDATED_AT].toInstant(),
            cliSessionId = this[CLI_SESSION_ID],
            runMode = this[RUN_MODE] ?: "INTERACTIVE",
            epoch = this[EPOCH] ?: 1,
            generation = this[GENERATION] ?: 0,
            gatewayBoundAt = this[GATEWAY_BOUND_AT]?.toInstant(),
            retainedUntil = this[RETAINED_UNTIL]?.toInstant(),
            cleanupRequestedAt = this[CLEANUP_REQUESTED_AT]?.toInstant(),
            currentSetupId = AgentSetupId(this[CURRENT_SETUP_ID] ?: "default"),
            currentSetupVersion = AgentSetupVersion(this[CURRENT_SETUP_VERSION] ?: 1),
            pendingSetupId = this[PENDING_SETUP_ID]?.let { AgentSetupId(it) },
            pendingSetupVersion = this[PENDING_SETUP_VERSION]?.let { AgentSetupVersion(it) },
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

        @JvmStatic val CURRENT_SETUP_ID = DSL.field("current_setup_id", String::class.java)

        @JvmStatic val CURRENT_SETUP_VERSION = DSL.field("current_setup_version", Long::class.javaObjectType)

        @JvmStatic val PENDING_SETUP_ID = DSL.field("pending_setup_id", String::class.java)

        @JvmStatic val PENDING_SETUP_VERSION = DSL.field("pending_setup_version", Long::class.javaObjectType)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)
    }
}
