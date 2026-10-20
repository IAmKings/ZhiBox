package com.zhibox.memory.core.extraction

import com.zhibox.memory.data.db.AppDatabase
import com.zhibox.memory.data.db.EntityFtsEntity
import com.zhibox.memory.data.db.EventFtsEntity

/**
 * B9：SAG 关键词索引（event_fts / entity_fts）全量重建。
 *
 * FTS 是 event/entity 行的**纯派生数据**（bigram 预分词、可随时从主表重建）：
 * - 备份 v3 导入成功后调用（导入包不携带 FTS，避免派生数据不一致）；
 * - 索引损坏/口径升级后由设置页诊断或测试调用。
 *
 * 口径：
 * - event：仅 `active = true` 的当前事件（被 supersede 的历史行不进索引，避免召回旧版本）；
 * - entity：全部实体（entity 跨文档复用，无 active 语义）；FTS 文本 = name+description+全部 aliases
 *   （B15.1：备份导入后重建也能恢复别名命中；无别名实体与 v8 完全一致）；
 * - 文本构造与 pipeline 写入侧共用 [SagExtractionSupport]（eventFtsText/entityFtsText），
 *   保证"重建结果 == 日常写入结果"。
 *
 * 幂等：先清空两张 FTS 再分批写入；中途失败后再次调用即可收敛（FTS 缺失不影响主表数据，
 * 由调用方把可读错误透出并允许重试）。
 */
object SagFtsRebuilder {

    private const val BATCH_SIZE = 300

    /** @return 重建的 FTS 行数（event + entity） */
    suspend fun rebuildAll(db: AppDatabase): Int {
        db.eventDao().clearFts()
        db.entityDao().clearFts()

        var rebuilt = 0

        val activeEvents = db.eventDao().all().filter { it.active }
        for (batch in activeEvents.chunked(BATCH_SIZE)) {
            db.eventDao().insertFtsAll(
                batch.map { event ->
                    EventFtsEntity(
                        eventId = event.id,
                        text = SagExtractionSupport.toFtsText(
                            SagExtractionSupport.eventFtsText(event.title, event.summary, event.content),
                        ),
                    )
                },
            )
            rebuilt += batch.size
        }

        val aliasesByEntity = db.entityAliasDao().all()
            .groupBy({ it.entityId }, { it.alias })
        val entities = db.entityDao().all()
        for (batch in entities.chunked(BATCH_SIZE)) {
            db.entityDao().insertFtsAll(
                batch.map { entity ->
                    EntityFtsEntity(
                        entityId = entity.id,
                        text = SagExtractionSupport.toFtsText(
                            SagExtractionSupport.entityFtsText(
                                name = entity.name.ifBlank { entity.normalizedName },
                                description = entity.description,
                                aliases = aliasesByEntity[entity.id].orEmpty(),
                            ),
                        ),
                    )
                },
            )
            rebuilt += batch.size
        }

        return rebuilt
    }
}
