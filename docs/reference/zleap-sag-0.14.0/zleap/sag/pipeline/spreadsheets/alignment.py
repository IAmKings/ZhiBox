"""Validate inferred workbook groups against canonical Markdown tables."""

from __future__ import annotations

import re
from collections import defaultdict

from zleap.sag.modules.load.chunking import MarkdownBlockParser, MarkdownInputNormalizer
from zleap.sag.modules.load.chunking.types import BlockType
from zleap.sag.pipeline.contracts import TableRecordGroup, TableStructure


def align_table_structure(markdown: str, structure: TableStructure) -> TableStructure:
    """Keep only record groups whose schema and identity match canonical Markdown."""

    doc = MarkdownInputNormalizer().normalize(markdown)
    blocks = MarkdownBlockParser().parse_blocks(doc)
    tables = [block for block in blocks if block.block_type == BlockType.TABLE]
    rows_by_table = [_logical_rows(block.raw_content) for block in tables]
    original_by_table: dict[int, list[TableRecordGroup]] = defaultdict(list)
    valid: list[TableRecordGroup] = []
    for group in structure.groups:
        original_by_table[group.table_index].append(group)
        if group.table_index >= len(rows_by_table):
            continue
        rows = rows_by_table[group.table_index]
        if _matches(group, rows):
            valid.append(group)

    valid_tables = {group.table_index for group in valid}
    suppressed = set(structure.suppressed_table_indices)
    suppressed.update(
        table_index for table_index in original_by_table if table_index not in valid_tables
    )
    return TableStructure(
        markdown_sha256=structure.markdown_sha256,
        groups=tuple(valid),
        suppressed_table_indices=tuple(suppressed),
    )


def _logical_rows(markdown_table: str) -> list[list[str]]:
    lines = markdown_table.splitlines()
    if len(lines) < 2:
        return []
    rows: list[list[str]] = []
    for line in (lines[0], *lines[2:]):
        cells = MarkdownBlockParser._split_table_cells(line)
        if cells is None:
            return []
        rows.append([_normalize(value) for value in cells])
    return rows


def _matches(group: TableRecordGroup, rows: list[list[str]]) -> bool:
    cell_range = group.cell_range
    if (
        not rows
        or cell_range.row_end > len(rows)
        or any(cell_range.column_end > len(row) for row in rows)
    ):
        return False
    header_row = group.metadata.get("header_row")
    header_values = group.metadata.get("header_values")
    if isinstance(header_row, int) and isinstance(header_values, list):
        actual = rows[header_row - 1][cell_range.column_start - 1 : cell_range.column_end]
        if actual != [_normalize(str(value)) for value in header_values]:
            return False
    identity_value = group.metadata.get("identity_value")
    if identity_value is None:
        return True
    return any(
        _normalize(str(identity_value)) == rows[row - 1][column - 1]
        for reference in group.identity_cells
        for row, column in [_a1_coordinates(reference)]
    )


def _a1_coordinates(reference: str) -> tuple[int, int]:
    letters = ""
    digits = ""
    for char in reference:
        if char.isalpha():
            letters += char
        else:
            digits += char
    column = 0
    for char in letters:
        column = column * 26 + ord(char.upper()) - ord("A") + 1
    return int(digits), column


def _normalize(value: str) -> str:
    return re.sub(r"\\([\\`*_{}\[\]()#+.!|>\-])", r"\1", value.strip())


__all__ = ["align_table_structure"]
