"""Spreadsheet-aware parsing internals."""

from .alignment import align_table_structure
from .contracts import CellRange, Workbook, WorkbookCell, WorkbookSheet
from .reader import read_workbook
from .structure import infer_table_structure

__all__ = [
    "CellRange",
    "Workbook",
    "WorkbookCell",
    "WorkbookSheet",
    "align_table_structure",
    "infer_table_structure",
    "read_workbook",
]
