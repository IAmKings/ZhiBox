"""Serializable contracts shared by the five SAG pipeline stages."""

from __future__ import annotations

from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Any, Literal

from pydantic import (
    AliasChoices,
    ConfigDict,
    Field,
    JsonValue,
    field_validator,
    model_serializer,
    model_validator,
)

from zleap.sag.exceptions import ValidationError as SagValidationError
from zleap.sag.validation import SagBaseModel as BaseModel

CONTRACT_VERSION: Literal["1.0"] = "1.0"
SEARCH_CONTRACT_VERSION: Literal["2.0"] = "2.0"
_MAX_STORAGE_ID_CHARACTERS = 36
_MAX_EXTERNAL_ID_CHARACTERS = 191
_MAX_SOURCE_TITLE_CHARACTERS = 500
_MAX_CREATOR_ID_CHARACTERS = 100
_MAX_RECORD_GROUP_ID_CHARACTERS = 191
_MAX_CELL_REFERENCE_CHARACTERS = 64


class BaseContractModel(BaseModel):
    """Shared immutable, JSON-serializable contract behavior."""

    model_config = ConfigDict(frozen=True, extra="forbid", use_enum_values=True)


class ContractModel(BaseContractModel):
    """Version-one public contract."""

    contract_version: Literal["1.0"] = CONTRACT_VERSION


class SearchContractModel(BaseContractModel):
    """Versioned base for the breaking Search contract introduced in phase five."""

    contract_version: Literal["2.0"] = SEARCH_CONTRACT_VERSION


class SourceType(StrEnum):
    TEXT = "text"
    ARTICLE = "article"
    CHAT = "chat"
    RECORDING = "recording"


class SourceDescriptor(ContractModel):
    data_source_id: str | None = None
    source_type: SourceType
    source_id: str | None = None
    external_id: str | None = None
    title: str | None = None
    creator_id: str | None = None
    created_at: datetime | None = None
    metadata: dict[str, JsonValue] = Field(default_factory=dict)

    @field_validator("data_source_id", "source_id")
    @classmethod
    def _bound_storage_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        value = value.strip()
        if not value:
            return None
        if len(value) > _MAX_STORAGE_ID_CHARACTERS:
            raise SagValidationError(f"存储 ID 不能超过 {_MAX_STORAGE_ID_CHARACTERS} 个字符")
        return value

    @field_validator("external_id")
    @classmethod
    def _bound_external_id(cls, value: str | None) -> str | None:
        return cls._bound_optional_text(value, "external_id", _MAX_EXTERNAL_ID_CHARACTERS)

    @field_validator("title")
    @classmethod
    def _bound_title(cls, value: str | None) -> str | None:
        return cls._bound_optional_text(value, "title", _MAX_SOURCE_TITLE_CHARACTERS)

    @field_validator("creator_id")
    @classmethod
    def _bound_creator_id(cls, value: str | None) -> str | None:
        return cls._bound_optional_text(value, "creator_id", _MAX_CREATOR_ID_CHARACTERS)

    @staticmethod
    def _bound_optional_text(value: str | None, label: str, maximum: int) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            return None
        if len(normalized) > maximum:
            raise SagValidationError(f"{label} 不能超过 {maximum} 个字符")
        return normalized

    @field_validator("created_at")
    @classmethod
    def _normalize_created_at(cls, value: datetime | None) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None or value.utcoffset() is None:
            raise SagValidationError("created_at 必须包含时区")
        return value.astimezone(UTC)


class ParsedUnit(ContractModel):
    unit_key: str
    content: str
    raw_content: str | None = None
    external_id: str | None = None
    speaker: str | None = None
    role: str | None = None
    timestamp: datetime | None = None
    start_ms: int | None = Field(default=None, ge=0)
    end_ms: int | None = Field(default=None, ge=0)
    metadata: dict[str, JsonValue] = Field(default_factory=dict)

    @field_validator("timestamp")
    @classmethod
    def _normalize_timestamp(cls, value: datetime | None) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None or value.utcoffset() is None:
            raise SagValidationError("timestamp 必须包含时区")
        return value.astimezone(UTC)

    @model_validator(mode="after")
    def _validate_media_range(self) -> ParsedUnit:
        if self.start_ms is not None and self.end_ms is not None and self.start_ms > self.end_ms:
            raise SagValidationError("start_ms 不能晚于 end_ms")
        return self


