package com.zhibox.memory.core.extraction

import androidx.room.withTransaction
import com.zhibox.memory.data.db.AppDatabase

/**
 * B9：SAG 派生数据删除清理。
 *
 * event/entity 删除由 FK 级联覆盖 incidence，但 event_fts/entity_fts（FTS4 无 FK）、
 * sag_vector（多态 owner 无 FK）和跨文档共享 entity 不会自动清理；删除 source/document 后
 * 调用本对象，保证“删除后不再出现在检索/图谱”。
 */
object SagCleanup {

    /** @return 清理行数（FTS 孤儿 + entity 孤儿 + sag 孤儿） */
    suspend fun cleanupOrphans(db: AppDatabase): Int = db.withTransaction {
        var removed = 0
        removed += db.eventDao().deleteFtsOrphans()
        removed += db.entityDao().deleteFtsOrphans()
        removed += db.entityDao().deleteOrphans()
        removed += db.sagVectorDao().deleteOrphans()
        removed
    }
}
