"""SAG 管理的四个 provider-neutral 向量索引合同。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from zleap.sag.exceptions import InvalidInputError

StorageMode = Literal["normal", "lite"]
LITE_SOURCE_CHUNK_VECTOR_DIMENSIONS = 1024
LITE_EVENT_ENTITY_VECTOR_DIMENSIONS = 128
LITE_RELATION_VECTOR_INDEXES = frozenset({"entity_vectors", "event_entity_vectors"})


@dataclass(frozen=True)
class IndexSchema:
    """一个"索引"(→ LanceDB 表)的字段分类。"""

    name: str
    vector_fields: tuple[str, ...] = ()
    text_fields: tuple[str, ...] = ()  # 全文检索(BM25)字段
    text_keyword_fields: tuple[str, ...] = ()  # 同时声明 ``.keyword`` 子字段
    stored_text_fields: tuple[str, ...] = ()  # 仅存储、不建立全文/精确索引的文本
    keyword_fields: tuple[str, ...] = ()  # 精确匹配字符串
    array_fields: tuple[str, ...] = ()  # list<string>
    object_fields: tuple[str, ...] = ()  # JSON/object；不参与全文检索
    bool_fields: tuple[str, ...] = ()
    datetime_fields: tuple[str, ...] = ()  # 统一存 ISO-8601 字符串
    int_fields: tuple[str, ...] = ()

    def all_fields(self) -> tuple[str, ...]:
        return (
            self.vector_fields
            + self.text_fields
            + self.text_keyword_fields
            + self.stored_text_fields
            + self.keyword_fields
            + self.array_fields
            + self.object_fields
            + self.bool_fields
            + self.datetime_fields
            + self.int_fields
        )


INDEX_SCHEMAS: dict[str, IndexSchema] = {
    "entity_vectors": IndexSchema(
        name="entity_vectors",
        vector_fields=("vector",),
        text_fields=("description",),
        text_keyword_fields=("name", "normalized_name"),
        keyword_fields=("entity_id", "source_config_id", "data_source_id", "type"),
        datetime_fields=("created_time",),
    ),
    "event_vectors_wide": IndexSchema(
        name="event_vectors_wide",
        vector_fields=("title_vector", "content_vector"),
        text_fields=("summary", "content"),
        text_keyword_fields=("title",),
        keyword_fields=(
            "event_id",
            "data_source_id",
            "source_type",
            "source_id",
            "generation_id",
            "article_id",
            "conversation_id",
            "chunk_id",
            "parent_id",
            "type",
            "priority",
            "status",
            "category",
        ),
        array_fields=("tags", "entity_ids", "entity_types"),
        object_fields=("reference", "extra_data", "keywords"),
        datetime_fields=("start_time", "end_time", "created_time"),
        int_fields=("level", "rank"),
    ),
    "event_entity_vectors": IndexSchema(
        name="event_entity_vectors",
        vector_fields=("vector",),
        text_keyword_fields=("description",),
        keyword_fields=(
            "event_id",
            "entity_id",
            "data_source_id",
            "source_config_id",
            "source_type",
            "source_id",
            "generation_id",
            "article_id",
            "conversation_id",
            "parent_id",
        ),
        datetime_fields=("created_time",),
        int_fields=("level",),
    ),
    "source_chunks": IndexSchema(
        name="source_chunks",
        vector_fields=("heading_vector", "content_vector"),
        text_fields=("content",),
        text_keyword_fields=("heading",),
        keyword_fields=(
            "chunk_id",
            "data_source_id",
            "source_id",
            "generation_id",
            "chunk_type",
        ),
        array_fields=("references",),
        datetime_fields=("created_time", "updated_time"),
        int_fields=("rank", "content_length"),
    ),
}

MODE_INDEX_NAMES: dict[str, tuple[str, ...]] = {
    "normal": (
        "entity_vectors",
        "event_vectors_wide",
        "event_entity_vectors",
        "source_chunks",
    ),
    # SaaS 也创建并写入 entity_vectors，只是不生成 entity.vector。
    "lite": (
        "entity_vectors",
        "event_vectors_wide",
        "event_entity_vectors",
        "source_chunks",
    ),
}


def cosine_distance_to_score(distance: float) -> float:
    """把余弦距离换算成与 Elasticsearch ``similarity="cosine"`` 一致的相似度分数。

    ES 的 kNN ``_score`` 是 ``(1 + cos) / 2``（值域 [0, 1]），而各向量后端原生返回的
    是余弦距离 ``1 - cos``（值域 [0, 2]）。检索侧的相似度阈值（如实体 0.9、事项 0.4）
    是按 ES 语义标定的，所有后端必须统一到同一尺度，否则同一阈值在不同后端上的
    严格程度不同，会静默改变召回。
    """
    clamped = max(0.0, min(2.0, float(distance)))
    return 1.0 - (clamped / 2.0)


def dimensions_for_index(
    storage_mode: StorageMode,
    index: str,
    embedding_dimensions: int | None,
) -> int | None:
    """返回可静态确定的物理向量维度；未知模型维度留待首个向量推断。"""
    if storage_mode == "lite" and index in LITE_RELATION_VECTOR_INDEXES:
        return LITE_EVENT_ENTITY_VECTOR_DIMENSIONS
    if storage_mode == "lite" and index == "source_chunks":
        # Lite 只保存 chunk 元数据，不写 chunk embedding；维持宿主固定的空向量列合同，
        # 避免 ES 在首次元数据写入时退化为动态 mapping。
        return LITE_SOURCE_CHUNK_VECTOR_DIMENSIONS
    return embedding_dimensions


def prepare_vector_for_index(
    storage_mode: StorageMode,
    index: str,
    vector: list[float],
) -> list[float]:
    """按索引合同整形向量；normal 保留完整 embedding，lite 关系向量截为 128 维。"""
    if storage_mode != "lite" or index not in LITE_RELATION_VECTOR_INDEXES:
        return vector
    if len(vector) < LITE_EVENT_ENTITY_VECTOR_DIMENSIONS:
        raise InvalidInputError(
            f"{index} embedding dims too short: got={len(vector)} "
            f"need>={LITE_EVENT_ENTITY_VECTOR_DIMENSIONS}"
        )
    return [float(value) for value in vector[:LITE_EVENT_ENTITY_VECTOR_DIMENSIONS]]


def declared_schema_document(index: str, dimensions: int) -> dict[str, object]:
    """生成 SQL 向量后端首次建整表所需的完整、带类型占位的文档。"""
    schema = INDEX_SCHEMAS[index]
    document: dict[str, object] = {}
    document.update({field: [0.0] * dimensions for field in schema.vector_fields})
    document.update(
        dict.fromkeys(
            schema.text_fields
            + schema.text_keyword_fields
            + schema.stored_text_fields
            + schema.keyword_fields
            + schema.datetime_fields,
            "",
        )
    )
    document.update({field: [] for field in schema.array_fields})
    document.update({field: {} for field in schema.object_fields})
    document.update(dict.fromkeys(schema.bool_fields, False))
    document.update(dict.fromkeys(schema.int_fields, 0))
    return document
