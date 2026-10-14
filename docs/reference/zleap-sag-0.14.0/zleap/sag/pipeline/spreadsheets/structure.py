"""Deterministic, product-neutral record boundary inference for workbooks."""

from __future__ import annotations

import math
from hashlib import sha256
from typing import cast

from pydantic import JsonValue

from zleap.sag.pipeline.contracts import TableCellRange, TableRecordGroup, TableStructure

from .contracts import Workbook


def infer_table_structure(workbook: Workbook, *, markdown_sha256: str) -> TableStructure:
    """Infer repeated text-schema bands and suppress ambiguous wide matrices."""

    groups: list[TableRecordGroup] = []
    suppressed: list[int] = []
    table_index = 0
    for sheet in workbook.sheets:
        rows = [[cell.display_value.strip() for cell in row] for row in sheet.rows]
        width = max((len(row) for row in rows), default=0)
        rows = [row + [""] * (width - len(row)) for row in rows]
        if not rows or not any(any(row) for row in rows):
            continue
        anchors = _repeated_header_anchors(rows)
        record_ranges = _record_ranges(rows, anchors)
        sheet_groups: list[TableRecordGroup] = []
        for row_index, column_start, column_end in anchors:
            row_start, row_end = record_ranges[(row_index, column_start, column_end)]
            cell_range = TableCellRange(
                row_start=row_start,
                row_end=row_end,
                column_start=column_start,
                column_end=column_end,
            )
            if any(cell_range.overlaps(existing.cell_range) for existing in sheet_groups):
                continue
            identity = _identity_cell(rows, cell_range, header_row=row_index)
            identity_cells = () if identity is None else (_a1(identity[0], identity[1]),)
            identity_value = None if identity is None else identity[2]
            cells = [
                cell
                for row in sheet.rows[cell_range.row_start - 1 : cell_range.row_end]
                for cell in row[cell_range.column_start - 1 : cell_range.column_end]
            ]
            display_rows = [
                [
                    cell.display_value
                    for cell in row[cell_range.column_start - 1 : cell_range.column_end]
                ]
                for row in sheet.rows[cell_range.row_start - 1 : cell_range.row_end]
            ]
            numeric_evidence = [
                {
                    "cell": _a1(cell.row, cell.column),
                    "raw": str(cell.raw_value),
                    "display": cell.display_value,
                }
                for cell in cells
                if cell.raw_value is not None and str(cell.raw_value) != cell.display_value
            ]
            fingerprint = (
                f"{table_index}:{sheet.name}:{cell_range.row_start}:{cell_range.row_end}:"
                f"{cell_range.column_start}:{cell_range.column_end}:{identity_value or ''}"
            )
            sheet_groups.append(
                TableRecordGroup(
                    record_group_id=f"rg-{sha256(fingerprint.encode('utf-8')).hexdigest()[:20]}",
                    table_index=table_index,
                    cell_range=cell_range,
                    identity_cells=identity_cells,
                    metadata={
                        "sheet_name": sheet.name,
                        "identity_value": identity_value,
                        "header_row": row_index,
                        "header_values": cast(
                            JsonValue, rows[row_index - 1][column_start - 1 : column_end]
                        ),
                        "display_rows": cast(JsonValue, display_rows),
                        "numeric_evidence": cast(JsonValue, numeric_evidence),
                    },
                )
            )
        if sheet_groups:
            groups.extend(sheet_groups)
        elif _looks_like_unresolved_matrix(rows):
            suppressed.append(table_index)
        table_index += 1
    groups.sort(
        key=lambda group: (
            group.table_index,
            group.cell_range.row_start,
            group.cell_range.column_start,
        )
    )
    return TableStructure(
        markdown_sha256=markdown_sha256,
        groups=tuple(groups),
        suppressed_table_indices=tuple(suppressed),
    )


def _looks_like_unresolved_matrix(rows: list[list[str]]) -> bool:
    width = max((len(row) for row in rows), default=0)
    if width < 8:
        return False
    for row in rows:
        bands: list[list[str]] = []
        column = 0
        while column < len(row):
            if not row[column]:
                column += 1
                continue
            start = column
            while column < len(row) and row[column]:
                column += 1
            values = row[start:column]
            if len(values) >= 2 and all(_header_token(value) for value in values):
                bands.append(values)
        if len(bands) >= 2:
            return True
    return False