class TableCellRange(ContractModel):
    """One-based inclusive coordinates inside one parsed table."""

    row_start: int = Field(ge=1)
    row_end: int = Field(ge=1)
    column_start: int = Field(ge=1)
    column_end: int = Field(ge=1)

    @model_validator(mode="after")
    def _validate_ordered_bounds(self) -> TableCellRange:
        if self.row_start > self.row_end:
            raise SagValidationError("table row range start cannot exceed end")
        if self.column_start > self.column_end:
            raise SagValidationError("table column range start cannot exceed end")
        return self

    def overlaps(self, other: TableCellRange) -> bool:
        return not (
            self.row_end < other.row_start
            or other.row_end < self.row_start
            or self.column_end < other.column_start
            or other.column_end < self.column_start
        )


class TableRecordGroup(ContractModel):
    """SAG-derived logical record boundary within one physical table."""

    record_group_id: str
    table_index: int = Field(ge=0)
    cell_range: TableCellRange
    identity_cells: tuple[str, ...] = ()
    metadata: dict[str, JsonValue] = Field(default_factory=dict)

    @field_validator("record_group_id")
    @classmethod
    def _normalize_record_group_id(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise SagValidationError("record_group_id cannot be blank")
        if len(normalized) > _MAX_RECORD_GROUP_ID_CHARACTERS:
            raise SagValidationError(
                f"record_group_id cannot exceed {_MAX_RECORD_GROUP_ID_CHARACTERS} characters"
            )
        return normalized

    @field_validator("identity_cells")
    @classmethod
    def _normalize_identity_cells(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        normalized: list[str] = []
        for value in values:
            cell = value.strip().upper()
            if not cell or len(cell) > _MAX_CELL_REFERENCE_CHARACTERS:
                raise SagValidationError("identity cell reference is invalid")
            if cell not in normalized:
                normalized.append(cell)
        return tuple(normalized)


class TableStructure(ContractModel):
    """Versioned, format-neutral logical record groups for parsed tables."""

    version: Literal["1.0"] = "1.0"
    markdown_sha256: str = Field(min_length=64, max_length=64, pattern=r"^[0-9a-f]{64}$")
    groups: tuple[TableRecordGroup, ...] = ()
    suppressed_table_indices: tuple[int, ...] = ()

    @field_validator("suppressed_table_indices")
    @classmethod
    def _normalize_suppressed_tables(cls, values: tuple[int, ...]) -> tuple[int, ...]:
        if any(isinstance(value, bool) or value < 0 for value in values):
            raise SagValidationError("suppressed table indices must be non-negative integers")
        return tuple(sorted(set(values)))

    @model_validator(mode="after")
    def _validate_groups(self) -> TableStructure:
        seen_ids: set[str] = set()
        by_table: dict[int, list[TableRecordGroup]] = {}
        for group in self.groups:
            if group.table_index in self.suppressed_table_indices:
                raise SagValidationError("a table cannot contain record groups and be suppressed")
            if group.record_group_id in seen_ids:
                raise SagValidationError("record_group_id values must be unique")
            seen_ids.add(group.record_group_id)
            for previous in by_table.setdefault(group.table_index, []):
                if group.cell_range.overlaps(previous.cell_range):
                    raise SagValidationError("table record groups cannot overlap")
            by_table[group.table_index].append(group)
        return self


class TextSource(ContractModel):
    kind: Literal["text"] = "text"
    text: str
    descriptor: SourceDescriptor | None = None
    format_hint: str | None = None


class FileSource(ContractModel):
    kind: Literal["file"] = "file"
    path: str
    descriptor: SourceDescriptor | None = None
    format_hint: str | None = None

    @field_validator("path")
    @classmethod
    def _validate_path(cls, value: str) -> str:
        if not value.strip():
            raise SagValidationError("path 不能为空")
        return str(Path(value))


class DocumentSource(ContractModel):
    """Canonical document content with an optional immutable original file."""

    kind: Literal["document"] = "document"
    content: str
    original_file: FileSource | None = None
    original_sha256: str | None = Field(
        default=None,
        min_length=64,
        max_length=64,
        pattern=r"^[0-9a-f]{64}$",
    )
    descriptor: SourceDescriptor | None = None
    format_hint: str = "markdown"

    @model_validator(mode="after")
    def _validate_original_pair(self) -> DocumentSource:
        if (self.original_file is None) != (self.original_sha256 is None):
            raise SagValidationError("original_file and original_sha256 must be supplied together")
        return self


class ArticleSource(ContractModel):
    kind: Literal["article"] = "article"
    content: str | None = None
    locator: str | None = None
    descriptor: SourceDescriptor | None = None
    format_hint: str | None = None

    @model_validator(mode="after")
    def _require_content_or_locator(self) -> ArticleSource:
        if self.content is None and not self.locator:
            raise SagValidationError("ArticleSource 必须提供 content 或 locator")
        return self


class ConversationSource(ContractModel):
    kind: Literal["conversation"] = "conversation"
    units: tuple[ParsedUnit, ...] = ()
    locator: str | None = None
    descriptor: SourceDescriptor | None = None

    @model_validator(mode="after")
    def _require_units_or_locator(self) -> ConversationSource:
        if not self.units and not self.locator:
            raise SagValidationError("ConversationSource 必须提供 units 或 locator")
        return self


class RecordingSource(ContractModel):
    kind: Literal["recording"] = "recording"
    units: tuple[ParsedUnit, ...] = ()
    locator: str | None = None
    descriptor: SourceDescriptor | None = None

    @model_validator(mode="after")
    def _require_units_or_locator(self) -> RecordingSource:
        if not self.units and not self.locator:
            raise SagValidationError("RecordingSource 必须提供 units 或 locator")
        return self


class ExternalSource(ContractModel):
    kind: Literal["external"] = "external"
    source_type: SourceType
    locator: str
    descriptor: SourceDescriptor | None = None
    reader: str | None = None


SourceInput = Annotated[
    TextSource
    | FileSource
    | DocumentSource
    | ArticleSource
    | ConversationSource
    | RecordingSource
    | ExternalSource,
    Field(discriminator="kind"),
]


class ParsedBlock(ContractModel):
    block_key: str
    block_type: str = "text"
    heading: str = ""
    content: str
    raw_content: str | None = None
    start_index: int = Field(default=0, ge=0)
    end_index: int = Field(default=0, ge=0)
    metadata: dict[str, JsonValue] = Field(default_factory=dict)


class ParsedDocument(ContractModel):
    kind: Literal["document"] = "document"
    descriptor: SourceDescriptor
    title: str | None = None
    body: str
    format: str
    blocks: tuple[ParsedBlock, ...] = ()
    warnings: tuple[str, ...] = ()
    metadata: dict[str, JsonValue] = Field(default_factory=dict)
    table_structure: TableStructure | None = None


class ParsedConversation(ContractModel):
    kind: Literal["conversation"] = "conversation"
    descriptor: SourceDescriptor
    units: tuple[ParsedUnit, ...]
    warnings: tuple[str, ...] = ()
    metadata: dict[str, JsonValue] = Field(default_factory=dict)


class ParsedRecording(ContractModel):
    kind: Literal["recording"] = "recording"
    descriptor: SourceDescriptor
    units: tuple[ParsedUnit, ...]
    warnings: tuple[str, ...] = ()
    metadata: dict[str, JsonValue] = Field(default_factory=dict)


ParsedSource = Annotated[
    ParsedDocument | ParsedConversation | ParsedRecording,
    Field(discriminator="kind"),
]


class SourceReference(ContractModel):
    reference_type: Literal["block", "unit", "time_range"]
    key: str
    order: int = Field(ge=0)
    start_ms: int | None = Field(default=None, ge=0)
    end_ms: int | None = Field(default=None, ge=0)
    metadata: dict[str, JsonValue] = Field(default_factory=dict)


class SectionItem(ContractModel):
    """Chunk 内部的一个最小可引用 Section。

    语义上等同于 chunking 层的 SectionDraft，但属于 pipeline 契约层，
    避免 chunking 内部类型泄露到 pipeline contracts。
    当 ChunkItem.sections 为空时，_insert_chunks 回退到 1 Chunk = 1 ArticleSection。
    """

    order_index: int = Field(ge=0)
    render_group_index: int = Field(ge=0)
    heading: str = ""
    content: str
    raw_content: str | None = None
    section_type: str = "TEXT"
    metadata: dict[str, JsonValue] = Field(default_factory=dict)


class ChunkItem(ContractModel):
    client_key: str
    rank: int = Field(ge=0)
    heading: str = ""
    content: str
    raw_content: str | None = None
    chunk_type: str = "TEXT"
    references: tuple[SourceReference, ...] = ()
    sections: tuple[SectionItem, ...] = ()
    token_count: int | None = Field(default=None, ge=0)
    metadata: dict[str, JsonValue] = Field(default_factory=dict)


class ChunkStats(ContractModel):
    input_units: int = Field(default=0, ge=0)
    chunk_count: int = Field(default=0, ge=0)
    token_count: int = Field(default=0, ge=0)
    truncated_units: int = Field(default=0, ge=0)


class ChunkSet(ContractModel):
    descriptor: SourceDescriptor
    source_version: str
    chunk_version: str
    chunks: tuple[ChunkItem, ...]
    units: tuple[ParsedUnit, ...] = ()
    stats: ChunkStats
    metadata: dict[str, JsonValue] = Field(default_factory=dict)

    @property
    def chunk_count(self) -> int:
        return len(self.chunks)


class WriteStatus(StrEnum):
    SUCCEEDED = "succeeded"
    PARTIAL = "partial"
    FAILED = "failed"
    SKIPPED_BY_POLICY = "skipped_by_policy"


class ItemFailure(ContractModel):
    item_id: str
    code: str
    message: str
    retryable: bool = False
    attempts: int = Field(default=1, ge=1)


class ChunkSetRef(ContractModel):
    data_source_id: str
    source_type: SourceType
    source_id: str
    source_version: str
    chunk_version: str
    generation_id: str | None = None
    chunk_ids: tuple[str, ...]
    client_key_to_chunk_id: dict[str, str]
    relation_status: WriteStatus
    vector_status: WriteStatus
    failed_items: tuple[ItemFailure, ...] = ()
    stats: dict[str, JsonValue] = Field(default_factory=dict)

    @property
    def chunk_count(self) -> int:
        """Return the number of persisted chunks."""
        return len(self.chunk_ids)


class PersistedChunkSelector(ContractModel):
    data_source_id: str
    source_ids: tuple[str, ...] = ()
    chunk_ids: tuple[str, ...] = ()
    expected_chunk_version: str | None = None

    @model_validator(mode="after")
    def _require_target(self) -> PersistedChunkSelector:
        if not self.source_ids and not self.chunk_ids:
            raise SagValidationError("PersistedChunkSelector 必须提供 source_ids 或 chunk_ids")
        return self


class EventSetRef(ContractModel):
    data_source_id: str
    source_type: SourceType
    source_id: str
    source_version: str
    chunk_version: str
    extract_version: str
    generation_id: str | None = None
    chunk_ids: tuple[str, ...]
    event_ids: tuple[str, ...]
    entity_ids: tuple[str, ...] = ()
    parent_event_ids: tuple[str, ...] = ()
    relation_status: WriteStatus
    vector_status: WriteStatus
    stats: dict[str, JsonValue] = Field(default_factory=dict)

    @property
    def event_count(self) -> int:
        """Return the number of persisted events."""
        return len(self.event_ids)

    @property
    def entity_count(self) -> int:
        """Return the persisted entity count without adding it to the portable payload."""
        return len(self.entity_ids)


class EventEntityDetail(ContractModel):
    """Portable entity information attached to a persisted event."""

    id: str
    type: str
    name: str
    description: str = ""
    weight: float = 1.0
    metadata: dict[str, JsonValue] = Field(default_factory=dict)


class EventDetail(ContractModel):
    """Portable persisted event returned by the explicit reload API."""

    id: str
    data_source_id: str
    source_type: SourceType
    source_id: str
    chunk_id: str | None = None
    parent_id: str | None = None
    level: int = Field(default=0, ge=0)
    rank: int = Field(default=0, ge=0)
    title: str
    summary: str
    content: str
    category: str | None = None
    event_type: str | None = None
    keywords: JsonValue | None = None
    priority: str | None = None
    status: str | None = None
    start_time: datetime | None = None
    end_time: datetime | None = None
    references: JsonValue | None = None
    entities: tuple[EventEntityDetail, ...] = ()
    created_time: datetime
    updated_time: datetime


_MAX_SEARCH_QUERY_CHARACTERS = 4_000
_MAX_SEARCH_DATA_SOURCES = 100
_MAX_SEARCH_SOURCES = 1_000
_MAX_SEARCH_CREATORS = 1_000
_MAX_DATA_SOURCE_ID_CHARACTERS = 36
_MAX_SOURCE_ID_CHARACTERS = 100
_MAX_CREATOR_ID_CHARACTERS = 100


class SearchScope(SearchContractModel):
    data_source_ids: tuple[str, ...]
    source_ids: tuple[str, ...] = ()
    creator_ids: tuple[str, ...] = ()
    source_types: tuple[SourceType, ...] = ()
    start_time: datetime | None = None
    end_time: datetime | None = None

    @field_validator(
        "data_source_ids",
        "source_ids",
        "creator_ids",
        "source_types",
        mode="before",
    )
    @classmethod
    def _normalize_ids(cls, value: object) -> tuple[str, ...]:
        if value is None:
            return ()
        if isinstance(value, str):
            value = (value,)
        if not isinstance(value, list | tuple | set | frozenset):
            raise SagValidationError("搜索范围必须是字符串集合")
        return tuple(dict.fromkeys(str(item).strip() for item in value if str(item).strip()))

    @field_validator("data_source_ids")
    @classmethod
    def _bound_data_sources(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if len(value) > _MAX_SEARCH_DATA_SOURCES:
            raise SagValidationError(f"data_source_ids 不能超过 {_MAX_SEARCH_DATA_SOURCES} 项")
        if any(len(item) > _MAX_DATA_SOURCE_ID_CHARACTERS for item in value):
            raise SagValidationError(
                f"data_source_id 不能超过 {_MAX_DATA_SOURCE_ID_CHARACTERS} 个字符"
            )
        return value

    @field_validator("source_ids")
    @classmethod
    def _bound_sources(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if len(value) > _MAX_SEARCH_SOURCES:
            raise SagValidationError(f"source_ids 不能超过 {_MAX_SEARCH_SOURCES} 项")
        if any(len(item) > _MAX_SOURCE_ID_CHARACTERS for item in value):
            raise SagValidationError(f"source_id 不能超过 {_MAX_SOURCE_ID_CHARACTERS} 个字符")
        return value

    @field_validator("creator_ids")
    @classmethod
    def _bound_creators(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if len(value) > _MAX_SEARCH_CREATORS:
            raise SagValidationError(f"creator_ids 不能超过 {_MAX_SEARCH_CREATORS} 项")
        if any(len(item) > _MAX_CREATOR_ID_CHARACTERS for item in value):
            raise SagValidationError(f"creator_id 不能超过 {_MAX_CREATOR_ID_CHARACTERS} 个字符")
        return value

    @field_validator("start_time", "end_time")
    @classmethod
    def _normalize_range_time(cls, value: datetime | None) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None or value.utcoffset() is None:
            raise SagValidationError("搜索时间范围必须包含时区")
        return value.astimezone(UTC)

    @model_validator(mode="after")
    def _validate_scope(self) -> SearchScope:
        if not self.data_source_ids:
            raise SagValidationError("data_source_ids 不能为空")
        if self.start_time and self.end_time and self.start_time > self.end_time:
            raise SagValidationError("start_time 不能晚于 end_time")
        return self


class SearchChunkRef(SearchContractModel):
    chunk_id: str
    heading: str = ""
    content: str = ""


class SearchEventRef(SearchContractModel):
    event_id: str
    title: str = ""
    summary: str = ""
    content: str = ""


class SearchHit(SearchContractModel):
    id: str
    source_id: str | None = None
    data_source_id: str | None = None
    score: float | None = None
    title: str | None = None
    content: str = ""
    timestamp: datetime | None = None
    metadata: dict[str, JsonValue] = Field(default_factory=dict)
    chunk_id: str | None = None
    chunk: tuple[SearchChunkRef, ...] = ()
    event: tuple[SearchEventRef, ...] = ()


class GraphNode(SearchContractModel):
    """Portable query, entity, event, or chunk node used by the search route graph."""

    id: str
    type: Literal["query", "entity", "event", "chunk"]
    category: str = ""
    event_id: str | None = None
    chunk_id: str | None = None
    name: str | None = None
    title: str | None = None
    summary: str | None = None
    content: str = ""
    description: str = ""
    stage: str | None = None
    hop: int = Field(default=0, ge=0)
    metadata: dict[str, JsonValue] = Field(default_factory=dict)


class GraphClue(SearchContractModel):
    """One deterministic directed edge in a search route graph."""

    model_config = ConfigDict(
        frozen=True,
        extra="forbid",
        populate_by_name=True,
        use_enum_values=True,
    )

    id: str
    stage: str
    method: str
    from_id: str = Field(
        validation_alias=AliasChoices("from_id", "from"),
        serialization_alias="from",
    )
    to_id: str = Field(
        validation_alias=AliasChoices("to_id", "to"),
        serialization_alias="to",
    )
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)
    hop: int = Field(default=0, ge=0)
    visibility: Literal["debug", "intermediate", "final"] = "intermediate"
    relation: str = ""
    description: str = ""
    metadata: dict[str, JsonValue] = Field(default_factory=dict)

    @model_serializer(mode="wrap")
    def _serialize_route_aliases(self, handler: Any) -> dict[str, Any]:
        data = handler(self)
        data["from"] = data.pop("from_id")
        data["to"] = data.pop("to_id")
        return data


class SearchGraph(SearchContractModel):
    """Answer-reachable graph returned by SAG search executors."""

    version: Literal["1.0"] = "1.0"
    nodes: tuple[GraphNode, ...] = ()
    clues: tuple[GraphClue, ...] = ()


class PathAnalysisResult(SearchContractModel):
    """Bounded, deterministic route paths grouped by answer target."""

    shortest_paths: dict[str, tuple[GraphClue, ...]] = Field(default_factory=dict)
    longest_paths: dict[str, tuple[GraphClue, ...]] = Field(default_factory=dict)
    candidate_paths: dict[str, tuple[tuple[GraphClue, ...], ...]] = Field(default_factory=dict)
    nodes_by_depth: dict[str, tuple[GraphNode, ...]] = Field(default_factory=dict)
    stats: dict[str, JsonValue] = Field(default_factory=dict)


class SearchResult(SearchContractModel):
    query: str
    original_query: str
    rewritten_query: str | None = None
    events: tuple[SearchHit, ...] = ()
    chunks: tuple[SearchHit, ...] = ()
    graph: SearchGraph | JsonValue | None = None
    paths: PathAnalysisResult | None = None
    stats: dict[str, JsonValue] = Field(default_factory=dict)


# SearchOptions is imported lazily through a forward reference to keep contracts/options acyclic.
class SearchRequest(SearchContractModel):
    query: str
    scope: SearchScope
    options: SearchOptions

    @field_validator("query")
    @classmethod
    def _query_not_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise SagValidationError("query 不能为空")
        if len(value) > _MAX_SEARCH_QUERY_CHARACTERS:
            raise SagValidationError(f"query 不能超过 {_MAX_SEARCH_QUERY_CHARACTERS} 个字符")
        return value


from zleap.sag.pipeline.options import SearchOptions  # noqa: E402  # isort: skip


SearchRequest.model_rebuild()
