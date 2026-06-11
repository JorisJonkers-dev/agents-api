package com.jorisjonkers.personalstack.agents.persistence

import com.jorisjonkers.personalstack.agents.IntegrationTestBase
import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.util.UUID

class JooqConversationRepositoryIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var conversationRepository: ConversationRepository

    @Test
    fun `save and findById returns the saved conversation`() {
        val conversation = buildConversation(title = "Test Conversation")
        conversationRepository.save(conversation)

        val found = conversationRepository.findById(conversation.id)

        assertThat(found).isNotNull
        assertThat(found!!.title).isEqualTo("Test Conversation")
        assertThat(found.status).isEqualTo(ConversationStatus.ACTIVE)
    }

    @Test
    fun `findById returns null when conversation does not exist`() {
        val result = conversationRepository.findById(ConversationId(UUID.randomUUID()))

        assertThat(result).isNull()
    }

    @Test
    fun `findByUserId returns all conversations for the user`() {
        val userId = UUID.randomUUID()
        val first = buildConversation(userId = userId, title = "First")
        val second = buildConversation(userId = userId, title = "Second")
        val other = buildConversation(title = "Other User")
        conversationRepository.save(first)
        conversationRepository.save(second)
        conversationRepository.save(other)

        val results = conversationRepository.findByUserId(userId)

        assertThat(results).hasSize(2)
        assertThat(results.map { it.title }).containsExactlyInAnyOrder("First", "Second")
    }

    @Test
    fun `save updates existing conversation on conflict`() {
        val conversation = buildConversation(title = "Original")
        conversationRepository.save(conversation)

        val updated =
            conversation.copy(
                title = "Updated",
                status = ConversationStatus.ARCHIVED,
                updatedAt = Instant.now(),
            )
        conversationRepository.save(updated)

        val found = conversationRepository.findById(conversation.id)
        assertThat(found).isNotNull
        assertThat(found!!.title).isEqualTo("Updated")
        assertThat(found.status).isEqualTo(ConversationStatus.ARCHIVED)
    }

    private fun buildConversation(
        userId: UUID = UUID.randomUUID(),
        title: String = "Test",
    ): Conversation {
        val now = Instant.now()
        return Conversation(
            id = ConversationId(UUID.randomUUID()),
            userId = userId,
            title = title,
            status = ConversationStatus.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }
}