def _repeated_header_anchors(rows: list[list[str]]) -> list[tuple[int, int, int]]:
    occurrences: dict[tuple[str, ...], list[tuple[int, int, int]]] = {}
    for row_index, row in enumerate(rows, start=1):
        column = 0
        while column < len(row):
            if not row[column]:
                column += 1
                continue
            start = column
            while column < len(row) and row[column]:
                column += 1
            values = row[start:column]
            if len(values) < 2 or not all(_header_token(value) for value in values):
                continue
            signature = tuple(value.casefold() for value in values)
            occurrences.setdefault(signature, []).append((row_index, start + 1, column))
    candidates = [
        occurrence
        for signature, signature_occurrences in occurrences.items()
        if len(signature_occurrences) >= 2
        for occurrence in signature_occurrences
        if len(signature) >= 2
    ]
    return sorted(set(candidates))


def _header_token(value: str) -> bool:
    if not value:
        return False
    try:
        numeric = float(value)
    except ValueError:
        return True
    return not math.isfinite(numeric)


def _record_start(rows: list[list[str]], header_row: int, start: int, end: int) -> int:
    record_start = header_row
    seen_nonempty = False
    for row_number in range(header_row - 1, max(0, header_row - 9), -1):
        nonempty = _band_has_value(rows[row_number - 1], start, end)
        if nonempty:
            record_start = row_number
            seen_nonempty = True
        elif seen_nonempty:
            break
    return record_start


def _record_end(rows: list[list[str]], header_row: int, start: int, end: int) -> int:
    record_end = header_row
    for row_number in range(header_row + 1, len(rows) + 1):
        if not _band_has_value(rows[row_number - 1], start, end):
            break
        record_end = row_number
    return record_end


def _record_ranges(
    rows: list[list[str]], anchors: list[tuple[int, int, int]]
) -> dict[tuple[int, int, int], tuple[int, int]]:
    by_columns: dict[tuple[int, int], list[tuple[int, int, int]]] = {}
    for anchor in anchors:
        by_columns.setdefault((anchor[1], anchor[2]), []).append(anchor)
    ranges: dict[tuple[int, int, int], tuple[int, int]] = {}
    for (column_start, column_end), column_anchors in by_columns.items():
        ordered = sorted(column_anchors)
        starts: list[int] = []
        prefix_height: int | None = None
        previous_header = 0
        for header_row, _, _ in ordered:
            detected = _record_start(rows, header_row, column_start, column_end)
            if prefix_height is None:
                start = detected
                prefix_height = header_row - start
            else:
                predicted = max(previous_header + 1, header_row - prefix_height)
                start = max(detected, predicted)
                prefix_height = header_row - start
            starts.append(start)
            previous_header = header_row
        for index, anchor in enumerate(ordered):
            header_row = anchor[0]
            detected_end = _record_end(rows, header_row, column_start, column_end)
            end = (
                min(detected_end, starts[index + 1] - 1)
                if index + 1 < len(starts)
                else detected_end
            )
            ranges[anchor] = (starts[index], end)
    return ranges


def _band_has_value(row: list[str], start: int, end: int) -> bool:
    return any(value for value in row[start - 1 : end])


def _identity_cell(
    rows: list[list[str]], cell_range: TableCellRange, *, header_row: int
) -> tuple[int, int, str] | None:
    candidates: list[tuple[int, int, str]] = []
    for row_number in range(cell_range.row_start, header_row):
        row = rows[row_number - 1]
        for column_number in range(cell_range.column_start, cell_range.column_end + 1):
            value = row[column_number - 1]
            if value and _header_token(value):
                candidates.append((row_number, column_number, value))
    return max(candidates, key=lambda item: (len(item[2]), -item[0], -item[1]), default=None)


def _a1(row: int, column: int) -> str:
    letters = ""
    current = column
    while current:
        current, remainder = divmod(current - 1, 26)
        letters = chr(ord("A") + remainder) + letters
    return f"{letters}{row}"


__all__ = ["infer_table_structure"]
