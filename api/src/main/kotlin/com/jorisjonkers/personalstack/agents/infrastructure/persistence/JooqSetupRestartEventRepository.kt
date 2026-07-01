package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupId
import com.jorisjonkers.personalstack.agents.domain.model.AgentSetupVersion
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEvent
import com.jorisjonkers.personalstack.agents.domain.model.SetupRestartEventStatus
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceAgentSessionId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.port.SetupRestartEventRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqSetupRestartEventRepository(
    private val dsl: DSLContext,
) : SetupRestartEventRepository {
    @Suppress("LongMethod")
    override fun save(event: SetupRestartEvent): SetupRestartEvent {
        dsl
            .insertInto(TABLE)
            .set(ID, event.id)
            .set(WORKSPACE_ID, event.workspaceId.value)
            .set(SESSION_ID, event.sessionId?.value)
            .set(FROM_SETUP_ID, event.fromSetupId?.value)
            .set(FROM_SETUP_VERSION, event.fromSetupVersion?.value)
            .set(TO_SETUP_ID, event.toSetupId.value)
            .set(TO_SETUP_VERSION, event.toSetupVersion.value)
            .set(STATUS, event.status.name)
            .set(REASON, event.reason)
            .set(MESSAGE, event.message)
            .set(REQUESTED_AT, event.requestedAt.atOffset(ZoneOffset.UTC))
            .set(STARTED_AT, event.startedAt?.atOffset(ZoneOffset.UTC))
            .set(COMPLETED_AT, event.completedAt?.atOffset(ZoneOffset.UTC))
            .set(CREATED_AT, event.createdAt.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, event.updatedAt.atOffset(ZoneOffset.UTC))
            .onConflict(ID)
            .doUpdate()
            .set(SESSION_ID, event.sessionId?.value)
            .set(STATUS, event.status.name)
            .set(REASON, event.reason)
            .set(MESSAGE, event.message)
            .set(STARTED_AT, event.startedAt?.atOffset(ZoneOffset.UTC))
            .set(COMPLETED_AT, event.completedAt?.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, event.updatedAt.atOffset(ZoneOffset.UTC))
            .execute()
        return event
    }

    override fun findById(id: UUID): SetupRestartEvent? =
        dsl
            .selectFrom(TABLE)
            .where(ID.eq(id))
            .fetchOne()
            ?.toEvent()

    override fun findAllByWorkspaceId(workspaceId: WorkspaceId): List<SetupRestartEvent> =
        dsl
            .selectFrom(TABLE)
            .where(WORKSPACE_ID.eq(workspaceId.value))
            .orderBy(REQUESTED_AT.asc())
            .fetch()
            .map { it.toEvent() }

    override fun findAllBySessionId(sessionId: WorkspaceAgentSessionId): List<SetupRestartEvent> =
        dsl
            .selectFrom(TABLE)
            .where(SESSION_ID.eq(sessionId.value))
            .orderBy(REQUESTED_AT.asc())
            .fetch()
            .map { it.toEvent() }

    override fun updateStatusIfCurrent(update: SetupRestartEventRepository.StatusUpdate): Boolean =
        dsl
            .update(TABLE)
            .set(STATUS, update.status.name)
            .set(MESSAGE, update.message)
            .set(STARTED_AT, update.startedAt?.atOffset(ZoneOffset.UTC))
            .set(COMPLETED_AT, update.completedAt?.atOffset(ZoneOffset.UTC))
            .set(UPDATED_AT, update.now.atOffset(ZoneOffset.UTC))
            .where(ID.eq(update.id))
            .and(STATUS.eq(update.expectedStatus.name))
            .execute() == 1

    private fun Record.toEvent(): SetupRestartEvent =
        SetupRestartEvent(
            id = this[ID],
            workspaceId = WorkspaceId(this[WORKSPACE_ID]),
            sessionId = this[SESSION_ID]?.let { WorkspaceAgentSessionId(it) },
            fromSetupId = this[FROM_SETUP_ID]?.let { AgentSetupId(it) },
            fromSetupVersion = this[FROM_SETUP_VERSION]?.let { AgentSetupVersion(it) },
            toSetupId = AgentSetupId(this[TO_SETUP_ID]),
            toSetupVersion = AgentSetupVersion(this[TO_SETUP_VERSION]),
            status = SetupRestartEventStatus.valueOf(this[STATUS]),
            reason = this[REASON],
            message = this[MESSAGE],
            requestedAt = this[REQUESTED_AT].toInstant(),
            startedAt = this[STARTED_AT]?.toInstant(),
            completedAt = this[COMPLETED_AT]?.toInstant(),
            createdAt = this[CREATED_AT].toInstant(),
            updatedAt = this[UPDATED_AT].toInstant(),
        )

    companion object {
        @JvmStatic val TABLE = DSL.table("setup_restart_events")

        @JvmStatic val ID = DSL.field("id", UUID::class.java)

        @JvmStatic val WORKSPACE_ID = DSL.field("workspace_id", UUID::class.java)

        @JvmStatic val SESSION_ID = DSL.field("session_id", UUID::class.java)

        @JvmStatic val FROM_SETUP_ID = DSL.field("from_setup_id", String::class.java)

        @JvmStatic val FROM_SETUP_VERSION = DSL.field("from_setup_version", Long::class.javaObjectType)

        @JvmStatic val TO_SETUP_ID = DSL.field("to_setup_id", String::class.java)

        @JvmStatic val TO_SETUP_VERSION = DSL.field("to_setup_version", Long::class.javaObjectType)

        @JvmStatic val STATUS = DSL.field("status", String::class.java)

        @JvmStatic val REASON = DSL.field("reason", String::class.java)

        @JvmStatic val MESSAGE = DSL.field("message", String::class.java)

        @JvmStatic val REQUESTED_AT = DSL.field("requested_at", OffsetDateTime::class.java)

        @JvmStatic val STARTED_AT = DSL.field("started_at", OffsetDateTime::class.java)

        @JvmStatic val COMPLETED_AT = DSL.field("completed_at", OffsetDateTime::class.java)

        @JvmStatic val CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

        @JvmStatic val UPDATED_AT = DSL.field("updated_at", OffsetDateTime::class.java)
    }
}
