"""Parse stage: portable input I/O and normalization, without SAG writes."""

from __future__ import annotations

import asyncio
import re
from hashlib import sha256
from html.parser import HTMLParser
from pathlib import Path
from typing import Any

from zleap.sag._safe_error import safe_error_summary
from zleap.sag.exceptions import InvalidInputError
from zleap.sag.modules.load.chunking import MarkdownBlockParser, MarkdownInputNormalizer
from zleap.sag.modules.load.chunking.parser.html_table import (
    CELL_SOFT_BREAK_TAGS,
    CELL_TAGS,
    ROW_TAG,
    TABLE_TAG,
    HtmlTableAccumulator,
    render_gfm_table,
)
from zleap.sag.pipeline.context import PipelineContext
from zleap.sag.pipeline.contracts import (
    ArticleSource,
    ConversationSource,
    DocumentSource,
    ExternalSource,
    FileSource,
    ParsedBlock,
    ParsedConversation,
    ParsedDocument,
    ParsedRecording,
    ParsedSource,
    ParsedUnit,
    RecordingSource,
    SourceDescriptor,
    SourceInput,
    SourceType,
    TableStructure,
    TextSource,
)
from zleap.sag.pipeline.converters import MarkItDownConverter
from zleap.sag.pipeline.errors import (
    ParseError,
    PipelineCancelledError,
    SourceReadError,
    StageInputError,
    UnsupportedSourceTypeError,
)
from zleap.sag.pipeline.events import (
    StageEvent,
    StageEventType,
    StageName,
    notify_observer,
)
from zleap.sag.pipeline.options import ParseOptions
from zleap.sag.pipeline.spreadsheets import (
    align_table_structure,
    infer_table_structure,
    read_workbook,
)


