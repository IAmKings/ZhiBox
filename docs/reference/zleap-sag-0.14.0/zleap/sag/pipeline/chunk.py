"""Deterministic in-memory Chunking stage."""

from __future__ import annotations

from datetime import datetime

from zleap.sag._safe_error import safe_error_summary
from zleap.sag.modules.load.chunking import (
    BlockType,
    InputDocument,
    MarkdownArticleSectionBuilder,
    MarkdownHeadingStrictChunker,
    MarkdownSourceChunkAssembler,
    StructuredBlock,
)
from zleap.sag.modules.load.chunking.tabular import project_table_record_groups
from zleap.sag.modules.load.chunking.tokenizer import TokenizerTokenEstimator
from zleap.sag.pipeline._utils import content_hash
from zleap.sag.pipeline.context import PipelineContext
from zleap.sag.pipeline.contracts import (
    ChunkItem,
    ChunkSet,
    ChunkStats,
    ParsedConversation,
    ParsedDocument,
    ParsedRecording,
    ParsedSource,
    ParsedUnit,
    SectionItem,
    SourceReference,
)
from zleap.sag.pipeline.errors import ChunkingError, StageInputError
from zleap.sag.pipeline.events import (
    StageEvent,
    StageEventType,
    StageName,
    notify_observer,
)
from zleap.sag.pipeline.options import ChunkOptions, EmptyPolicy

CHUNKER_IMPLEMENTATION_VERSION = "explicit-5-code-formula-html-table"
TABLE_CHUNKER_IMPLEMENTATION_VERSION = "markdown-table-1"


