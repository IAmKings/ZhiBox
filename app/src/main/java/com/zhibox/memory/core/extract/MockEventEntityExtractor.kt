package com.zhibox.memory.core.extract

import kotlinx.coroutines.delay

/**
 * B8 测试用抽取器（不依赖网络/LLM）。
 *
 * 默认返回确定性的合法 [ExtractedEvent]（与 [ExtractionPrompts] v1 schema 等价），
 * 也可注入任意 [ExtractionResult] 模拟失败/无效事件；记录调用次数与输入，便于 B9
 * worker/重试测试断言（如"每个 chunk 只抽取一次"）。
 *
 * 线程安全：仅测试使用；[calls]/[inputs] 在单协程串行场景下使用。
 */
class MockEventEntityExtractor(
    private val result: ExtractionResult = ExtractionResult.Success(DEFAULT_EVENT),
    private val delayMillis: Long = 0,
) : EventEntityExtractor {

    /** 便捷构造：固定返回一个成功事件 */
    constructor(event: ExtractedEvent) : this(ExtractionResult.Success(event))

    /** 便捷构造：固定返回一个失败 */
    constructor(failure: ExtractionResult.Failure) : this(failure as ExtractionResult)

    /** 已调用次数（测试断言幂等/重试使用） */
    var calls: Int = 0
        private set

    /** 已收到的输入（按调用顺序） */
    val inputs: MutableList<ExtractionInput> = mutableListOf()

    override suspend fun extract(input: ExtractionInput): ExtractionResult {
        calls++
        inputs += input
        if (delayMillis > 0) delay(delayMillis)
        return result
    }

    companion object {
        /** 默认成功事件：所有词均可从测试 chunk 复现，便于断言 */
        @JvmField
        val DEFAULT_EVENT: ExtractedEvent = ExtractedEvent(
            title = "测试事件",
            summary = "用于测试的抽取事件",
            content = "张三在上海学习数学。",
            language = "zh",
            entities = listOf(
                ExtractedEntity(EntityType.PERSON, "张三", "学习中的学生"),
                ExtractedEntity(EntityType.LOCATION, "上海", "学习地点"),
                ExtractedEntity(EntityType.SUBJECT, "数学", "学习主题"),
            ),
            isValid = true,
        )
    }
}
