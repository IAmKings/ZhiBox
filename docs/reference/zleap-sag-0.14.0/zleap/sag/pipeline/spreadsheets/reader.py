"""Bounded XLS/XLSX readers that never execute workbook code or external links."""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal, InvalidOperation
from hashlib import sha256
from pathlib import Path
from typing import Any

from zleap.sag.pipeline.errors import SourceReadError

from .contracts import CellRange, SpreadsheetValue, Workbook, WorkbookCell, WorkbookSheet

_SUPPORTED_SUFFIXES = frozenset({".xls", ".xlsx"})
_MAX_SHEETS = 256
_MAX_TOTAL_CELLS = 2_000_000


def read_workbook(path: Path, *, expected_sha256: str) -> Workbook:
    """Read one immutable workbook into a format-neutral bounded model."""

    source = Path(path)
    suffix = source.suffix.casefold()
    if suffix not in _SUPPORTED_SUFFIXES:
        raise SourceReadError(
            f"unsupported spreadsheet format: {suffix or '<none>'}",
            stage="parse",
            code="spreadsheet_format_unsupported",
        )
    try:
        content = source.read_bytes()
    except OSError as exc:
        raise SourceReadError(
            f"failed to read spreadsheet: {source.name}",
            stage="parse",
            code="spreadsheet_read_failed",
            cause_summary=type(exc).__name__,
        ) from exc
    digest = sha256(content).hexdigest()
    if digest != expected_sha256:
        raise SourceReadError(
            "spreadsheet original digest does not match the request",
            stage="parse",
            code="original_digest_mismatch",
        )
    try:
        sheets = _read_xls(source) if suffix == ".xls" else _read_xlsx(source)
    except SourceReadError:
        raise
    except Exception as exc:
        raise SourceReadError(
            f"failed to parse spreadsheet: {source.name}",
            stage="parse",
            code="spreadsheet_read_failed",
            cause_summary=type(exc).__name__,
        ) from exc
    _validate_workbook_bounds(sheets)
    return Workbook(sha256=digest, sheets=sheets)


def _read_xls(path: Path) -> tuple[WorkbookSheet, ...]:
    try:
        import xlrd
    except (ImportError, ModuleNotFoundError) as exc:
        raise _missing_dependency("xlrd", exc) from exc

    book = xlrd.open_workbook(path, formatting_info=True, on_demand=True)
    sheets: list[WorkbookSheet] = []
    try:
        for sheet in book.sheets():
            rows: list[tuple[WorkbookCell, ...]] = []
            for row_index in range(sheet.nrows):
                row: list[WorkbookCell] = []
                for column_index in range(sheet.ncols):
                    cell = sheet.cell(row_index, column_index)
                    raw = _xls_value(cell, book.datemode, xlrd)
                    number_format = _xls_number_format(book, sheet, row_index, column_index)
                    row.append(
                        WorkbookCell(
                            row=row_index + 1,
                            column=column_index + 1,
                            raw_value=raw,
                            display_value=_display_value(raw, number_format),
                        )
                    )
                rows.append(tuple(row))
            merged = tuple(
                CellRange(
                    row_start=row_start + 1,
                    row_end=row_end,
                    column_start=column_start + 1,
                    column_end=column_end,
                )
                for row_start, row_end, column_start, column_end in sheet.merged_cells
            )
            sheets.append(WorkbookSheet(name=sheet.name, rows=tuple(rows), merged_ranges=merged))
    finally:
        book.release_resources()
    return tuple(sheets)


