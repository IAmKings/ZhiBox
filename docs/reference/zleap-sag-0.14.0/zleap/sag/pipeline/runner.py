"""Fixed five-stage runner with only local, explicit result hand-offs."""

from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import ConfigDict, Field, JsonValue

from zleap.sag._safe_error import safe_error_summary
from zleap.sag.pipeline.chunk import ChunkStage
from zleap.sag.pipeline.context import PipelineContext
from zleap.sag.pipeline.contracts import (
    ChunkSet,
    ChunkSetRef,
    EventSetRef,
    ParsedSource,
    PersistedChunkSelector,
    SearchRequest,
    SearchResult,
    SourceDescriptor,
    SourceInput,
)
from zleap.sag.pipeline.errors import StageInputError
from zleap.sag.pipeline.events import StageEvent, StageEventType, StageName, notify_observer
from zleap.sag.pipeline.extract import ExtractStage
from zleap.sag.pipeline.index import IndexStage
from zleap.sag.pipeline.options import (
    ChunkOptions,
    ExtractionOptions,
    IndexOptions,
    OptionsModel,
    ParseOptions,
)
from zleap.sag.pipeline.parse import ParseStage
from zleap.sag.pipeline.search import SearchStage


class FrozenModel(OptionsModel):
    model_config = ConfigDict(arbitrary_types_allowed=True)


class FailurePolicy(StrEnum):
    FAIL_FAST = "fail_fast"
    CONTINUE_ITEMS = "continue_items"


class ParseStep(FrozenModel):
    source: SourceInput
    options: ParseOptions = Field(default_factory=ParseOptions)


class ChunkStep(FrozenModel):
    parsed: ParsedSource | None = None
    options: ChunkOptions = Field(default_factory=ChunkOptions)


class IndexStep(FrozenModel):
    chunks: ChunkSet | None = None
    descriptor: SourceDescriptor
    options: IndexOptions = Field(default_factory=IndexOptions)


class ExtractStep(FrozenModel):
    target: ChunkSetRef | PersistedChunkSelector | None = None
    options: ExtractionOptions = Field(default_factory=ExtractionOptions)


class SearchStep(FrozenModel):
    request: SearchRequest


class PipelinePlan(FrozenModel):
    parse: ParseStep | None = None
    chunk: ChunkStep | None = None
    index: IndexStep | None = None
    extract: ExtractStep | None = None
    search: SearchStep | None = None
    failure_policy: FailurePolicy = FailurePolicy.FAIL_FAST


class PipelineRunResult(FrozenModel):
    run_id: str
    parsed: ParsedSource | None = None
    chunks: ChunkSet | None = None
    chunk_ref: ChunkSetRef | None = None
    event_ref: EventSetRef | None = None
    search_result: SearchResult | None = None
    errors: tuple[dict[str, JsonValue], ...] = ()


