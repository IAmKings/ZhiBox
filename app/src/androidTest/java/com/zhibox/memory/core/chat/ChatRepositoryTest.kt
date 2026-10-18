package com.zhibox.memory.core.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.ChatDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B11 Chat 持久化（instrumentation，真实 Room + ChatDao）：
 * - 会话 create/list/get、title trim/空标题兜底、sourceScope 编解码（只存来源 id）；
 * - 用户/助手消息追加、createdAt 排序、append 更新会话 updatedAt、title 不变；
 * - 引用只存 chunkId/rank，按 rank 读取并映射回 evidenceId `S{n}`；
 * - 删除会话 FK CASCADE 清理消息/引用（其他会话不受影响）；
 * - 确定性时间戳（全部由调用方传入）、rename 空标题兜底；
 * - “no正文 beyond messages”：会话只存 scope ids、引用只存 chunkId/rank，正文仅在 message.content。
 *
 * 需真机/模拟器执行（本轮只做 `:app:compileDebugAndroidTestKotlin` 编译验证，设备任务由 Lead 另行安排）。
 */
class ChatRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        dao = db.chatDao()
        repo = RoomChatRepository(dao)
        runBlocking { db.clearAllTables() }
    }

    @After
    fun tearDown() {
        runBlocking { db.clearAllTables() }
    }

    // ---- 会话 create/list/get ----

    @Test
    fun createGetAndListConversations() = runBlocking {
        val c1 = repo.createConversation(title = " 问答一 ", sourceScope = listOf("src-1", "src-2"), now = 1_000L)
        assertEquals("标题应 trim", "问答一", c1.title)
        assertTrue("id 应为非空 UUID", c1.id.isNotBlank())
        assertEquals(listOf("src-1", "src-2"), c1.sourceScope)
        assertEquals(1_000L, c1.createdAt)
        assertEquals(1_000L, c1.updatedAt)

        val c2 = repo.createConversation(title = "   ", sourceScope = emptyList(), now = 2_000L)
        assertEquals("空标题兜底", "新对话", c2.title)
        assertTrue(c2.sourceScope.isEmpty())
        assertNotEquals("不同会话 id 必须不同", c1.id, c2.id)

        assertEquals(c1, repo.conversation(c1.id))
        assertEquals(c2, repo.conversation(c2.id))
        assertNull(repo.conversation("missing-id"))

        assertEquals(
            "按 updatedAt DESC 排序（并发安全：c2 更新）",
            listOf(c2.id, c1.id),
            repo.conversations(limit = 10).map { it.id },
        )
        assertEquals(listOf(c2.id), repo.conversations(limit = 1).map { it.id })
    }

    @Test
    fun sourceScopeIsStoredAsIdsOnlyAndNormalized() = runBlocking {
        // 只存正数来源 id、去重、空=全部来源
        assertNull(RoomChatRepository.encodeScope(emptyList()))
        assertNull(RoomChatRepository.encodeScope(listOf("", " ")))
        assertEquals("s3,s4", RoomChatRepository.encodeScope(listOf("s3", "s3", "s4")))
        assertEquals(emptyList<String>(), RoomChatRepository.decodeScope(null))
        assertEquals(emptyList<String>(), RoomChatRepository.decodeScope(""))
        assertEquals(listOf("1", "x", "3"), RoomChatRepository.decodeScope("1, x, 3"))
        assertEquals(listOf("-1", "2"), RoomChatRepository.decodeScope("-1,2"))
        assertEquals("最多保留 200 个来源", 200, RoomChatRepository.encodeScope((1..205).map { "s$it" })!!.split(',').size)

        val c = repo.createConversation(
            title = "范围",
            sourceScope = listOf("", " ", "s5", "s5", "s6"),
            now = 10L,
        )
        assertEquals(listOf("s5", "s6"), c.sourceScope)
        val raw = dao.conversation(c.id)!!
        assertEquals("DB 原始列只存来源 id", "s5,s6", raw.sourceScope)
        assertFalse("sourceScope 不得包含正文/路径", raw.sourceScope!!.contains("content://"))
    }

    // ---- 消息追加 ----

    @Test
    fun appendUserAndAssistantMessagesWithDeterministicTimestamps() = runBlocking {
        val conversation = repo.createConversation(title = "学习问答", now = 100L)

        val user = repo.appendMessage(
            conversationId = conversation.id,
            role = ChatRole.USER,
            content = "什么是光合作用？",
            model = null,
            now = 200L,
        )
        val assistant = repo.appendMessage(
            conversationId = conversation.id,
            role = ChatRole.ASSISTANT,
            content = "光合作用是……[S1]",
            model = "gpt-4o-mini",
            now = 300L,
        )

        assertEquals(ChatRole.USER, user.role)
        assertEquals(200L, user.createdAt)
        assertNull(user.model)
        assertEquals(ChatRole.ASSISTANT, assistant.role)
        assertEquals(300L, assistant.createdAt)
        assertEquals("gpt-4o-mini", assistant.model)

        val messages = repo.messages(conversation.id)
        assertEquals(listOf(user.id, assistant.id), messages.map { it.id })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), messages.map { it.role })
        assertEquals("什么是光合作用？", messages.first().content)
        assertEquals("光合作用是……[S1]", messages.last().content)

        val updated = repo.conversation(conversation.id)!!
        assertEquals("append 应更新会话 updatedAt", 300L, updated.updatedAt)
        assertEquals("append 不得改标题", "学习问答", updated.title)
        assertEquals("append 不得改 createdAt", 100L, updated.createdAt)
        assertTrue(repo.messages("missing").isEmpty())
    }

    // ---- 引用 ----

    @Test
    fun citationsKeepRankAndChunkIdAndMapBackToEvidenceIds() = runBlocking {
        val conversation = repo.createConversation(title = "引用", now = 1L)
        val message = repo.appendMessage(
            conversationId = conversation.id,
            role = ChatRole.ASSISTANT,
            content = "答案[S1][S2]",
            model = "gpt-4o-mini",
            now = 2L,
        )

        // 乱序输入，读取时按 rank 排序
        repo.appendCitations(
            message.id,
            listOf(
                ChatCitation(evidenceId = "S2", chunkId = 102L, rank = 1),
                ChatCitation(evidenceId = "S1", chunkId = 101L, rank = 0),
            ),
        )
        val citations = repo.citations(message.id)
        assertEquals(listOf(0, 1), citations.map { it.rank })
        assertEquals(listOf(101L, 102L), citations.map { it.chunkId })
        assertEquals("evidenceId 由 rank 映射", listOf("S1", "S2"), citations.map { it.evidenceId })

        // 空引用列表不写行
        repo.appendCitations(message.id, emptyList())
        assertEquals(2, repo.citations(message.id).size)
        assertTrue(repo.citations("missing-message").isEmpty())

        // DB 行只含 messageId/chunkId/rank（无正文列）
        val raw = dao.citations(message.id)
        assertEquals(2, raw.size)
        assertEquals(listOf(101L, 102L), raw.map { it.chunkId })
        assertEquals(listOf(0, 1), raw.map { it.rank })
        assertTrue(raw.all { it.messageId == message.id })
    }

    // ---- 删除级联 ----

    @Test
    fun deleteConversationCascadesMessagesAndCitations() = runBlocking {
        val conversation = repo.createConversation(title = "待删", now = 1L)
        val message = repo.appendMessage(conversation.id, ChatRole.ASSISTANT, "回答[S1]", "m", now = 2L)
        repo.appendCitations(message.id, listOf(ChatCitation("S1", 7L, 0)))
        val other = repo.createConversation(title = "保留", now = 3L)
        repo.appendMessage(other.id, ChatRole.USER, "另一个问题", null, now = 4L)

        repo.deleteConversation(conversation.id)

        assertNull("会话应删除", repo.conversation(conversation.id))
        assertTrue("消息应级联删除", repo.messages(conversation.id).isEmpty())
        assertTrue("引用应级联删除", repo.citations(message.id).isEmpty())
        assertNotNull("其他会话不受影响", repo.conversation(other.id))
        assertEquals(1, repo.messages(other.id).size)
        assertEquals(listOf(other.id), repo.conversations(limit = 10).map { it.id })
    }

    // ---- rename / 时间戳 ----

    @Test
    fun renameTrimsTitleAndUpdatesUpdatedAtDeterministically() = runBlocking {
        val conversation = repo.createConversation(title = "旧标题", now = 7L)
        repo.renameConversation(conversation.id, "  新标题  ", now = 11L)

        val renamed = repo.conversation(conversation.id)!!
        assertEquals("新标题", renamed.title)
        assertEquals(11L, renamed.updatedAt)
        assertEquals("createdAt 不变", 7L, renamed.createdAt)

        repo.renameConversation(conversation.id, "   ", now = 12L)
        assertEquals("新对话", repo.conversation(conversation.id)!!.title)
        assertEquals(12L, repo.conversation(conversation.id)!!.updatedAt)
    }

    @Test
    fun contentBodyOnlyStoredInMessagesNotInConversationOrCitations() = runBlocking {
        val conversation = repo.createConversation(title = "隐私", sourceScope = listOf("1"), now = 1L)
        val message = repo.appendMessage(conversation.id, ChatRole.USER, "用户问题正文-in-message", null, now = 2L)
        repo.appendCitations(message.id, listOf(ChatCitation("S1", 9L, 0)))

        val rawConversation = dao.conversation(conversation.id)!!
        assertFalse("会话行不得含消息正文", rawConversation.title.contains("正文"))
        assertEquals("1", rawConversation.sourceScope)
        val rawCitations = dao.citations(message.id)
        assertEquals(1, rawCitations.size)
        assertEquals(9L, rawCitations.single().chunkId)
        assertEquals(0, rawCitations.single().rank)
        assertEquals(
            "正文只存在 messages.content",
            "用户问题正文-in-message",
            repo.messages(conversation.id).single().content,
        )
    }

    // ---- 空对话/边界 ----

    @Test
    fun emptyConversationHasNoMessagesOrCitationsButListed() = runBlocking {
        val conversation = repo.createConversation(title = "空对话", now = 5L)
        assertTrue(repo.messages(conversation.id).isEmpty())
        assertEquals(listOf(conversation.id), repo.conversations(limit = 10).map { it.id })
        assertEquals(5L, repo.conversation(conversation.id)!!.updatedAt)
    }

    // ---- 文档/来源无关性（Chat 不依赖知识库） ----

    @Test
    fun chatWorksWithoutAnyKnowledgeBaseRows() = runBlocking {
        // 不 seed source/document/chunk：会话/消息/引用表无 FK 依赖知识库
        assertEquals(0, db.sourceDao().all().size)
        assertEquals(0, db.documentDao().all().size)

        val conversation = repo.createConversation(title = "无知识库", now = 1L)
        val message = repo.appendMessage(conversation.id, ChatRole.ASSISTANT, "回答", "m", now = 2L)
        repo.appendCitations(message.id, listOf(ChatCitation("S1", 123L, 0)))

        assertEquals(1, repo.messages(conversation.id).size)
        assertEquals(listOf(123L), repo.citations(message.id).map { it.chunkId })
    }
}
