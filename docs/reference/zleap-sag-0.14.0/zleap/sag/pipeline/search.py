"""Read-only Search stage over the existing strategy dispatcher."""

from __future__ import annotations

from zleap.sag._safe_error import safe_error_summary
from zleap.sag.pipeline.context import PipelineContext
from zleap.sag.pipeline.contracts import SearchRequest, SearchResult
from zleap.sag.pipeline.errors import SearchStageError, StageInputError
from zleap.sag.pipeline.events import (
    StageEvent,
    StageEventType,
    StageName,
    notify_observer,
)


class SearchStage:
    async def run(
        self,
        request: SearchRequest,
        *,
        context: PipelineContext,
    ) -> SearchResult:
        await context.raise_if_cancelled(StageName.SEARCH)
        if context.search_adapter is None:
            raise StageInputError(
                "Search 需要注入 SearchAdapter",
                stage="search",
                code="search_adapter_required",
                run_id=context.run_id,
            )
        await notify_observer(
            context.observer,
            StageEvent(run_id=context.run_id, stage=StageName.SEARCH, type=StageEventType.STARTED),
        )
        try:
            result = await context.search_adapter.search(request)
        except (SearchStageError, StageInputError) as exc:
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.SEARCH,
                    type=StageEventType.FAILED,
                    error=str(exc),
                ),
            )
            raise
        except Exception as exc:
            error = SearchStageError(
                safe_error_summary(exc, label="搜索失败"),
                stage="search",
                code="search_failed",
                run_id=context.run_id,
                cause_summary=type(exc).__name__,
            )
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.SEARCH,
                    type=StageEventType.FAILED,
                    error=str(error),
                ),
            )
            raise error from exc
        await notify_observer(
            context.observer,
            StageEvent(
                run_id=context.run_id,
                stage=StageName.SEARCH,
                type=StageEventType.SUCCEEDED,
                completed=len(result.events) + len(result.chunks),
            ),
        )
        return result
