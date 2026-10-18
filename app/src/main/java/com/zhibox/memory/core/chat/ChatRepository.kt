package com.zhibox.memory.core.chat

import com.zhibox.memory.data.db.ChatConversationEntity
import com.zhibox.memory.data.db.ChatDao
import com.zhibox.memory.data.db.ChatMessageEntity
import com.zhibox.memory.data.db.MessageCitationEntity
import java.util.UUID

/**
 * B11：Chat 会话/消息/引用持久化契约（P0 单轮问答；会话历史可查看但不注入下一问）。
 *
 * - 会话/消息/引用写入 Room，删除会话由 FK CASCADE 清理消息/引用；
 * - `sourceScope` 以来源 id 列表保存（空=全部来源；不保存正文/路径）；
 * - 引用只保存 chunkId + rank；证据正文由 chunk 表重新读取，避免重复存储；
 * - 所有时间戳由调用方传入，便于测试确定性。
 */
interface ChatRepository {
    suspend fun createConversation(
        title: String,
        sourceScope: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): ChatConversation

    suspend fun conversation(id: String): ChatConversation?

    suspend fun conversations(limit: Int = 50): List<ChatConversation>

    suspend fun appendMessage(
        conversationId: String,
        role: ChatRole,
        content: String,
        model: String?,
        now: Long = System.currentTimeMillis(),
    ): ChatMessage

    suspend fun appendCitations(messageId: String, citations: List<ChatCitation>)

    suspend fun messages(conversationId: String): List<ChatMessage>

    suspend fun citations(messageId: String): List<ChatCitation>

    suspend fun renameConversation(id: String, title: String, now: Long = System.currentTimeMillis())

    suspend fun deleteConversation(id: String)
}

class RoomChatRepository(private val dao: ChatDao) : ChatRepository {

    override suspend fun createConversation(
        title: String,
        sourceScope: List<String>,
        now: Long,
    ): ChatConversation {
        val entity = ChatConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { "新对话" },
            sourceScope = encodeScope(sourceScope),
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertConversation(entity)
        return entity.toDomain()
    }

    override suspend fun conversation(id: String): ChatConversation? = dao.conversation(id)?.toDomain()

    override suspend fun conversations(limit: Int): List<ChatConversation> =
        dao.recentConversations(limit).map { it.toDomain() }

    override suspend fun appendMessage(
        conversationId: String,
        role: ChatRole,
        content: String,
        model: String?,
        now: Long,
    ): ChatMessage {
        val entity = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = role.wire,
            content = content,
            createdAt = now,
            model = model,
        )
        dao.upsertMessage(entity)
        // 更新会话 updatedAt（SQL 精确更新；不重写 title/scope）
        dao.renameConversation(conversationId, dao.conversation(conversationId)?.title ?: "新对话", now)
        return entity.toDomain()
    }

    override suspend fun appendCitations(messageId: String, citations: List<ChatCitation>) {
        if (citations.isEmpty()) return
        dao.upsertCitations(
            citations.map {
                MessageCitationEntity(
                    messageId = messageId,
                    chunkId = it.chunkId,
                    rank = it.rank,
                )
            },
        )
    }

    override suspend fun messages(conversationId: String): List<ChatMessage> =
        dao.messages(conversationId).map { it.toDomain() }

    override suspend fun citations(messageId: String): List<ChatCitation> =
        dao.citations(messageId).map { ChatCitation(evidenceId = "S${it.rank + 1}", chunkId = it.chunkId, rank = it.rank) }

    override suspend fun renameConversation(id: String, title: String, now: Long) {
        dao.renameConversation(id, title.trim().ifBlank { "新对话" }, now)
    }

    override suspend fun deleteConversation(id: String) {
        dao.deleteConversation(id)
    }

    private fun ChatConversationEntity.toDomain() = ChatConversation(
        id = id,
        title = title,
        sourceScope = decodeScope(sourceScope),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun ChatMessageEntity.toDomain() = ChatMessage(
        id = id,
        conversationId = conversationId,
        role = ChatRole.fromWire(role),
        content = content,
        createdAt = createdAt,
        model = model,
    )

    companion object {
        internal fun encodeScope(ids: List<String>): String? =
            ids.filter { it.isNotBlank() }.distinct().take(200).joinToString(",").ifBlank { null }

        internal fun decodeScope(value: String?): List<String> =
            value.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
    }
}
