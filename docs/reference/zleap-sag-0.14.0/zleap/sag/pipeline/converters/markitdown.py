"""MarkItDown-backed spreadsheet conversion.

The optional dependency is imported only when a supported spreadsheet is read,
so the core SAG package stays lightweight for Markdown-only users.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from importlib import metadata
from pathlib import Path
from typing import Any

from zleap.sag.pipeline.errors import SourceReadError


@dataclass(frozen=True, slots=True)
class ConvertedMarkdown:
    """Normalized Markdown and auditable converter metadata."""

    content: str
    source_format: str
    converter: str
    converter_version: str


class MarkItDownConverter:
    """Convert CSV/XLSX files to Markdown without blocking the event loop."""

    name = "markitdown"
    supported_formats = frozenset({"csv", "xlsx"})

    async def convert(self, path: Path, source_format: str) -> ConvertedMarkdown:
        normalized_format = source_format.lower().lstrip(".")
        if normalized_format not in self.supported_formats:
            raise SourceReadError(
                f"MarkItDown 不支持格式: {normalized_format}",
                stage="parse",
                code="converter_format_unsupported",
            )
        return await asyncio.to_thread(self._convert_sync, path, normalized_format)

    def _convert_sync(self, path: Path, source_format: str) -> ConvertedMarkdown:
        markitdown_type = self._load_markitdown()
        try:
            result = markitdown_type().convert(str(path))
        except SourceReadError:
            raise
        except Exception as exc:
            raise SourceReadError(
                f"MarkItDown 转换 {path.name} 失败",
                stage="parse",
                code="converter_failed",
                cause_summary=type(exc).__name__,
            ) from exc

        content = getattr(result, "text_content", None)
        if not isinstance(content, str):
            raise SourceReadError(
                "MarkItDown 返回结果缺少 text_content",
                stage="parse",
                code="converter_invalid_result",
            )

        try:
            version = metadata.version("markitdown")
        except metadata.PackageNotFoundError:
            version = "unknown"
        return ConvertedMarkdown(
            content=content,
            source_format=source_format,
            converter=self.name,
            converter_version=version,
        )

    @staticmethod
    def _load_markitdown() -> type[Any]:
        try:
            from markitdown import MarkItDown
        except (ImportError, ModuleNotFoundError) as exc:
            raise SourceReadError(
                "读取 CSV/XLSX 需要可选依赖；请安装 zleap-sag[tables]",
                stage="parse",
                code="converter_dependency_missing",
                cause_summary=type(exc).__name__,
            ) from exc
        return MarkItDown
