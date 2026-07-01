package com.jorisjonkers.personalstack.agents.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.jorisjonkers.personalstack.agents.domain.model.AgentCredentialProvider
import com.jorisjonkers.personalstack.agents.domain.model.AgentOauthCredential
import com.jorisjonkers.personalstack.agents.domain.port.AgentCredentialRepository
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
class JooqAgentCredentialRepository(
    private val dsl: DSLContext,
) : AgentCredentialRepository {
    private val json: ObjectMapper = jacksonObjectMapper()

    override fun upsert(credential: AgentOauthCredential): AgentOauthCredential {
        val payload = json.writeValueAsString(credential.payload)
        val updatedAt = credential.updatedAt.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(TABLE)
            .set(USER_ID, credential.userId)
            .set(PROVIDER, credential.provider.name)
            .set(PAYLOAD, payload)
            .set(VALID, null as Boolean?)
            .set(VALIDATED_AT, null as OffsetDateTime?)
            .set(UPDATED_AT, updatedAt)
            .set(UPDATED_BY, credential.updatedBy)
            .onConflict(USER_ID, PROVIDER)
            .doUpdate()
            .set(PAYLOAD, payload)
            .set(VALID, null as Boolean?)
            .set(VALIDATED_AT, null as OffsetDateTime?)
            .set(UPDATED_AT, updatedAt)
            .set(UPDATED_BY, credential.updatedBy)
            .execute()
        return credential.copy(valid = null, validatedAt = null)
    }

    override fun find(
        userId: String,
        provider: AgentCredentialProvider,
    ): AgentOauthCredential? =
        dsl
            .selectFrom(TABLE)
            .where(USER_ID.eq(userId))
            .and(PROVIDER.eq(provider.name))
            .fetchOne()
            ?.toCredential()

    override fun markValidity(
        userId: String,
        provider: AgentCredentialProvider,
        valid: Boolean,
    ) {
        dsl
            .update(TABLE)
            .set(VALID, valid)
            .set(VALIDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(USER_ID.eq(userId))
            .and(PROVIDER.eq(provider.name))
            .execute()
    }

    override fun statusFor(userId: String): List<AgentCredentialRepository.CredentialStatus> =
        AgentCredentialProvider.entries.map { provider ->
            val rec =
                dsl
                    .select(PROVIDER, VALID, VALIDATED_AT, UPDATED_AT)
                    .from(TABLE)
                    .where(USER_ID.eq(userId))
                    .and(PROVIDER.eq(provider.name))
                    .fetchOne()
            if (rec == null) {
                AgentCredentialRepository.CredentialStatus(
                    provider = provider,
                    stored = false,
                    valid = null,
                    validatedAt = null,
                    updatedAt = null,
                )
            } else {
                AgentCredentialRepository.CredentialStatus(
                    provider = provider,
                    stored = true,
                    valid = rec.get(VALID),
                    validatedAt = rec.get(VALIDATED_AT)?.toInstant(),
                    updatedAt = rec.get(UPDATED_AT)?.toInstant(),
                )
            }
        }

    private fun Record.toCredential(): AgentOauthCredential {
        val payload: Map<String, String> =
            this.get(PAYLOAD)?.let { json.readValue<Map<String, String>>(it) }.orEmpty()
        return AgentOauthCredential(
            userId = this.get(USER_ID),
            provider = AgentCredentialProvider.valueOf(this.get(PROVIDER)),
            payload = payload,
            valid = this.get(VALID),
            validatedAt = this.get(VALIDATED_AT)?.toInstant(),
            updatedAt = this.get(UPDATED_AT).toInstant(),
            updatedBy = this.get(UPDATED_BY),
        )
    }

    private companion object {
        val TABLE = DSL.table(DSL.name("agent_oauth_credentials"))
        val USER_ID = DSL.field(DSL.name("user_id"), SQLDataType.VARCHAR)
        val PROVIDER = DSL.field(DSL.name("provider"), SQLDataType.VARCHAR)
        val PAYLOAD = DSL.field(DSL.name("payload"), SQLDataType.CLOB)
        val VALID = DSL.field(DSL.name("token_valid"), SQLDataType.BOOLEAN)
        val VALIDATED_AT = DSL.field(DSL.name("validated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val UPDATED_AT = DSL.field(DSL.name("updated_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val UPDATED_BY = DSL.field(DSL.name("updated_by"), SQLDataType.VARCHAR)
    }
}
