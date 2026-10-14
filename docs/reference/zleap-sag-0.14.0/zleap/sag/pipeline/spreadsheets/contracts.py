"""Internal normalized workbook contracts used by spreadsheet-aware parsing."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

from zleap.sag.exceptions import ValidationError as SagValidationError

SpreadsheetValue = str | Decimal | bool | datetime | None


@dataclass(frozen=True, slots=True)
class CellRange:
    row_start: int
    row_end: int
    column_start: int
    column_end: int


@dataclass(frozen=True, slots=True)
class WorkbookCell:
    row: int
    column: int
    raw_value: SpreadsheetValue
    display_value: str
    formula: str | None = None


@dataclass(frozen=True, slots=True)
class WorkbookSheet:
    name: str
    rows: tuple[tuple[WorkbookCell, ...], ...]
    merged_ranges: tuple[CellRange, ...] = ()

    def cell(self, *, row: int, column: int) -> WorkbookCell:
        if row < 1 or column < 1:
            raise SagValidationError("workbook coordinates are one-based")
        try:
            return self.rows[row - 1][column - 1]
        except IndexError as exc:
            raise SagValidationError(
                f"cell outside worksheet bounds: row={row}, column={column}"
            ) from exc


@dataclass(frozen=True, slots=True)
class Workbook:
    sha256: str
    sheets: tuple[WorkbookSheet, ...]


__all__ = ["CellRange", "SpreadsheetValue", "Workbook", "WorkbookCell", "WorkbookSheet"]
