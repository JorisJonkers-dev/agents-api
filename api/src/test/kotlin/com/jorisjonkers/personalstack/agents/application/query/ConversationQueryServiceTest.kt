package com.jorisjonkers.personalstack.agents.application.query

import com.jorisjonkers.personalstack.agents.domain.model.Conversation
import com.jorisjonkers.personalstack.agents.domain.model.ConversationId
import com.jorisjonkers.personalstack.agents.domain.model.ConversationKind
import com.jorisjonkers.personalstack.agents.domain.model.ConversationStatus
import com.jorisjonkers.personalstack.agents.domain.port.ConversationMessageRepository
import com.jorisjonkers.personalstack.agents.domain.port.ConversationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ConversationQueryServiceTest {
    private val conversations = mockk<ConversationRepository>()
    private val messages = mockk<ConversationMessageRepository>()
    private val service = ConversationQueryService(conversations, messages)

    private fun conversation(
        id: ConversationId = ConversationId.random(),
        userId: UUID = UUID.randomUUID(),
    ): Conversation {
        val now = Instant.now()
        return Conversation(id, userId, "x", ConversationStatus.ACTIVE, ConversationKind.PLAIN, now, now)
    }

    @Test
    fun `get returns the detail for the owner`() {
        val c = conversation()
        every { conversations.findById(c.id) } returns c
        every { messages.findAllByConversationIdOrderedByTime(c.id) } returns emptyList()

        val result = service.get(c.id, c.userId)

        assertThat(result).isEqualTo(ConversationQueryService.ConversationDetail(c, emptyList()))
    }

    @Test
    fun `get returns nothing for another user's conversation`() {
        val c = conversation()
        val otherUser = UUID.randomUUID()
        every { conversations.findById(c.id) } returns c

        val result = service.get(c.id, otherUser)

        assertThat(result).isNull()
        verify(exactly = 0) { messages.findAllByConversationIdOrderedByTime(any()) }
    }

    @Test
    fun `get returns nothing for an unknown id`() {
        val unknownId = ConversationId.random()
        val requester = UUID.randomUUID()
        every { conversations.findById(unknownId) } returns null

        val result = service.get(unknownId, requester)

        assertThat(result).isNull()
        verify(exactly = 0) { messages.findAllByConversationIdOrderedByTime(any()) }
    }

    @Test
    fun `another user's id and an unknown id are indistinguishable at the seam`() {
        val c = conversation()
        val otherUser = UUID.randomUUID()
        val unknownId = ConversationId.random()
        every { conversations.findById(c.id) } returns c
        every { conversations.findById(unknownId) } returns null

        val forAnotherUsersConversation = service.get(c.id, otherUser)
        val forAnUnknownId = service.get(unknownId, otherUser)

        assertThat(forAnotherUsersConversation).isNull()
        assertThat(forAnUnknownId).isNull()
        assertThat(forAnotherUsersConversation).isEqualTo(forAnUnknownId)
    }

    @Test
    fun `list is unaffected and remains scoped to the requesting user`() {
        val userId = UUID.randomUUID()
        val owned = conversation(userId = userId)
        every { conversations.findAllByUserId(userId) } returns listOf(owned)

        val result = service.list(userId)

        assertThat(result).containsExactly(owned)
    }
}
