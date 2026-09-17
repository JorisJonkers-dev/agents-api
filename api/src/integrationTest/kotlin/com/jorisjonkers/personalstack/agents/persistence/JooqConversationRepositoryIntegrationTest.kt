package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class JooqConversationRepositoryIntegrationTest
    @Autowired
    constructor(
        private val conversations: ConversationRepository,
    ) : IntegrationTestBase {
        private fun newConversation(
            userId: UUID = UUID.randomUUID(),
            title: String? = "x",
            kind: ConversationKind = ConversationKind.PLAIN,
        ) = Conversation(
            id = ConversationId.random(),
            userId = userId,
            title = title,
            status = ConversationStatus.ACTIVE,
            kind = kind,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        @Test
        fun saveAndFindByIdRoundTripTheKind() {
            val c = newConversation(kind = ConversationKind.KNOWLEDGE)
            conversations.save(c)
            assertThat(conversations.findById(c.id).required().kind).isEqualTo(ConversationKind.KNOWLEDGE)
        }

        @Test
        fun saveAndFindByIdRoundTripWithNullTitle() {
            val c = newConversation(title = null)
            conversations.save(c)
            val loaded = (conversations.findById(c.id)).required()
            assertThat(loaded.title).isNull()
            assertThat(loaded.status).isEqualTo(ConversationStatus.ACTIVE)
        }

        @Test
        fun findallbyuseridReturnsOnlyTheUserSConversations() {
            val userA = UUID.randomUUID()
            val userB = UUID.randomUUID()
            conversations.save(newConversation(userId = userA, title = "A1"))
            conversations.save(newConversation(userId = userA, title = "A2"))
            conversations.save(newConversation(userId = userB, title = "B"))

            val forA = conversations.findAllByUserId(userA)
            assertThat(forA).hasSize(2)
            assertThat(forA.map { it.title }).containsExactlyInAnyOrder("A1", "A2")
        }

        @Test
        fun saveUpdatesTheRowOnConflict() {
            val c = newConversation()
            conversations.save(c)
            conversations.save(c.copy(status = ConversationStatus.ARCHIVED, updatedAt = Instant.now()))

            val loaded = conversations.findById(c.id).required()
            assertThat(loaded.status).isEqualTo(ConversationStatus.ARCHIVED)
        }

        @Test
        fun deleteRemovesTheRow() {
            val c = newConversation()
            conversations.save(c)
            conversations.delete(c.id)
            assertThat(conversations.findById(c.id)).isNull()
        }
    }
