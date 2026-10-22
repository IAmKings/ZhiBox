package com.zhibox.memory.core.model

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedModelStoreTest {

    private val old = Uri.parse("content://models/old")
    private val next = Uri.parse("content://models/new")

    @Test
    fun `新授权失败时保留旧目录`() {
        val released = mutableListOf<Uri>()

        val changed = SharedModelStore.replaceTreeUri(
            old = old,
            new = next,
            hasNewPermission = false,
            acquire = { false },
            persist = { error("不应写偏好") },
            release = { released += it },
        )

        assertFalse(changed)
        assertTrue(released.isEmpty())
    }

    @Test
    fun `新目录持久化后才释放旧授权`() {
        val calls = mutableListOf<String>()

        val changed = SharedModelStore.replaceTreeUri(
            old = old,
            new = next,
            hasNewPermission = false,
            acquire = { calls += "acquire"; true },
            persist = { calls += "persist"; true },
            release = { calls += if (it == old) "release-old" else "release-new" },
        )

        assertTrue(changed)
        assertEquals(listOf("acquire", "persist", "release-old"), calls)
    }

    @Test
    fun `持久化失败时仅回收新授权`() {
        val released = mutableListOf<Uri>()

        val changed = SharedModelStore.replaceTreeUri(
            old = old,
            new = next,
            hasNewPermission = false,
            acquire = { true },
            persist = { false },
            release = { released += it },
        )

        assertFalse(changed)
        assertEquals(listOf(next), released)
    }

    @Test
    fun `重复选择已有授权目录不释放授权`() {
        val calls = mutableListOf<String>()

        val changed = SharedModelStore.replaceTreeUri(
            old = old,
            new = old,
            hasNewPermission = true,
            acquire = { calls += "acquire"; true },
            persist = { calls += "persist"; true },
            release = { calls += "release" },
        )

        assertTrue(changed)
        assertTrue(calls.isEmpty())
    }
}
