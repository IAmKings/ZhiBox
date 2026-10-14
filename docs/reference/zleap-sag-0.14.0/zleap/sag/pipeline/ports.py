"""Provider-neutral ports consumed by pipeline stages."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Protocol

from zleap.sag.pipeline.contracts import (
    ChunkSet,
    ChunkSetRef,
    EventDetail,
    EventSetRef,
    ParsedBlock,
    PersistedChunkSelector,
    SearchRequest,
    SearchResult,
    SearchScope,
    SourceDescriptor,
)
from zleap.sag.pipeline.options import ExtractionOptions, IndexOptions

if TYPE_CHECKING:
    from zleap.sag.pipeline.context import CancellationToken
    from zleap.sag.pipeline.parse import SourceReaderRegistry


class IndexAdapter(Protocol):
    async def index(
        self,
        chunks: ChunkSet,
        descriptor: SourceDescriptor,
        options: IndexOptions,
    ) -> ChunkSetRef: ...


class ExtractAdapter(Protocol):
    """Adapters receive the run token and should abort generation on cancellation.

    Raise asyncio.CancelledError when interrupted; the stage converts token-driven
    cancellation into PipelineCancelledError and emits the cancelled event.
    """

    async def extract(
        self,
        target: ChunkSetRef | PersistedChunkSelector,
        options: ExtractionOptions,
        *,
        cancellation: CancellationToken | None = None,
    ) -> EventSetRef: ...


class EventLoaderAdapter(Protocol):
    async def load_events(self, event_ref: EventSetRef) -> tuple[EventDetail, ...]: ...


class SearchAdapter(Protocol):
    async def search(self, request: SearchRequest) -> SearchResult: ...


class SearchScopeResolver(Protocol):
    async def resolve(self, scope: SearchScope) -> SearchScope: ...


class ImageEnricher(Protocol):
    """Parse-stage image capability; implementations own their transport resources."""

    @property
    def fingerprint(self) -> str: ...

    async def enrich_many(
        self,
        blocks: tuple[ParsedBlock, ...],
        *,
        source_path: Path | None,
        cancellation: CancellationToken,
    ) -> tuple[ParsedBlock, ...]: ...

    async def aclose(self) -> None: ...


@dataclass(frozen=True, slots=True)
class PipelineComponents:
    """Explicit external composition mode; no schema-validation bypass flag exists."""

    index_adapter: IndexAdapter
    extract_adapter: ExtractAdapter
    search_adapter: SearchAdapter
    event_loader: EventLoaderAdapter | None = None
    source_readers: SourceReaderRegistry | None = None
    image_enricher: ImageEnricher | None = None
    validate_contract: Callable[[], Awaitable[None] | None] | None = None
    aclose: Callable[[], Awaitable[None] | None] | None = None