class PipelineRunner:
    def __init__(self) -> None:
        self.parse_stage = ParseStage()
        self.chunk_stage = ChunkStage()
        self.index_stage = IndexStage()
        self.extract_stage = ExtractStage()
        self.search_stage = SearchStage()

    async def run(self, plan: PipelinePlan, *, context: PipelineContext) -> PipelineRunResult:
        self._validate(plan, context.run_id)
        parsed: ParsedSource | None = None
        chunks: ChunkSet | None = None
        chunk_ref: ChunkSetRef | None = None
        event_ref: EventSetRef | None = None
        search_result: SearchResult | None = None
        errors: list[dict[str, JsonValue]] = []
        dependency_failed = False

        if plan.parse:
            parsed, failed = await self._execute(
                lambda: self.parse_stage.run(
                    plan.parse.source, plan.parse.options, context=context
                ),
                "parse",
                plan,
                errors,
            )
            dependency_failed = failed

        if plan.chunk:
            if dependency_failed and plan.chunk.parsed is None:
                await self._skipped(context, StageName.CHUNK, "Parse 失败")
            else:
                chunk_input = plan.chunk.parsed or parsed
                assert chunk_input is not None
                chunks, failed = await self._execute(
                    lambda: self.chunk_stage.run(chunk_input, plan.chunk.options, context=context),
                    "chunk",
                    plan,
                    errors,
                )
                dependency_failed = failed

        if plan.index:
            if dependency_failed and plan.index.chunks is None:
                await self._skipped(context, StageName.INDEX, "Chunk 失败")
            else:
                index_input = plan.index.chunks or chunks
                assert index_input is not None
                chunk_ref, failed = await self._execute(
                    lambda: self.index_stage.run(
                        index_input,
                        plan.index.descriptor,
                        plan.index.options,
                        context=context,
                    ),
                    "index",
                    plan,
                    errors,
                )
                dependency_failed = failed and chunk_ref is None

        if plan.extract:
            if dependency_failed and plan.extract.target is None:
                await self._skipped(context, StageName.EXTRACT, "Index 失败")
            else:
                extract_input = plan.extract.target or chunk_ref
                assert extract_input is not None
                event_ref, failed = await self._execute(
                    lambda: self.extract_stage.run(
                        extract_input, plan.extract.options, context=context
                    ),
                    "extract",
                    plan,
                    errors,
                )
                dependency_failed = failed and event_ref is None

        if plan.search:
            # Search has a complete explicit scope and is independent from EventSetRef.
            if errors and plan.failure_policy == FailurePolicy.FAIL_FAST:
                await self._skipped(context, StageName.SEARCH, "上游失败")
            else:
                search_result, _ = await self._execute(
                    lambda: self.search_stage.run(plan.search.request, context=context),
                    "search",
                    plan,
                    errors,
                )

        return PipelineRunResult(
            run_id=context.run_id,
            parsed=parsed,
            chunks=chunks,
            chunk_ref=chunk_ref,
            event_ref=event_ref,
            search_result=search_result,
            errors=tuple(errors),
        )

    async def _execute(
        self,
        operation: Any,
        stage: str,
        plan: PipelinePlan,
        errors: list[dict[str, JsonValue]],
    ) -> tuple[Any, bool]:
        try:
            return await operation(), False
        except Exception as exc:
            partial = getattr(exc, "partial_result", None)
            if plan.failure_policy == FailurePolicy.FAIL_FAST:
                # FAIL_FAST is an exception contract, not a successful result carrying an
                # error list.  Built-in stages compensate both stores before raising.
                raise
            errors.append(
                {
                    "stage": stage,
                    "code": str(getattr(exc, "code", "stage_failed")),
                    "message": safe_error_summary(exc, label=f"{stage} failed"),
                    "retryable": bool(getattr(exc, "retryable", False)),
                }
            )
            if plan.failure_policy == FailurePolicy.CONTINUE_ITEMS and partial is not None:
                return partial, True
            return None, True

    @staticmethod
    async def _skipped(context: PipelineContext, stage: StageName, message: str) -> None:
        await notify_observer(
            context.observer,
            StageEvent(
                run_id=context.run_id,
                stage=stage,
                type=StageEventType.SKIPPED,
                message=message,
            ),
        )

    @staticmethod
    def _validate(plan: PipelinePlan, run_id: str) -> None:
        if not any((plan.parse, plan.chunk, plan.index, plan.extract, plan.search)):
            raise StageInputError(
                "PipelinePlan 至少包含一个阶段",
                stage="runner",
                code="empty_plan",
                run_id=run_id,
            )
        if plan.chunk and plan.chunk.parsed is None and plan.parse is None:
            raise StageInputError(
                "ChunkStep 缺少 ParsedSource",
                stage="runner",
                code="missing_stage_input",
                run_id=run_id,
            )
        if plan.index and plan.index.chunks is None and plan.chunk is None:
            raise StageInputError(
                "IndexStep 缺少 ChunkSet",
                stage="runner",
                code="missing_stage_input",
                run_id=run_id,
            )
        if plan.extract and plan.extract.target is None and plan.index is None:
            raise StageInputError(
                "ExtractStep 缺少 ChunkSetRef/PersistedChunkSelector",
                stage="runner",
                code="missing_stage_input",
                run_id=run_id,
            )
