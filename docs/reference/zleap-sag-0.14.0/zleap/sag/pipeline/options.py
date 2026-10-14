"""Immutable options for each explicit pipeline stage."""

from __future__ import annotations

from enum import StrEnum
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import (
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)

from zleap.sag.exceptions import ValidationError as SagValidationError
from zleap.sag.validation import SagBaseModel as BaseModel


class OptionsModel(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid", use_enum_values=True)


_MAX_PARSE_CHARACTERS = 25 * 1024 * 1024


class ParseOptions(OptionsModel):
    encoding: str = "utf-8"
    format_hint: str | None = None
    max_chars: int = Field(default=_MAX_PARSE_CHARACTERS, ge=1, le=_MAX_PARSE_CHARACTERS)
    converter: str | None = None
    reader: str | None = None
    preserve_raw: bool = True
    error_policy: Literal["raise", "truncate"] = "raise"


class EmptyPolicy(StrEnum):
    ERROR = "error"
    RETURN_EMPTY = "return_empty"


class ChunkOptions(OptionsModel):
    strategy: Literal["standard", "heading_strict", "overlap", "time_window", "token_window"] = (
        "standard"
    )
    max_tokens: int = Field(default=800, ge=32, le=100_000)
    overlap_tokens: int = Field(default=0, ge=0)
    min_content_length: int = Field(default=0, ge=0)
    time_window_seconds: int = Field(default=300, ge=1)
    empty_policy: EmptyPolicy = EmptyPolicy.ERROR

    @model_validator(mode="after")
    def _validate_overlap(self) -> ChunkOptions:
        if self.overlap_tokens >= self.max_tokens:
            raise SagValidationError("overlap_tokens 必须小于 max_tokens")
        return self


class ReplacePolicy(StrEnum):
    CREATE_ONLY = "create_only"
    REUSE_IF_SAME = "reuse_if_same"
    REPLACE_CURRENT = "replace_current"


class VectorPolicy(StrEnum):
    ENABLED = "enabled"
    DISABLED = "disabled"


class IndexOptions(OptionsModel):
    replace_policy: ReplacePolicy = ReplacePolicy.REUSE_IF_SAME
    vector_policy: VectorPolicy = VectorPolicy.ENABLED
    embedding_batch_size: int = Field(default=10, ge=1, le=100)
    vector_batch_size: int = Field(default=50, ge=1, le=500)
    refresh: bool = False


class ExtractionStrategy(StrEnum):
    SAG_EXTRACT = "sag_extract"


class ExtractionLimits(OptionsModel):
    max_events_per_chunk: int = Field(default=20, ge=1, le=200)
    min_entities_per_event: int = Field(default=1, ge=0, le=100)
    max_entities_per_event: int = Field(default=20, ge=1, le=100)

    @model_validator(mode="after")
    def _validate_entity_range(self) -> ExtractionLimits:
        if self.min_entities_per_event > self.max_entities_per_event:
            raise SagValidationError("min_entities_per_event 不能大于 max_entities_per_event")
        return self


class ExtractionExecutionOptions(OptionsModel):
    max_concurrency: int = Field(default=5, ge=1, le=100)
    # A few OpenAI-compatible gateways occasionally return an unclassified 400
    # for a valid content request.  Retry only that Chunk, then split it once;
    # known policy/configuration/context failures remain terminal.
    request_rejection_retries: int = Field(default=1, ge=0, le=2)
    request_recovery_min_chars: int = Field(default=400, ge=100, le=10_000)
    embedding_batch_size: int = Field(default=10, ge=1, le=100)
    vector_batch_size: int = Field(default=50, ge=1, le=500)
    # 实体关系仍会持久化；关闭后只省略实体 embedding 与实体 vector。
    write_entity_vectors: bool = True
    # 事项-实体关联索引：每个关联单独生成一次 embedding（关联数通常是事项数的十几倍），
    # 但只有 ProductionSearchExecutor 读取它；full_expand 不读，可关闭以显著提速。
    write_event_entity_vectors: bool = True


class RelatedEventContextOptions(OptionsModel):
    enabled: bool = False
    top_k: int = Field(default=3, ge=1, le=10)
    threshold: float = Field(default=0.7, ge=0.0, le=1.0)


class ExtractionOptions(OptionsModel):
    strategy: Literal["sag_extract"] = "sag_extract"
    source_type: Literal["text", "article", "chat", "recording"] | None = None

    custom_prompt: str | None = Field(default=None, max_length=50_000)
    background: str | None = Field(default=None, max_length=50_000)
    guidance_rules: tuple[str, ...] = ()
    # 逐字复刻模式：为 True 时 system prompt 仅使用 custom_prompt（必须提供），
    # 不追加运行上下文/指导规则/输出合同/强制限制段落，且强制携带 few-shot 示例。
    # 用于复现外部基准（如 SAG-Benchmark）的提示词行为。
    verbatim_prompt: bool = False
    # 抽取合同模式，与 storage_mode 无关：minimal=精简合同(单事件，无
    # references/reason/summary)；rich=富合同(默认，多事件，增加 summary、reason、
    # references)。reason 仅用于审计，不落库。刻意不用 lite/normal，避免与
    # 存储层 storage_mode 混淆（后者决定向量怎么落盘，与本合同正交）。
    contract: Literal["minimal", "rich"] = "rich"
    # Event-local violations may be dropped; parent-summary requests stay strict.
    on_contract_violation: Literal["raise", "drop_event"] = "raise"
    # 与 custom_prompt 同源的 few-shot 示例。verbatim 模式下若不提供，则不携带任何
    # few-shot——绝不回退到 zleap 内置示例，否则会与外部提示词的约定冲突。
    #
    # custom_examples=((输入1, 输出1), (输入2, 输出2), ...)，按顺序展开成多组
    # user/assistant 消息。需要覆盖多种实体类型、或需要同时示范"单事项"与
    # "多事项"两种分支时使用。单对示例即为长度为 1 的元组。
    custom_examples: tuple[tuple[str, str], ...] = ()

    limits: ExtractionLimits = Field(default_factory=ExtractionLimits)
    max_retries: int = Field(default=5, ge=0, le=10)
    timezone: str = "Asia/Shanghai"

    enable_article_summary: bool = False
    enable_parent_summary: bool = False
    parent_summary_token_budget: int = Field(default=18_000, ge=500, le=100_000)

    related_events: RelatedEventContextOptions = Field(default_factory=RelatedEventContextOptions)
    execution: ExtractionExecutionOptions = Field(default_factory=ExtractionExecutionOptions)

    @field_validator("custom_prompt", "background", mode="before")
    @classmethod
    def _normalize_optional_text(cls, value: object) -> object:
        if value is None:
            return None
        if not isinstance(value, str):
            return value
        normalized = value.strip()
        return normalized or None

    @field_validator("custom_examples", mode="before")
    @classmethod
    def _normalize_custom_examples(cls, value: object) -> object:
        if value is None:
            return ()
        if not isinstance(value, tuple | list):
            return value
        normalized: list[tuple[str, str]] = []
        for index, pair in enumerate(value, 1):
            if not isinstance(pair, tuple | list) or len(pair) != 2:
                raise SagValidationError(f"custom_examples[{index}] 必须是 (输入, 输出) 二元组")
            example_input, example_output = pair
            if not isinstance(example_input, str) or not isinstance(example_output, str):
                raise SagValidationError(f"custom_examples[{index}] 的输入与输出必须是字符串")
            stripped_input = example_input.strip()
            stripped_output = example_output.strip()
            if not stripped_input or not stripped_output:
                raise SagValidationError(f"custom_examples[{index}] 的输入与输出都不能为空")
            normalized.append((stripped_input, stripped_output))
        return tuple(normalized)

    @field_validator("guidance_rules", mode="before")
    @classmethod
    def _normalize_guidance_rules(cls, value: object) -> object:
        if value is None:
            return ()
        if isinstance(value, str):
            value = (value,)
        if not isinstance(value, tuple | list):
            return value
        normalized: list[str] = []
        seen: set[str] = set()
        for rule in value:
            if not isinstance(rule, str):
                normalized.append(rule)
                continue
            item = rule.strip()
            if not item:
                raise SagValidationError("guidance_rules 不能包含空规则")
            if item not in seen:
                normalized.append(item)
                seen.add(item)
        return tuple(normalized)

    @field_validator("timezone")
    @classmethod
    def _validate_timezone(cls, value: str) -> str:
        normalized = value.strip()
        try:
            ZoneInfo(normalized)
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise SagValidationError(f"无效 timezone: {normalized}", cause=exc) from exc
        return normalized

    @model_validator(mode="after")
    def _validate_summary_source(self) -> ExtractionOptions:
        if self.enable_article_summary and self.source_type in {"chat", "recording"}:
            raise SagValidationError("enable_article_summary 只适用于 text/article")
        return self


class BuiltinSearchStrategy(StrEnum):
    VECTOR = "vector"
    ATOMIC = "atomic"
    FULL_EXPAND = "full_expand"
    PRUNED_EXPAND_LLM = "pruned_expand_llm"
    PRUNED_EXPAND_RERANK = "pruned_expand_rerank"
    PRUNED_EXPAND_RFF = "pruned_expand_rff"


class QueryProcessingOptions(OptionsModel):
    rewrite: bool | None = None
    entity_extraction: Literal["disabled", "rewrite", "llm", "lexical"] | None = None
    normalization: bool | None = None
    max_rewrites: int | None = Field(default=None, ge=0, le=2)


class EntityRecallOptions(OptionsModel):
    enabled: bool | None = None
    top_k: int | None = Field(default=None, ge=1, le=1000)
    vector_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    max_events_per_entity: int | None = Field(default=None, ge=1, le=1000)


class EventRecallOptions(OptionsModel):
    vector_top_k: int | None = Field(default=None, ge=1, le=2000)
    vector_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    lexical_top_k: int | None = Field(default=None, ge=1, le=2000)


class RecallOptions(OptionsModel):
    entity: EntityRecallOptions = Field(default_factory=EntityRecallOptions)
    event: EventRecallOptions = Field(default_factory=EventRecallOptions)
    merge: Literal["union", "weighted", "rrf"] | None = None
    candidate_pool_size: int | None = Field(default=None, ge=0, le=2000)

    @field_validator("candidate_pool_size")
    @classmethod
    def _validate_candidate_pool_size(cls, value: int | None) -> int | None:
        if value is not None and not (value == 0 or 200 <= value <= 2000):
            raise SagValidationError(
                f"candidate_pool_size 必须为 0/None 或 200..2000，得到 {value}"
            )
        return value


class GraphSearchOptions(OptionsModel):
    enabled: bool | None = None
    max_hops: int | None = Field(default=None, ge=0, le=10)
    entities_per_hop: int | None = Field(default=None, ge=1, le=1000)
    events_per_hop: int | None = Field(default=None, ge=1, le=5000)
    event_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    relation_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    seed_event_limit: int | None = Field(default=None, ge=0, le=1000)

    @model_validator(mode="after")
    def _validate_enabled_hops(self) -> GraphSearchOptions:
        if self.enabled is False and self.max_hops not in (None, 0):
            raise SagValidationError("graph.enabled=False 时 max_hops 只能为 0 或省略")
        if self.enabled is True and self.max_hops == 0:
            raise SagValidationError("graph.enabled=True 时 max_hops 必须大于 0 或省略")
        return self


class RankingOptions(OptionsModel):
    method: Literal["vector", "weighted", "rrf", "rerank", "llm"] | None = None
    candidate_limit: int | None = Field(default=None, ge=1, le=5000)
    # Production executor 不读取：recall/expand 阶段已有独立阈值
    # （recall.event.vector_threshold / graph.event_threshold /
    # graph.relation_threshold）。legacy executors 仍读（executors.py 的
    # VectorExecutor + legacy bridge、multi.py、atomic.py、core/config.py）。
    # 保留字段用于外部 API 兼容，避免破坏已序列化的 SearchOptions。
    score_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    rrf_k: int | None = Field(default=None, ge=1, le=10_000)
    rerank_top_n: int | None = Field(default=None, ge=1, le=1000)
    rerank_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    # LLM 精排最终挑选的事件数（对应 SAG2 multi 的 rerank_top_k）。None 时 = top_k。
    # 当 llm_select_top_n < top_k 时，_hydrate_chunks_topped_up 会补齐到 top_k。
    llm_select_top_n: int | None = Field(default=None, ge=1, le=50)
    llm_prompt_name: (
        Literal["llm_rank_events", "llm_rank_events_cot", "select_useful_relations_local"] | None
    ) = None
    llm_include_content: bool | None = None
    llm_max_content_chars: int | None = Field(default=None, ge=0, le=50_000)
    llm_include_thought_process: bool | None = None
    timeout_seconds: float | None = Field(default=None, ge=0.1, le=120.0)
    on_timeout: Literal["error", "fallback_to_score"] | None = None


class SelectionOptions(OptionsModel):
    enabled: bool | None = None
    max_candidates: int | None = Field(default=None, ge=1, le=200)
    custom_prompt: str | None = Field(default=None, max_length=50_000)
    include_selection_rationale: bool | None = None
    max_rationale_chars: int = Field(default=300, ge=0, le=2000)

    @field_validator("custom_prompt", mode="before")
    @classmethod
    def _normalize_custom_prompt(cls, value: object) -> object:
        if value is None or not isinstance(value, str):
            return value
        normalized = value.strip()
        return normalized or None

    @model_validator(mode="after")
    def _validate_prompt_requires_selection(self) -> SelectionOptions:
        if self.enabled is False and self.custom_prompt is not None:
            raise SagValidationError("selection.enabled=False 时不能提供 custom_prompt")
        return self


class SearchOutputOptions(OptionsModel):
    return_graph: bool = False
    return_paths: bool = False
    include_stage_stats: bool = False
    # 排序完成、最终 top_k 截断前的候选快照，最多 20 条。
    include_ranked_candidates: bool = False
    max_paths_per_event: int = Field(default=20, ge=1, le=200)
    include_chunk: bool = False
    include_event: bool = False


class SearchOptions(OptionsModel):
    strategy: str
    fallback_strategy: str | None = None
    top_k: int = Field(default=10, ge=1, le=1000)
    return_type: Literal["event", "chunk"] = "event"
    guard: bool = True
    # 搜索级结构化输出模式：None 时用引擎级 LLM 配置默认（json_schema）；
    # 显式指定则本请求内所有 LLM 结构化调用（查询改写/NER/精排/选择）按此模式走。
    structured_output_mode: str | None = None
    query: QueryProcessingOptions = Field(default_factory=QueryProcessingOptions)
    recall: RecallOptions = Field(default_factory=RecallOptions)
    graph: GraphSearchOptions = Field(default_factory=GraphSearchOptions)
    ranking: RankingOptions = Field(default_factory=RankingOptions)
    selection: SelectionOptions = Field(default_factory=SelectionOptions)
    output: SearchOutputOptions = Field(default_factory=SearchOutputOptions)

    @field_validator("strategy", "fallback_strategy")
    @classmethod
    def _normalize_strategy_name(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise SagValidationError("搜索策略名不能为空")
        return normalized

    @field_validator("structured_output_mode")
    @classmethod
    def _validate_structured_output_mode(cls, value: str | None) -> str | None:
        if value is None:
            return None
        # 与注册表键保持一致：归一化（小写）后再比对，引擎级配置与请求级路径大小写行为统一。
        normalized = value.strip().lower()
        from zleap.sag.core.ai.structured import available_modes

        if normalized not in available_modes():
            raise SagValidationError(
                f"未知结构化输出模式 {value!r}；可选: {', '.join(available_modes())}"
            )
        return normalized

    @model_validator(mode="after")
    def _validate_search_combinations(self) -> SearchOptions:
        if self.fallback_strategy == self.strategy:
            raise SagValidationError("fallback_strategy 不能与 strategy 相同")
        if self.output.return_paths and not self.output.return_graph:
            raise SagValidationError("return_paths=True 要求同时设置 return_graph=True")
        if self.return_type == "event" and self.output.include_event:
            raise SagValidationError("include_event 仅在 return_type='chunk' 时生效")
        if self.return_type == "chunk" and self.output.include_chunk:
            raise SagValidationError("include_chunk 仅在 return_type='event' 时生效")
        return self