class _HTMLToMarkdown(HTMLParser):
    """Minimal deterministic HTML normalizer; host converters handle rich documents."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._parts: list[str] = []
        self._heading: int | None = None
        self._table: HtmlTableAccumulator | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        _ = attrs
        if tag == TABLE_TAG:
            if self._table is None:
                self._table = HtmlTableAccumulator()
                self._parts.append("\n")
            # 嵌套表格拍平进当前单元格，不另起状态机。
            self._table.open_table()
            return
        if self._table is not None:
            if self._table.depth == 1:
                if tag == ROW_TAG:
                    self._table.start_row()
                elif tag in CELL_TAGS:
                    self._table.start_cell()
                elif tag in CELL_SOFT_BREAK_TAGS:
                    self._table.add_soft_break()
            return
        if tag in {"p", "div", "section", "article", "br", "li", "tr"}:
            self._parts.append("\n")
        if len(tag) == 2 and tag.startswith("h") and tag[1].isdigit():
            self._heading = int(tag[1])
            self._parts.append(f"\n{'#' * self._heading} ")

    def handle_endtag(self, tag: str) -> None:
        if self._table is not None:
            if tag == TABLE_TAG:
                if self._table.close_table():
                    self._flush_table()
                return
            if self._table.depth == 1:
                if tag in CELL_TAGS:
                    self._table.end_cell()
                elif tag == ROW_TAG:
                    self._table.end_row()
            return
        if tag in {TABLE_TAG, *CELL_TAGS}:
            # 表格外的孤立闭合标签：无上下文可结算，忽略。
            return
        if tag in {"p", "div", "section", "article", "li", ROW_TAG} or (
            len(tag) == 2 and tag.startswith("h")
        ):
            self._parts.append("\n")
        if self._heading is not None and tag == f"h{self._heading}":
            self._heading = None

    def handle_data(self, data: str) -> None:
        if self._table is not None:
            self._table.add_text(data)
        else:
            self._parts.append(data)

    def _flush_table(self) -> None:
        """把累积的表格渲染进输出，并退出表格状态。"""
        if self._table is None:
            return
        rows = self._table.rows()
        self._table = None
        if rows:
            self._parts.append(render_gfm_table(rows))

    def text(self) -> str:
        # 未闭合的 <table>（截断页面、畸形 HTML）在此兜底 flush，
        # 否则表格与其后的全部正文都会被静默丢弃。
        self._flush_table()
        value = "".join(self._parts)
        value = re.sub(r"[ \t]+\n", "\n", value)
        value = re.sub(r"\n{3,}", "\n\n", value)
        return value.strip()


class SourceReaderRegistry:
    def __init__(self) -> None:
        self._readers: dict[str, Any] = {}

    def register(self, name: str, reader: Any) -> None:
        if not name.strip():
            raise InvalidInputError("reader name 不能为空")
        self._readers[name] = reader

    def get(self, name: str) -> Any:
        try:
            return self._readers[name]
        except KeyError as exc:
            raise SourceReadError(
                f"未注册来源读取器: {name}",
                stage="parse",
                code="source_reader_not_found",
            ) from exc


class ParseStage:
    async def run(
        self,
        source: SourceInput,
        options: ParseOptions | None = None,
        *,
        context: PipelineContext | None = None,
    ) -> ParsedSource:
        options = options or ParseOptions()
        context = context or PipelineContext()
        await context.raise_if_cancelled(StageName.PARSE)
        await notify_observer(
            context.observer,
            StageEvent(run_id=context.run_id, stage=StageName.PARSE, type=StageEventType.STARTED),
        )
        try:
            result = await self._parse(source, options, context)
        except (
            ParseError,
            PipelineCancelledError,
            SourceReadError,
            StageInputError,
            UnsupportedSourceTypeError,
        ) as exc:
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.PARSE,
                    type=StageEventType.FAILED,
                    error=str(exc),
                ),
            )
            raise
        except Exception as exc:
            error = ParseError(
                safe_error_summary(exc, label="解析失败"),
                stage="parse",
                code="parse_failed",
                run_id=context.run_id,
                cause_summary=type(exc).__name__,
            )
            await notify_observer(
                context.observer,
                StageEvent(
                    run_id=context.run_id,
                    stage=StageName.PARSE,
                    type=StageEventType.FAILED,
                    error=str(error),
                ),
            )
            raise error from exc
        await notify_observer(
            context.observer,
            StageEvent(
                run_id=context.run_id,
                stage=StageName.PARSE,
                type=StageEventType.SUCCEEDED,
            ),
        )
        return result

    async def _parse(
        self,
        source: SourceInput,
        options: ParseOptions,
        context: PipelineContext,
    ) -> ParsedSource:
        if isinstance(source, ExternalSource) or (
            isinstance(source, ArticleSource) and source.content is None
        ):
            reader_name = options.reader or getattr(source, "reader", None)
            if not reader_name:
                source_type = getattr(source, "source_type", SourceType.ARTICLE)
                reader_name = str(getattr(source_type, "value", source_type))
            registry = context.source_readers
            if registry is None:
                raise SourceReadError(
                    "locator 来源需要注入 SourceReaderRegistry",
                    stage="parse",
                    code="source_reader_required",
                    run_id=context.run_id,
                )
            reader = registry.get(reader_name)
            payload = await reader.read(source, options)
            if isinstance(payload, ParsedDocument):
                return await self._enrich_document_images(payload, None, context)
            if isinstance(payload, ParsedConversation | ParsedRecording):
                return payload
            if isinstance(
                payload,
                TextSource
                | FileSource
                | DocumentSource
                | ArticleSource
                | ConversationSource
                | RecordingSource
                | ExternalSource,
            ):
                if payload is source:
                    raise SourceReadError(
                        "SourceReader 返回了原 locator，无法继续解析",
                        stage="parse",
                        code="recursive_reader_result",
                        run_id=context.run_id,
                    )
                return await self._parse(payload, options, context)
            else:
                raise SourceReadError(
                    "SourceReader 必须返回 portable SourceInput 或 ParsedSource",
                    stage="parse",
                    code="invalid_reader_result",
                    run_id=context.run_id,
                )

        if isinstance(source, DocumentSource):
            descriptor = self._descriptor(source.descriptor, SourceType.ARTICLE)
            table_structure = None
            original_path: Path | None = None
            if source.original_file is not None:
                assert source.original_sha256 is not None
                original_path = Path(source.original_file.path)
                original_format = self._resolve_format(
                    source.original_file.format_hint, original_path.suffix
                )
                if original_format in {"xls", "xlsx"}:
                    workbook = await asyncio.to_thread(
                        read_workbook,
                        original_path,
                        expected_sha256=source.original_sha256,
                    )
                    table_structure = infer_table_structure(
                        workbook,
                        markdown_sha256=sha256(source.content.encode("utf-8")).hexdigest(),
                    )
                    table_structure = align_table_structure(source.content, table_structure)
            parsed = self._parse_document(
                source.content,
                descriptor,
                self._resolve_format(options.format_hint or source.format_hint, ""),
                options,
                parse_images=context.image_enricher is not None,
                table_structure=table_structure,
                metadata={
                    "canonical_with_original": source.original_file is not None,
                    "original_sha256": source.original_sha256,
                },
            )
            return await self._enrich_document_images(parsed, original_path, context)

        if isinstance(source, FileSource):
            path = Path(source.path)
            file_format = self._resolve_format(
                options.format_hint or source.format_hint, path.suffix
            )
            if file_format not in {"markdown", "html", "text"}:
                converter_name = options.converter
                if converter_name is None and file_format in MarkItDownConverter.supported_formats:
                    converter_name = MarkItDownConverter.name
                if not converter_name:
                    raise UnsupportedSourceTypeError(
                        f"格式 {file_format} 需要宿主 converter/SourceReader",
                        stage="parse",
                        code="converter_required",
                        run_id=context.run_id,
                    )
                descriptor = self._descriptor(
                    source.descriptor,
                    SourceType.ARTICLE,
                    title=path.stem,
                    external_id=str(path),
                )
                if converter_name == MarkItDownConverter.name:
                    converted = await MarkItDownConverter().convert(path, file_format)
                    parsed = self._parse_document(
                        converted.content,
                        descriptor,
                        "markdown",
                        options,
                        source_path=path,
                        parse_images=context.image_enricher is not None,
                        metadata={
                            "original_format": converted.source_format,
                            "normalized_format": "markdown",
                            "converter": converted.converter,
                            "converter_version": converted.converter_version,
                        },
                    )
                    return await self._enrich_document_images(parsed, path, context)

                registry = context.source_readers
                if registry is None:
                    raise SourceReadError(
                        f"未注入 converter: {converter_name}",
                        stage="parse",
                        code="converter_required",
                        run_id=context.run_id,
                    )
                reader = registry.get(converter_name)
                payload = await reader.read(source, options)
                if isinstance(payload, ParsedDocument):
                    return await self._enrich_document_images(payload, path, context)
                if isinstance(payload, ParsedConversation | ParsedRecording):
                    return payload
                if isinstance(
                    payload,
                    TextSource
                    | FileSource
                    | DocumentSource
                    | ArticleSource
                    | ConversationSource
                    | RecordingSource
                    | ExternalSource,
                ):
                    return await self._parse(payload, options, context)
                raise SourceReadError(
                    "converter 必须返回 portable SourceInput 或 ParsedSource",
                    stage="parse",
                    code="invalid_reader_result",
                    run_id=context.run_id,
                )
            # 内建读取路径才要求普通文件；converter 路径让 converter 自行处理，
            # 因此不在此处拦截 FIFO/设备等非常规文件。
            if not path.is_file():
                raise SourceReadError(
                    f"文件不存在或不是普通文件: {path}",
                    stage="parse",
                    code="file_read_failed",
                    run_id=context.run_id,
                )
            try:
                content = path.read_text(encoding=options.encoding)
            except (OSError, UnicodeError) as exc:
                raise SourceReadError(
                    f"读取文件失败: {path}",
                    stage="parse",
                    code="file_read_failed",
                    run_id=context.run_id,
                    cause_summary=type(exc).__name__,
                ) from exc
            descriptor = self._descriptor(
                source.descriptor,
                SourceType.ARTICLE,
                title=path.stem,
                external_id=str(path),
            )
            parsed = self._parse_document(
                content,
                descriptor,
                file_format,
                options,
                source_path=path,
                parse_images=context.image_enricher is not None,
            )
            return await self._enrich_document_images(parsed, path, context)

        if isinstance(source, TextSource):
            descriptor = self._descriptor(source.descriptor, SourceType.TEXT)
            file_format = self._resolve_format(options.format_hint or source.format_hint, "")
            parsed = self._parse_document(
                source.text,
                descriptor,
                file_format,
                options,
                parse_images=context.image_enricher is not None,
            )
            return await self._enrich_document_images(parsed, None, context)

        if isinstance(source, ArticleSource):
            descriptor = self._descriptor(source.descriptor, SourceType.ARTICLE)
            file_format = self._resolve_format(options.format_hint or source.format_hint, "")
            parsed = self._parse_document(
                source.content or "",
                descriptor,
                file_format,
                options,
                parse_images=context.image_enricher is not None,
            )
            return await self._enrich_document_images(parsed, None, context)

        if isinstance(source, ConversationSource):
            descriptor = self._descriptor(source.descriptor, SourceType.CHAT)
            return ParsedConversation(
                descriptor=descriptor,
                units=self._normalize_units(source.units, recording=False),
            )

        if isinstance(source, RecordingSource):
            descriptor = self._descriptor(source.descriptor, SourceType.RECORDING)
            return ParsedRecording(
                descriptor=descriptor,
                units=self._normalize_units(source.units, recording=True),
            )

        raise UnsupportedSourceTypeError(
            f"不支持的来源输入: {type(source).__name__}",
            stage="parse",
            code="unsupported_source_type",
            run_id=context.run_id,
        )

    @staticmethod
    def _descriptor(
        descriptor: SourceDescriptor | None,
        expected: SourceType,
        *,
        title: str | None = None,
        external_id: str | None = None,
    ) -> SourceDescriptor:
        if descriptor is not None and descriptor.source_type != expected:
            raise StageInputError(
                f"descriptor.source_type={descriptor.source_type} 与输入类型 {expected.value} 冲突",
                stage="parse",
                code="source_descriptor_conflict",
            )
        if descriptor is not None:
            updates: dict[str, Any] = {}
            if descriptor.title is None and title:
                updates["title"] = title
            if descriptor.external_id is None and external_id:
                updates["external_id"] = external_id
            return descriptor.model_copy(update=updates)
        return SourceDescriptor(source_type=expected, title=title, external_id=external_id)

    @staticmethod
    def _resolve_format(hint: str | None, suffix: str) -> str:
        value = (hint or suffix.lstrip(".") or "markdown").lower()
        aliases = {
            "md": "markdown",
            "markdown": "markdown",
            "htm": "html",
            "html": "html",
            "txt": "text",
            "text": "text",
            "plain": "text",
        }
        return aliases.get(value, value)

    def _parse_document(
        self,
        content: str,
        descriptor: SourceDescriptor,
        file_format: str,
        options: ParseOptions,
        *,
        metadata: dict[str, Any] | None = None,
        source_path: Path | None = None,
        parse_images: bool = False,
        table_structure: TableStructure | None = None,
    ) -> ParsedDocument:
        warnings: list[str] = []
        if options.max_chars is not None and len(content) > options.max_chars:
            if options.error_policy == "raise":
                raise ParseError(
                    f"输入长度 {len(content)} 超过 max_chars={options.max_chars}",
                    stage="parse",
                    code="input_too_large",
                )
            content = content[: options.max_chars]
            warnings.append("input_truncated")

        normalized = content
        if file_format == "html":
            parser = _HTMLToMarkdown()
            parser.feed(content)
            normalized = parser.text()
        elif file_format not in {"markdown", "text"}:
            raise UnsupportedSourceTypeError(
                f"不支持的内建 parser: {file_format}",
                stage="parse",
                code="unsupported_parser",
            )

        document_metadata = {"original_format": file_format, **(metadata or {})}
        doc = MarkdownInputNormalizer().normalize(normalized, source_path=source_path)
        doc.metadata.update(document_metadata)
        internal_blocks = MarkdownBlockParser(
            parse_images=parse_images and file_format == "markdown"
        ).parse_blocks(doc)
        parse_warnings = doc.metadata.get("parse_warnings", [])
        if isinstance(parse_warnings, list):
            warnings.extend(str(warning) for warning in parse_warnings)
        blocks = tuple(
            ParsedBlock(
                block_key=block.block_id,
                block_type=block.block_type.value.lower(),
                heading=block.heading,
                content=block.raw_content,
                raw_content=block.raw_content if options.preserve_raw else None,
                start_index=block.start_index,
                end_index=block.end_index,
                metadata=block.metadata,
            )
            for block in internal_blocks
        )
        title = descriptor.title or next((block.heading for block in blocks if block.heading), None)
        return ParsedDocument(
            descriptor=descriptor,
            title=title,
            body=normalized,
            format=file_format,
            blocks=blocks,
            warnings=tuple(warnings),
            metadata=document_metadata,
            table_structure=table_structure,
        )

    @staticmethod
    async def _enrich_document_images(
        parsed: ParsedDocument,
        source_path: Path | None,
        context: PipelineContext,
    ) -> ParsedDocument:
        """在 Block 识别后执行可选 VLM enrichment，并汇总单图失败信息。"""
        enricher = context.image_enricher
        if enricher is None or not any(
            block.block_type.lower() == "image" for block in parsed.blocks
        ):
            return parsed

        # source_path 是 Markdown 文件路径，供 fetcher 安全解析同目录下的相对图片。
        blocks = await enricher.enrich_many(
            parsed.blocks,
            source_path=source_path,
            cancellation=context.cancellation,
        )
        warnings = list(parsed.warnings)
        failed = sum(
            1
            for block in blocks
            if isinstance(block.metadata.get("image_enrichment"), dict)
            and block.metadata["image_enrichment"].get("status") == "failed"
        )
        if failed:
            warnings.append(f"image_enrichment_failed:{failed}")
        metadata = dict(parsed.metadata)
        # 模型、Prompt 或 Schema 变化时，指纹会进一步影响 source/chunk version。
        metadata["image_enricher_fingerprint"] = enricher.fingerprint
        return parsed.model_copy(
            update={"blocks": blocks, "warnings": tuple(warnings), "metadata": metadata}
        )

    @staticmethod
    def _normalize_units(
        units: tuple[ParsedUnit, ...], *, recording: bool
    ) -> tuple[ParsedUnit, ...]:
        indexed = list(enumerate(units))
        if recording:
            indexed.sort(
                key=lambda pair: (
                    pair[1].start_ms is None,
                    pair[1].start_ms or 0,
                    pair[1].timestamp is None,
                    pair[1].timestamp,
                    pair[0],
                )
            )
        else:
            indexed.sort(
                key=lambda pair: (
                    pair[1].timestamp is None,
                    pair[1].timestamp,
                    pair[0],
                )
            )
        return tuple(unit for _, unit in indexed)
