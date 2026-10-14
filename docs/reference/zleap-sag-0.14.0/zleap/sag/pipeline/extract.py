"""Extract stage boundary over the existing extraction implementation."""

from __future__ import annotations

import asyncio

from zleap.sag._safe_error import safe_error_summary
from zleap.sag.pipeline.context import PipelineContext
from zleap.sag.pipeline.contracts import (
    ChunkSetRef,
    EventSetRef,
    PersistedChunkSelector,
)
from zleap.sag.pipeline.errors import (
    ExtractionError,
    StageInputError,
    StalePipelineReferenceError,
    UnsupportedStageOptionError,
)
from zleap.sag.pipeline.events import (
    StageEvent,
    StageEventType,
    StageName,
    notify_observer,
)
from zleap.sag.pipeline.options import ExtractionOptions


class ExtractStage:
    async def run(
        self,
        target: ChunkSetRef | PersistedChunkSelector,
        options: ExtractionOptions | None = None,
        *,
        context: PipelineContext,
    ) -> EventSetRef:
        options = options or ExtractionOptions()
        await context.raise_if_cancelled(StageName.EXTRACT)
        if options.source_type is not None and isinstance(target, ChunkSetRef):
            if options.source_type != target.source_type:
                raise StageInputError(
                    "ExtractionOptions.source_type 与 ChunkSetRef 冲突",
                    stage="extract",
                    code="source_type_conflict",
                    run_id=context.run_id,
                )
        if context.extract_adapter is None:
            raise StageInputError(
                "Extract 需要注入 ExtractAdapter",
                stage="extract",
                code="extract_adapter_required",
                run_id=context.run_id,
            )
        await notify_observer(
            context.observer,
            StageEvent(run_id=context.run_id, stage=StageName.EXTRACT, type=StageEventType.STARTED),
        )
        try:
            result = await context.extract_adapter.extract(
                target, options, cancellation=context.cancellation
            )
        except asyncio.CancelledError:
            await context.raise_if_cancelled(StageName.EXTRACT)
            raise
        except (
            ExtractionError,
            StageInputError,
            StalePipelineReferenceError,
            UnsupportedStageOptionError,
        ) as exc:
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.EXTRACT,
                    type=StageEventType.FAILED,
                    error=str(exc),
                ),
            )
            raise
        except Exception as exc:
            error = ExtractionError(
                safe_error_summary(exc, label="提取失败"),
                stage="extract",
                code="extraction_failed",
                run_id=context.run_id,
                cause_summary=type(exc).__name__,
            )
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.EXTRACT,
                    type=StageEventType.FAILED,
                    error=str(error),
                ),
            )
            raise error from exc
        await notify_observer(
            context.observer,
            StageEvent(
                run_id=context.run_id,
                stage=StageName.EXTRACT,
                type=StageEventType.SUCCEEDED,
                completed=len(result.chunk_ids),
                total=len(result.chunk_ids),
            ),
        )
        return result
