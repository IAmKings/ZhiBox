package com.zhibox.memory.core.extract

/**
 * SAG 事件抽取的实体类型（B8 契约，**11 类封闭集合**）。
 *
 * 与参考实现（SAG）的"可配置实体类型"不同，本 App 收敛为固定 11 类：提示词、JSON 校验、
 * 数据库 `entity.entityType` 取值、FTS/展示标签共用同一来源。新增类型必须同时更新：
 * 1) 本枚举；2) [ExtractionPrompts] 的提示词；3) [ExtractionJson] 的校验（自动覆盖）；
 * 4) UI 展示标签（`label`）。`wire` 为持久化/LLM JSON 中的稳定字符串（小写英文），
 * 不随 Kotlin 枚举改名而变——数据库里的历史值一律按 `wire` 读取。
 */
enum class EntityType(val wire: String, val label: String) {
    /** 时间：日期、时刻、时段、周期 */
    TIME("time", "时间"),

    /** 地点：国家/城市/场所/方位 */
    LOCATION("location", "地点"),

    /** 人物：具体个人（含称谓） */
    PERSON("person", "人物"),

    /** 组织：公司、机构、学校、部门 */
    ORGANIZATION("organization", "组织"),

    /** 群体：人群、团队、家庭、民族等集合性主体 */
    GROUP("group", "群体"),

    /** 学科主体：学科、知识点、主题（K12 学习场景的核心类型） */
    SUBJECT("subject", "主题"),

    /** 作品：书籍、文章、论文、影视、课程等 */
    WORK("work", "作品"),

    /** 产品：商品、软件、硬件、工具 */
    PRODUCT("product", "产品"),

    /** 动作：行为、操作、过程、方法 */
    ACTION("action", "动作"),

    /** 指标：数量、比例、分数、成绩、度量 */
    METRIC("metric", "指标"),

    /** 标签：无法归入上述类别但有检索价值的关键词/主题标签 */
    TAGS("tags", "标签"),
    ;

    companion object {
        /** 11 类全量（提示词默认注入顺序） */
        val ALL: List<EntityType> = entries.toList()

        /** 全部 wire 名（测试/校验用） */
        val WIRE_NAMES: List<String> = entries.map { it.wire }

        private val byWire: Map<String, EntityType> = entries.associateBy { it.wire }

        /**
         * wire 名（大小写不敏感）→ 枚举；未知/空返回 null。
         *
         * 兼容 LLM 偶尔输出枚举常量名（如 "PERSON"）的情况：先按 wire 匹配，再按枚举名匹配。
         */
        fun fromWire(raw: String?): EntityType? {
            val value = raw?.trim()?.lowercase() ?: return null
            if (value.isEmpty()) return null
            return byWire[value] ?: entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
    }
}