def _read_xlsx(path: Path) -> tuple[WorkbookSheet, ...]:
    try:
        from openpyxl import load_workbook
    except (ImportError, ModuleNotFoundError) as exc:
        raise _missing_dependency("openpyxl", exc) from exc

    book = load_workbook(path, read_only=False, data_only=True, keep_links=False)
    try:
        sheets: list[WorkbookSheet] = []
        for sheet in book.worksheets:
            rows: list[tuple[WorkbookCell, ...]] = []
            for values in sheet.iter_rows(
                min_row=1,
                max_row=sheet.max_row,
                min_col=1,
                max_col=sheet.max_column,
            ):
                rows.append(
                    tuple(
                        WorkbookCell(
                            row=cell.row,
                            column=cell.column,
                            raw_value=_normalize_value(cell.value),
                            display_value=_display_value(
                                _normalize_value(cell.value), cell.number_format
                            ),
                        )
                        for cell in values
                    )
                )
            merged = tuple(
                CellRange(
                    row_start=item.min_row,
                    row_end=item.max_row,
                    column_start=item.min_col,
                    column_end=item.max_col,
                )
                for item in sheet.merged_cells.ranges
            )
            sheets.append(WorkbookSheet(name=sheet.title, rows=tuple(rows), merged_ranges=merged))
        return tuple(sheets)
    finally:
        book.close()


def _xls_value(cell: Any, datemode: int, xlrd: Any) -> SpreadsheetValue:
    if cell.ctype in {xlrd.XL_CELL_EMPTY, xlrd.XL_CELL_BLANK, xlrd.XL_CELL_ERROR}:
        return None
    if cell.ctype == xlrd.XL_CELL_BOOLEAN:
        return bool(cell.value)
    if cell.ctype == xlrd.XL_CELL_DATE:
        return xlrd.xldate_as_datetime(cell.value, datemode)
    return _normalize_value(cell.value)


def _xls_number_format(book: Any, sheet: Any, row: int, column: int) -> str:
    xf_index = sheet.cell_xf_index(row, column)
    format_key = book.xf_list[xf_index].format_key
    item = book.format_map.get(format_key)
    return "General" if item is None else item.format_str


def _normalize_value(value: Any) -> SpreadsheetValue:
    if value is None or isinstance(value, str | bool | datetime):
        return value
    if isinstance(value, Decimal):
        return value
    if isinstance(value, int) and not isinstance(value, bool):
        return Decimal(value)
    if isinstance(value, float):
        return Decimal(str(value))
    return str(value)


def _display_value(value: SpreadsheetValue, number_format: str | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, datetime):
        return value.isoformat(sep=" ")
    if not isinstance(value, Decimal):
        return value
    places = _decimal_places(number_format or "General")
    if places is None:
        display = format(value, "f")
        return display.rstrip("0").rstrip(".") if "." in display else display
    try:
        quantum = Decimal(1).scaleb(-places)
        return f"{value.quantize(quantum):.{places}f}"
    except InvalidOperation:
        return str(value)


def _decimal_places(number_format: str) -> int | None:
    primary = number_format.split(";", 1)[0]
    if "%" in primary or "E" in primary.upper():
        return None
    primary = primary.replace('"', "")
    if "." not in primary:
        return 0 if "0" in primary and primary.casefold() != "general" else None
    decimals = primary.split(".", 1)[1]
    count = 0
    for character in decimals:
        if character in {"0", "#"}:
            count += 1
        else:
            break
    return count


def _validate_workbook_bounds(sheets: tuple[WorkbookSheet, ...]) -> None:
    if len(sheets) > _MAX_SHEETS:
        raise SourceReadError(
            "spreadsheet exceeds the supported sheet count",
            stage="parse",
            code="spreadsheet_limit_exceeded",
        )
    total_cells = sum(sum(len(row) for row in sheet.rows) for sheet in sheets)
    if total_cells > _MAX_TOTAL_CELLS:
        raise SourceReadError(
            "spreadsheet exceeds the supported cell count",
            stage="parse",
            code="spreadsheet_limit_exceeded",
        )


def _missing_dependency(name: str, exc: BaseException) -> SourceReadError:
    return SourceReadError(
        f"reading spreadsheets requires optional dependency {name}; install zleap-sag[tables]",
        stage="parse",
        code="spreadsheet_dependency_missing",
        cause_summary=type(exc).__name__,
    )


__all__ = ["read_workbook"]
