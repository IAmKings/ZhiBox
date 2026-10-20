package com.zhibox.memory.core.text

import java.util.concurrent.ConcurrentHashMap

/**
 * P1.1：PDF 打开密码的**进程内临时保险箱**（user-password 重试链路）。
 *
 * 安全约定：
 * - **不持久化、不写日志、不进备份、不落盘**：只存在内存 map（进程死亡即消失）；
 * - `take` 是"取出并移除"（一次性消费）：调用方拿到后必须在 try/finally 中 `CharArray.fill('\u0000')` 清零；
 * - `remove`/`clearAll` 会清零自己持有的 char（防御性）；不把它做成 Bundle/Parcelable，避免进程外泄漏；
 * - key 为 documentId（非文件名/内容）；即使同文档重复重试也覆盖旧密码并在覆盖时清零旧 char。
 */
object PdfPasswordVault {

    private val passwords = ConcurrentHashMap<String, CharArray>()

    /** 暂存密码（覆盖时清零旧值）；[password] 所有权移交给 vault，调用方不应再复用。 */
    fun put(documentId: String, password: CharArray) {
        if (documentId.isBlank() || password.isEmpty()) return
        val previous = passwords.put(documentId, password)
        if (previous != null && previous !== password) previous.fill('\u0000')
    }

    /** 取出并移除密码（一次性消费）；无则 null。调用方负责清零返回值。 */
    fun take(documentId: String): CharArray? = passwords.remove(documentId)

    /** 移除并清零该文档密码。 */
    fun remove(documentId: String) {
        passwords.remove(documentId)?.fill('\u0000')
    }

    /** 清空所有暂存密码并清零。 */
    fun clearAll() {
        for (key in passwords.keys.toList()) {
            passwords.remove(key)?.fill('\u0000')
        }
    }
}