class ChunkStage:
    async def run(
        self,
        parsed: ParsedSource,
        options: ChunkOptions | None = None,
        *,
        context: PipelineContext | None = None,
    ) -> ChunkSet:
        options = options or ChunkOptions()
        context = context or PipelineContext()
        await context.raise_if_cancelled(StageName.CHUNK)
        await notify_observer(
            context.observer,
            StageEvent(run_id=context.run_id, stage=StageName.CHUNK, type=StageEventType.STARTED),
        )
        try:
            chunks = await self._chunk(parsed, options)
            if not chunks and options.empty_policy == EmptyPolicy.ERROR:
                raise StageInputError(
                    "解析结果没有可切分内容",
                    stage="chunk",
                    code="empty_source",
                    run_id=context.run_id,
                )
            source_version = self._source_version(parsed)
            version_payload = {
                "source_version": source_version,
                "options": options.model_dump(mode="json"),
                "implementation": CHUNKER_IMPLEMENTATION_VERSION,
            }
            # heading_strict preserves the original parsed blocks and does not run
            # the normal table chunker, so it must not claim table metadata.
            has_table = (
                isinstance(parsed, ParsedDocument)
                and options.strategy != "heading_strict"
                and any(block.block_type.lower() == "table" for block in parsed.blocks)
            )
            if has_table:
                version_payload["table_implementation"] = TABLE_CHUNKER_IMPLEMENTATION_VERSION
            chunk_version = content_hash(version_payload)
            units = (
                parsed.units if isinstance(parsed, (ParsedConversation, ParsedRecording)) else ()
            )
            total_tokens = sum(item.token_count or 0 for item in chunks)
            result = ChunkSet(
                descriptor=parsed.descriptor,
                source_version=source_version,
                chunk_version=chunk_version,
                chunks=tuple(chunks),
                units=units,
                stats=ChunkStats(
                    input_units=len(parsed.blocks)
                    if isinstance(parsed, ParsedDocument)
                    else len(units),
                    chunk_count=len(chunks),
                    token_count=total_tokens,
                ),
                metadata={
                    "chunker_implementation": CHUNKER_IMPLEMENTATION_VERSION,
                    **(
                        {"table_chunker_implementation": TABLE_CHUNKER_IMPLEMENTATION_VERSION}
                        if has_table
                        else {}
                    ),
                },
            )
        except (ChunkingError, StageInputError) as exc:
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.CHUNK,
                    type=StageEventType.FAILED,
                    error=str(exc),
                ),
            )
            raise
        except Exception as exc:
            error = ChunkingError(
                safe_error_summary(exc, label="切块失败"),
                stage="chunk",
                code="chunking_failed",
                run_id=context.run_id,
                cause_summary=type(exc).__name__,
            )
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.CHUNK,
                    type=StageEventType.FAILED,
                    error=str(error),
                ),
            )
            raise error from exc
        await notify_observer(
            context.observer,
            StageEvent(
                run_id=context.run_id,
                stage=StageName.CHUNK,
                type=StageEventType.SUCCEEDED,
                completed=len(result.chunks),
                total=len(result.chunks),
            ),
        )
        return result

    async def _chunk(self, parsed: ParsedSource, options: ChunkOptions) -> list[ChunkItem]:
        if isinstance(parsed, ParsedDocument):
            if options.strategy in {"time_window", "token_window"}:
                raise StageInputError(
                    f"文档来源不支持 {options.strategy}",
                    stage="chunk",
                    code="invalid_chunk_strategy",
                )
            return await self._chunk_document(parsed, options)
        if isinstance(parsed, (ParsedConversation, ParsedRecording)):
            return self._chunk_units(parsed, options)
        raise StageInputError(
            f"未知 ParsedSource: {type(parsed).__name__}",
            stage="chunk",
            code="unsupported_parsed_source",
        )

    async def _chunk_document(
        self, parsed: ParsedDocument, options: ChunkOptions
    ) -> list[ChunkItem]:
        doc = InputDocument(content=parsed.body, is_markdown=True, metadata=dict(parsed.metadata))
        blocks = [
            StructuredBlock(
                block_id=block.block_key,
                block_type=BlockType(block.block_type.upper()),
                raw_content=block.raw_content if block.raw_content is not None else block.content,
                heading=block.heading,
                start_index=block.start_index,
                end_index=block.end_index,
                metadata=dict(block.metadata),
            )
            for block in parsed.blocks
        ]
        if parsed.table_structure is not None:
            blocks = project_table_record_groups(blocks, parsed.table_structure)
        if options.strategy == "heading_strict":
            return self._chunk_document_heading_strict(self._heading_strict_blocks(blocks), options)

        section_max_tokens = max(32, min(512, options.max_tokens // 4))
        sections = await MarkdownArticleSectionBuilder(
            section_max_tokens=section_max_tokens,
        ).build_sections(doc, blocks)
        drafts = MarkdownSourceChunkAssembler(
            source_chunk_max_tokens=options.max_tokens,
        ).assemble_chunks(doc, sections)
        estimator = TokenizerTokenEstimator()
        items: list[ChunkItem] = []
        for draft in drafts:
            if len(draft.content.strip()) < options.min_content_length and not draft.metadata.get(
                "keep_if_short", False
            ):
                continue
            # 从 pipeline 产出的 SectionDraft 构建 SectionItem 列表
            section_items: list[SectionItem] = []
            for order in draft.section_order_indices:
                if order >= len(sections):
                    continue
                sec = sections[order]
                section_items.append(
                    SectionItem(
                        order_index=sec.order_index,
                        render_group_index=sec.render_group_index,
                        heading=sec.heading,
                        content=sec.content,
                        raw_content=sec.raw_content,
                        section_type=sec.section_type,
                        metadata=dict(sec.metadata),
                    )
                )
            section_refs = []
            for order in draft.section_order_indices:
                if order >= len(sections):
                    continue
                section_metadata = sections[order].metadata
                block_key = str(
                    section_metadata.get("reference_key")
                    or section_metadata.get("block_id")
                    or f"section-{order}"
                )
                section_refs.append(
                    SourceReference(
                        reference_type="block",
                        key=block_key,
                        order=order,
                        metadata={
                            key: section_metadata[key]
                            for key in (
                                "record_group_id",
                                "table_index",
                                "cell_range",
                                "identity_cells",
                                "sheet_name",
                            )
                            if key in section_metadata
                        },
                    )
                )
            content = draft.content
            # 原子语义块（表格/图片/代码/公式）不参与 overlap，避免边界插入上一块尾巴。
            no_overlap_types = {
                BlockType.TABLE.value,
                BlockType.IMAGE.value,
                BlockType.CODE.value,
                BlockType.FORMULA.value,
            }
            if (
                options.strategy == "overlap"
                and items
                and options.overlap_tokens
                and draft.chunk_type not in no_overlap_types
                and items[-1].chunk_type not in no_overlap_types
            ):
                overlap = self._tail_by_tokens(items[-1].content, options.overlap_tokens, estimator)
                content = f"{overlap}\n{content}" if overlap else content
            items.append(
                ChunkItem(
                    client_key=f"chunk-{len(items)}",
                    rank=len(items),
                    heading=draft.heading,
                    content=content,
                    raw_content=draft.raw_content,
                    chunk_type=draft.chunk_type,
                    references=tuple(section_refs),
                    sections=tuple(section_items),
                    token_count=estimator.estimate_tokens(content),
                    metadata=draft.metadata,
                )
            )
        return items

    @staticmethod
    def _heading_strict_blocks(blocks: list[StructuredBlock]) -> list[StructuredBlock]:
        """Keep legacy table-in-heading behavior while preserving IMAGE blocks.

        The table-aware parser emits TABLE/CODE/FORMULA blocks so normal
        chunking can keep their provenance and boundaries. ``heading_strict``
        historically viewed a whole heading region as one text block, so merge
        TABLE/CODE/FORMULA blocks back into the adjacent TEXT block for this
        strategy only. IMAGE blocks stay separate so their enriched semantic
        text and references are retained.
        """
        normalized: list[StructuredBlock] = []
        pending: StructuredBlock | None = None

        def starts_new_heading_region(block: StructuredBlock) -> bool:
            """块自带标题行时视为新标题区,即使标题文本与上一块相同也不合并。"""
            return block.raw_content.lstrip().startswith("#")

        def flush() -> None:
            nonlocal pending
            if pending is not None:
                normalized.append(pending)
                pending = None

        def merge_text(block: StructuredBlock, *, contains_table: bool = False) -> None:
            nonlocal pending
            if pending is None:
                pending = block
                return
            metadata = dict(pending.metadata)
            merged_ids = list(metadata.get("heading_strict_merged_block_ids", [pending.block_id]))
            merged_ids.append(block.block_id)
            metadata["heading_strict_merged_block_ids"] = merged_ids
            if contains_table or metadata.get("contains_table"):
                metadata["contains_table"] = True
            pending = StructuredBlock(
                block_id=pending.block_id,
                block_type=BlockType.TEXT,
                raw_content=f"{pending.raw_content}{block.raw_content}",
                heading=pending.heading,
                start_index=pending.start_index,
                end_index=block.end_index,
                metadata=metadata,
            )

        for block in blocks:
            if block.block_type == BlockType.IMAGE:
                flush()
                normalized.append(block)
                continue

            if block.block_type in {BlockType.TABLE, BlockType.CODE, BlockType.FORMULA}:
                as_text = StructuredBlock(
                    block_id=block.block_id,
                    block_type=BlockType.TEXT,
                    raw_content=block.raw_content,
                    heading=block.heading,
                    start_index=block.start_index,
                    end_index=block.end_index,
                    metadata=dict(block.metadata),
                )
                if pending is not None and pending.block_type == BlockType.TEXT:
                    merge_text(
                        as_text,
                        contains_table=(block.block_type == BlockType.TABLE),
                    )
                else:
                    flush()
                    pending = as_text
                continue

            if (
                pending is not None
                and pending.block_type == BlockType.TEXT
                and block.block_type == BlockType.TEXT
                and pending.heading == block.heading
                and not starts_new_heading_region(block)
            ):
                merge_text(block)
            else:
                flush()
                pending = block

        flush()
        return normalized

    @staticmethod
    def _chunk_document_heading_strict(
        blocks: list[StructuredBlock],
        options: ChunkOptions,
    ) -> list[ChunkItem]:
        """按每个原始 Markdown 标题 block 切块，不做句子或 token 级二次切分。"""
        estimator = TokenizerTokenEstimator()
        drafts = MarkdownHeadingStrictChunker().split_blocks(blocks)
        items: list[ChunkItem] = []
        for draft in drafts:
            if len(draft.content.strip()) < options.min_content_length and not draft.metadata.get(
                "keep_if_short", False
            ):
                continue
            block_order = draft.section_order_indices[0]
            block = blocks[block_order]
            sections = (
                (
                    SectionItem(
                        order_index=block_order,
                        render_group_index=block_order,
                        heading=block.heading,
                        content=draft.content,
                        raw_content=draft.raw_content,
                        section_type=draft.chunk_type,
                        metadata=dict(block.metadata),
                    ),
                )
                if block.block_type == BlockType.IMAGE
                else ()
            )
            items.append(
                ChunkItem(
                    client_key=f"chunk-{len(items)}",
                    rank=len(items),
                    heading=draft.heading,
                    content=draft.content,
                    raw_content=draft.raw_content,
                    chunk_type=draft.chunk_type,
                    references=(
                        SourceReference(
                            reference_type="block",
                            key=block.block_id,
                            order=block_order,
                        ),
                    ),
                    sections=sections,
                    token_count=estimator.estimate_tokens(draft.content),
                    metadata=draft.metadata,
                )
            )
        return items

    def _chunk_units(
        self,
        parsed: ParsedConversation | ParsedRecording,
        options: ChunkOptions,
    ) -> list[ChunkItem]:
        estimator = TokenizerTokenEstimator()
        strategy = options.strategy
        if strategy == "standard":
            strategy = "token_window" if isinstance(parsed, ParsedRecording) else "time_window"
        if strategy not in {"time_window", "token_window", "overlap"}:
            raise StageInputError(
                f"会话/录音来源不支持 {options.strategy}",
                stage="chunk",
                code="invalid_chunk_strategy",
            )

        groups: list[list[tuple[int, ParsedUnit, str]]] = []
        current: list[tuple[int, ParsedUnit, str]] = []
        current_tokens = 0
        window_start: datetime | None = None
        for order, unit in enumerate(parsed.units):
            rendered = self._render_unit(unit)
            unit_tokens = estimator.estimate_tokens(rendered)
            split_for_tokens = bool(current and current_tokens + unit_tokens > options.max_tokens)
            split_for_time = False
            if strategy == "time_window" and current and window_start and unit.timestamp:
                split_for_time = (
                    unit.timestamp - window_start
                ).total_seconds() > options.time_window_seconds
            if split_for_tokens or split_for_time:
                groups.append(current)
                current = []
                current_tokens = 0
                window_start = None
            if not current:
                window_start = unit.timestamp
            current.append((order, unit, rendered))
            current_tokens += unit_tokens
        if current:
            groups.append(current)

        items: list[ChunkItem] = []
        for group in groups:
            content = "\n".join(rendered for _, _, rendered in group).strip()
            if len(content) < options.min_content_length:
                continue
            refs = tuple(
                SourceReference(
                    reference_type="time_range" if isinstance(parsed, ParsedRecording) else "unit",
                    key=unit.unit_key,
                    order=order,
                    start_ms=unit.start_ms,
                    end_ms=unit.end_ms,
                    metadata={
                        "external_id": unit.external_id,
                        "speaker": unit.speaker,
                        "role": unit.role,
                    },
                )
                for order, unit, _ in group
            )
            items.append(
                ChunkItem(
                    client_key=f"chunk-{len(items)}",
                    rank=len(items),
                    content=content,
                    raw_content="\n".join(
                        (unit.raw_content if unit.raw_content is not None else unit.content)
                        for _, unit, _ in group
                    ),
                    chunk_type="RECORDING" if isinstance(parsed, ParsedRecording) else "CHAT",
                    references=refs,
                    token_count=estimator.estimate_tokens(content),
                    metadata={
                        "strategy": strategy,
                        "start_time": self._iso(group[0][1].timestamp),
                        "end_time": self._iso(group[-1][1].timestamp),
                        "start_ms": group[0][1].start_ms,
                        "end_ms": group[-1][1].end_ms,
                    },
                )
            )
        return items

    @staticmethod
    def _render_unit(unit: ParsedUnit) -> str:
        label = unit.speaker or unit.role
        return f"{label}: {unit.content}" if label else unit.content

    @staticmethod
    def _iso(value: datetime | None) -> str | None:
        return value.isoformat() if value else None

    @staticmethod
    def _tail_by_tokens(text: str, max_tokens: int, estimator: TokenizerTokenEstimator) -> str:
        low, high = 0, len(text)
        best = len(text)
        while low <= high:
            mid = (low + high) // 2
            candidate = text[mid:]
            if estimator.estimate_tokens(candidate) <= max_tokens:
                best = mid
                high = mid - 1
            else:
                low = mid + 1
        return text[best:]

    @staticmethod
    def _source_version(parsed: ParsedSource) -> str:
        if isinstance(parsed, ParsedDocument):
            content: object = {
                "kind": parsed.kind,
                "body": parsed.body,
                "title": parsed.title,
                "blocks": [block.model_dump(mode="json") for block in parsed.blocks],
                "metadata": parsed.metadata,
                "table_structure": (
                    parsed.table_structure.model_dump(mode="json")
                    if parsed.table_structure is not None
                    else None
                ),
            }
        else:
            content = {
                "kind": parsed.kind,
                "units": [unit.model_dump(mode="json") for unit in parsed.units],
                "metadata": parsed.metadata,
            }
        return content_hash(content)
