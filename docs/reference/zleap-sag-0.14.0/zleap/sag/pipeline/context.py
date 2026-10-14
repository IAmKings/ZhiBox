"""Per-run dependencies and cancellation state."""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import Any, TypeVar
from uuid import uuid4

from zleap.sag.pipeline.errors import PipelineCancelledError
from zleap.sag.pipeline.events import (
    Observer,
    StageEvent,
    StageEventType,
    StageName,
    notify_observer,
)

_T = TypeVar("_T")


class CancellationToken:
    def __init__(self) -> None:
        self._event = asyncio.Event()

    def cancel(self) -> None:
        self._event.set()

    async def run(self, operation: Callable[[], Awaitable[_T]]) -> _T:
        """Cancel and drain request-local async work when this token is set."""
        if self.is_cancelled:
            raise asyncio.CancelledError
        work = asyncio.ensure_future(operation())
        cancelled = asyncio.create_task(self._event.wait())
        try:
            await asyncio.wait((work, cancelled), return_when=asyncio.FIRST_COMPLETED)
            if self.is_cancelled:
                raise asyncio.CancelledError
            return await work
        finally:
            for task in (work, cancelled):
                if not task.done():
                    task.cancel()
            await asyncio.gather(work, cancelled, return_exceptions=True)

    @property
    def is_cancelled(self) -> bool:
        return self._event.is_set()


@dataclass(frozen=True, slots=True)
class PipelineContext:
    """Dependencies only; it never stores a previous stage result."""

    run_id: str = field(default_factory=lambda: str(uuid4()))
    observer: Observer | None = None
    cancellation: CancellationToken = field(default_factory=CancellationToken)
    source_readers: Any = None
    image_enricher: Any = None
    index_adapter: Any = None
    extract_adapter: Any = None
    search_adapter: Any = None

    async def raise_if_cancelled(self, stage: StageName) -> None:
        if not self.cancellation.is_cancelled:
            return
        await notify_observer(
            self.observer,
            StageEvent(
                run_id=self.run_id,
                stage=stage,
                type=StageEventType.CANCELLED,
                message="流水线已取消",
            ),
        )
        raise PipelineCancelledError(
            "流水线已取消",
            stage=stage.value,
            run_id=self.run_id,
            code="pipeline_cancelled",
        )
