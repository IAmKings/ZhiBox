"""Best-effort stage observation contracts."""

from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from enum import StrEnum
from typing import Protocol

from pydantic import ConfigDict, Field, JsonValue

from zleap.sag.validation import SagBaseModel as BaseModel


class StageName(StrEnum):
    PARSE = "parse"
    CHUNK = "chunk"
    INDEX = "index"
    EXTRACT = "extract"
    SEARCH = "search"


class StageEventType(StrEnum):
    STARTED = "started"
    PROGRESS = "progress"
    SUCCEEDED = "succeeded"
    PARTIAL = "partial"
    FAILED = "failed"
    SKIPPED = "skipped"
    CANCELLED = "cancelled"


class StageEvent(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid", use_enum_values=True)

    run_id: str
    stage: StageName
    type: StageEventType
    completed: int | None = Field(default=None, ge=0)
    total: int | None = Field(default=None, ge=0)
    message: str | None = None
    item_id: str | None = None
    stats: dict[str, JsonValue] = Field(default_factory=dict)
    error: str | None = None
    timestamp: datetime = Field(default_factory=lambda: datetime.now(UTC))


class StageObserver(Protocol):
    def __call__(self, event: StageEvent) -> Awaitable[None] | None: ...


Observer = StageObserver | Callable[[StageEvent], Awaitable[None] | None]


async def notify_observer(observer: Observer | None, event: StageEvent) -> None:
    """Notify a best-effort observer without changing the business result."""

    if observer is None:
        return
    try:
        result = observer(event)
        if inspect.isawaitable(result):
            await result
    except Exception:
        # Observability is deliberately best-effort. Hosts requiring an audit gate
        # should wrap the observer and fail before invoking the pipeline.
        return
