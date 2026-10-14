"""Structured errors emitted by explicit pipeline stages."""

from __future__ import annotations

from typing import Any

from zleap.sag.exceptions import SagError


class PipelineError(SagError):
    _code = "pipeline_error"

    def __init__(
        self,
        message: str,
        *,
        stage: str | None = None,
        code: str = "pipeline_error",
        run_id: str | None = None,
        retryable: bool = False,
        item_id: str | None = None,
        provider: str | None = None,
        partial_result: Any = None,
        cause_summary: str | None = None,
        operation: str | None = None,
        details: dict[str, Any] | None = None,
        cause: BaseException | None = None,
    ) -> None:
        # 旧 pipeline 专属 kwargs 归入 details(统一结构化),同时保留实例属性,
        # 兼容 runner.py::`getattr(exc, "partial_result", None)` 等既有属性访问;
        # 现有 ~30 个调用点(parse/chunk/index/extract/search/_pipeline_adapters)零改动。
        merged = dict(details or {})
        if run_id is not None:
            merged["run_id"] = run_id
        if partial_result is not None:
            merged["partial_result"] = partial_result
        if cause_summary is not None:
            merged["cause_summary"] = cause_summary
        self.run_id = run_id
        self.partial_result = partial_result
        self.cause_summary = cause_summary
        super().__init__(
            message,
            code=code,
            operation=operation,
            stage=stage,
            retryable=retryable,
            item_id=item_id,
            provider=provider,
            details=merged or None,
            cause=cause,
        )


class StageInputError(PipelineError):
    pass


class SourceReadError(PipelineError):
    pass


class ParseError(PipelineError):
    pass


class ChunkingError(PipelineError):
    pass


class IndexError(PipelineError):
    pass


class ExtractionError(PipelineError):
    pass


class ExtractionBatchFailure(ExtractionError):
    def __init__(
        self,
        message: str,
        *,
        failures: tuple[Any, ...] = (),
        stats: dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(message, **kwargs)
        self.failures = failures
        self.stats = stats or {}


class SearchStageError(PipelineError):
    pass


class SearchPlanError(SearchStageError):
    pass


class UnknownSearchStrategyError(SearchPlanError):
    pass


class MissingSearchCapabilityError(SearchPlanError):
    pass


class StalePipelineReferenceError(PipelineError):
    pass


class UnsupportedSourceTypeError(PipelineError):
    pass


class UnsupportedStageOptionError(PipelineError):
    pass


class PipelineCancelledError(PipelineError):
    pass
