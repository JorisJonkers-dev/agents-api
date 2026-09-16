package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceId
import com.jorisjonkers.personalstack.agents.domain.model.WorkspaceStatus
import com.jorisjonkers.personalstack.agents.infrastructure.persistence.JooqWorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

private const val PRE_MIGRATION_VERSION = "24"
private const val ID_PARAM = 1
private const val NAME_PARAM = 2
private const val STATUS_PARAM = 3

/**
 * V25 rewrites the three retired [WorkspaceStatus] values on existing rows.
 * Runs Flyway against a scratch schema in the same Testcontainers Postgres
 * so it can insert PENDING/STARTING/IDLE rows via raw SQL — the enum no
 * longer has those constants to construct them with — before advancing
 * past V25 and reading them back through [JooqWorkspaceRepository].
 */
class WorkspaceStatusPreparingMigrationIntegrationTest : IntegrationTestBase {
    @Value("\${spring.datasource.url}")
    lateinit var jdbcUrl: String

    @Value("\${spring.datasource.username}")
    lateinit var dbUsername: String

    @Value("\${spring.datasource.password}")
    lateinit var dbPassword: String

    @Test
    fun v25RewritesEveryRetiredStatusToItsGlossaryEquivalent() {
        val schema = freshSchemaName()
        migrate(schema, target = PRE_MIGRATION_VERSION)

        val pendingId = UUID.randomUUID()
        val startingId = UUID.randomUUID()
        val idleId = UUID.randomUUID()
        openConnection(schema).use { conn ->
            insertLegacyWorkspace(conn, pendingId, "PENDING")
            insertLegacyWorkspace(conn, startingId, "STARTING")
            insertLegacyWorkspace(conn, idleId, "IDLE")
        }

        migrate(schema, target = null)

        openConnection(schema).use { conn ->
            val repo = JooqWorkspaceRepository(DSL.using(conn, SQLDialect.POSTGRES))
            assertThat(repo.findById(WorkspaceId(pendingId)).required().status)
                .isEqualTo(WorkspaceStatus.PREPARING)
            assertThat(repo.findById(WorkspaceId(startingId)).required().status)
                .isEqualTo(WorkspaceStatus.PREPARING)
            assertThat(repo.findById(WorkspaceId(idleId)).required().status)
                .isEqualTo(WorkspaceStatus.READY)
        }
    }

    @Test
    fun v25RejectsAnUnknownStatusOnceItsCheckConstraintExists() {
        val schema = freshSchemaName()
        migrate(schema, target = null)

        openConnection(schema).use { conn ->
            assertThat(
                runCatching { insertLegacyWorkspace(conn, UUID.randomUUID(), "SOMETHING_ELSE") }.isFailure,
            ).isTrue()
        }
    }

    private fun freshSchemaName(): String = "wsstatus_" + UUID.randomUUID().toString().replace("-", "")

    private fun migrate(
        schema: String,
        target: String?,
    ) {
        val config =
            Flyway
                .configure()
                .dataSource(jdbcUrl, dbUsername, dbPassword)
                .schemas(schema)
                .locations("classpath:db/migration")
        target?.let { config.target(MigrationVersion.fromVersion(it)) }
        config.load().migrate()
    }

    private fun openConnection(schema: String): Connection {
        val conn = DriverManager.getConnection(jdbcUrl, dbUsername, dbPassword)
        conn.createStatement().use { it.execute("SET search_path TO $schema") }
        return conn
    }

    private fun insertLegacyWorkspace(
        conn: Connection,
        id: UUID,
        status: String,
    ) {
        conn
            .prepareStatement(
                """
                INSERT INTO workspaces (id, name, status, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(ID_PARAM, id)
                ps.setString(NAME_PARAM, "legacy-$status")
                ps.setString(STATUS_PARAM, status)
                ps.executeUpdate()
            }
    }
}
