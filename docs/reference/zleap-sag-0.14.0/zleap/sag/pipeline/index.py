"""Index stage boundary; persistence lives behind an injected adapter."""

from __future__ import annotations

from zleap.sag._safe_error import safe_error_summary
from zleap.sag.pipeline.context import PipelineContext
from zleap.sag.pipeline.contracts import ChunkSet, ChunkSetRef, SourceDescriptor, WriteStatus
from zleap.sag.pipeline.errors import (
    IndexError,
    StageInputError,
    StalePipelineReferenceError,
)
from zleap.sag.pipeline.events import (
    StageEvent,
    StageEventType,
    StageName,
    notify_observer,
)
from zleap.sag.pipeline.options import IndexOptions


class IndexStage:
    async def run(
        self,
        chunks: ChunkSet,
        descriptor: SourceDescriptor,
        options: IndexOptions | None = None,
        *,
        context: PipelineContext,
    ) -> ChunkSetRef:
        options = options or IndexOptions()
        await context.raise_if_cancelled(StageName.INDEX)
        if not descriptor.data_source_id:
            raise StageInputError(
                "Index 需要 descriptor.data_source_id",
                stage="index",
                code="data_source_id_required",
                run_id=context.run_id,
            )
        self._validate_descriptor(chunks.descriptor, descriptor, context.run_id)
        if context.index_adapter is None:
            raise StageInputError(
                "Index 需要注入 IndexAdapter",
                stage="index",
                code="index_adapter_required",
                run_id=context.run_id,
            )
        await notify_observer(
            context.observer,
            StageEvent(run_id=context.run_id, stage=StageName.INDEX, type=StageEventType.STARTED),
        )
        try:
            result = await context.index_adapter.index(chunks, descriptor, options)
        except (IndexError, StageInputError, StalePipelineReferenceError) as exc:
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.INDEX,
                    type=StageEventType.FAILED,
                    error=str(exc),
                ),
            )
            raise
        except Exception as exc:
            error = IndexError(
                safe_error_summary(exc, label="索引失败"),
                stage="index",
                code="index_failed",
                run_id=context.run_id,
                cause_summary=type(exc).__name__,
            )
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.INDEX,
                    type=StageEventType.FAILED,
                    error=str(error),
                ),
            )
            raise error from exc
        event_type = (
            StageEventType.PARTIAL
            if WriteStatus(result.relation_status) != WriteStatus.SUCCEEDED
            or WriteStatus(result.vector_status)
            not in {WriteStatus.SUCCEEDED, WriteStatus.SKIPPED_BY_POLICY}
            else StageEventType.SUCCEEDED
        )
        await notify_observer(
            context.observer,
            StageEvent(
                run_id=context.run_id,
                stage=StageName.INDEX,
                type=event_type,
                completed=len(result.chunk_ids) - len(result.failed_items),
                total=len(result.chunk_ids),
            ),
        )
        return result

    @staticmethod
    def _validate_descriptor(
        chunk_descriptor: SourceDescriptor,
        descriptor: SourceDescriptor,
        run_id: str,
    ) -> None:
        for field in ("source_type", "source_id", "external_id"):
            left = getattr(chunk_descriptor, field)
            right = getattr(descriptor, field)
            if left is not None and right is not None and left != right:
                raise StageInputError(
                    f"ChunkSet descriptor.{field} 与 Index descriptor 冲突",
                    stage="index",
                    code="source_descriptor_conflict",
                    run_id=run_id,
                )
