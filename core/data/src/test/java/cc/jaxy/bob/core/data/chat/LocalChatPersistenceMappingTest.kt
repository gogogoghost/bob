package cc.jaxy.bob.core.data.chat

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatPersistenceMappingTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `complete message and attachment round trip`() {
        val message = ChatMessage(
            id = "message-1",
            role = "user",
            content = "inspect this",
            createdAt = 42L,
            model = "vision-model",
            provider = "openai",
            attachments = listOf(ChatAttachment("file-1", "photo.png", "image/png", "file:///local_files/file-1")),
        )
        assertEquals(message, json.decodeFromString<ChatMessage>(json.encodeToString(message)))
    }

    @Test
    fun `topic agent relationship survives serialization`() {
        val topic = ChatTopic("topic-1", "Local topic", 10L, 20L, agentId = "agent-1")
        assertEquals(topic, json.decodeFromString<ChatTopic>(json.encodeToString(topic)))
    }
}
