package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import com.jorisjonkers.personalstack.common.exception.NotFoundException
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class GetConversationQueryServiceTest {
    private val conversationRepository = mockk<ConversationRepository>()
    private val service = GetConversationQueryService(conversationRepository)

    @Test
    fun `findById returns conversation when found`() {
        val conversationId = ConversationId(UUID.randomUUID())
        val conversation =
            Conversation(
                id = conversationId,
                userId = UUID.randomUUID(),
                title = "Test Conversation",
                status = ConversationStatus.ACTIVE,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )
        every { conversationRepository.findById(conversationId) } returns conversation

        val result = service.findById(conversationId)

        assertThat(result.title).isEqualTo("Test Conversation")
    }

    @Test
    fun `findById throws NotFoundException when not found`() {
        val conversationId = ConversationId(UUID.randomUUID())
        every { conversationRepository.findById(conversationId) } returns null

        assertThatThrownBy { service.findById(conversationId) }
            .isInstanceOf(NotFoundException::class.java)
    }
}
