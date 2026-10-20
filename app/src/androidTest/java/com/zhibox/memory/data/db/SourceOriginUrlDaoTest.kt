package com.zhibox.memory.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * B14.2 Source DAO originUrl roundtrip（instrumentation，in-memory Room schema v8）：
 * - originUrl 默认 null 可写可读；写入 HTTPS URL 后按原值读回；
 * - upsert 更新 originUrl（含改回 null）不改其他列；不影响既有 source 行。
 */
class SourceOriginUrlDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun originUrlDefaultsToNullAndRoundTrips() = runBlocking {
        db.sourceDao().upsert(SourceEntity("src-1", "本地文件来源", "/files/a.txt", null, 100, "active"))
        assertNull("默认 originUrl 为 null", db.sourceDao().get("src-1")!!.originUrl)

        db.sourceDao().upsert(
            SourceEntity("src-2", "网页来源", "", null, 200, "active", originUrl = "https://example.com/page?id=2"),
        )
        assertEquals(
            "https://example.com/page?id=2",
            db.sourceDao().get("src-2")!!.originUrl,
        )
    }

    @Test
    fun upsertUpdatesOriginUrlWithoutTouchingOtherColumns() = runBlocking {
        val original = SourceEntity("src-1", "来源", "/files/b.txt", "text/plain", 300, "active")
        db.sourceDao().upsert(original)
        db.sourceDao().upsert(original.copy(originUrl = "https://example.com/b"))
        val withUrl = db.sourceDao().get("src-1")!!
        assertEquals("https://example.com/b", withUrl.originUrl)
        assertEquals(original.title, withUrl.title)
        assertEquals(original.uri, withUrl.uri)
        assertEquals(original.mimeType, withUrl.mimeType)
        assertEquals(original.createdAt, withUrl.createdAt)
        assertEquals(original.status, withUrl.status)

        // 改回 null（清除 URL provenance）也应生效
        db.sourceDao().upsert(withUrl.copy(originUrl = null))
        assertNull(db.sourceDao().get("src-1")!!.originUrl)
        assertEquals(original.title, db.sourceDao().get("src-1")!!.title)
    }
}
