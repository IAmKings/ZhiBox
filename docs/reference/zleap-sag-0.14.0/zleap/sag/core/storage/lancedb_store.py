"""LanceDB 嵌入式向量后端。

设计(对齐 pgvector_store 的接入方式,零服务依赖):
- 每个逻辑 collection 映射为一张 LanceDB 表(本地目录,`lancedb.connect_async`)。
- 表结构按 ``index_schemas.INDEX_SCHEMAS`` 声明 + 首批文档推断:向量列维度取首个
  向量值长度(与 pgvector 一致);datetime 统一存 ISO-8601 字符串。
- 写入:``merge_insert("id")`` 幂等 upsert;id 取 _id/id/chunk_id/entity_id/event_id。
- kNN:cosine 距离,``_score = 1 - 距离``;filter_query(ES bool/term/terms/range/exists)
  翻译为 DataFusion WHERE 字符串。
- 词法:**真 BM25**(tantivy FTS),正确处理 ``multi_match``(含 ``title^3`` 权重,按 id
  合并取最高分)与嵌套 ``{"match": {"f": {"query": ...}}}``——这两点是 pgvector 降级
  路径的已知缺陷，此后端不复现；故声明 LEXICAL_SEARCH，生产搜索 Profile 本地可用。
"""

from __future__ import annotations

import asyncio
import json
import re
from collections.abc import Sequence
from datetime import date, datetime, timedelta
from typing import Any, TypeAlias, TypeGuard

# 顶层导入(而非惰性):lancedb + pyarrow 的原生库首次冷加载可达数秒,放在模块导入期完成,
# 避免落入 start() 健康检查 ping 的超时窗口内(冷装机首启曾因此误判连接失败)。
import lancedb
from lancedb.query import ColumnOrdering

from zleap.sag._safe_error import safe_error_type
from zleap.sag.core.storage.index_schemas import INDEX_SCHEMAS, cosine_distance_to_score
from zleap.sag.exceptions import StateError, StorageModeError
from zleap.sag.utils import get_logger

logger = get_logger("storage.lancedb")

_IDENT = re.compile(r"[^a-zA-Z0-9_]")

# 未在 INDEX_SCHEMAS 声明的索引/字段,向量列的判定启发(与 pgvector 一致)
_MIN_VECTOR_LEN = 8
# 批量 get 的单次 IN 列表上限:再大会让 SQL 谓词本身成为瓶颈
_GET_MANY_CHUNK = 1000
# 保留期必须显式固定，不能依赖 LanceDB 未来版本可能变化的默认值。
_SNAPSHOT_RETENTION = timedelta(days=7)


def _is_stale_snapshot_error(error: Exception) -> bool:
    """Return whether Lance is reading a data fragment pruned by another process."""

    message = str(error).casefold()
    return ".lance/data/" in message and ("not found" in message or "object at location" in message)


def _ident(name: str) -> str:
    """把索引/字段名规整为安全标识符。"""
    return _IDENT.sub("_", name)


VectorValue: TypeAlias = list[int | float] | tuple[int | float, ...]


def _is_vector(value: object) -> TypeGuard[VectorValue]:
    return (
        isinstance(value, (list, tuple))
        and len(value) > _MIN_VECTOR_LEN
        and all(isinstance(x, (int, float)) and not isinstance(x, bool) for x in value)
    )


def _sql_str(value: Any) -> str:
    """SQL 字符串字面量(单引号转义)。"""
    return "'" + str(value).replace("'", "''") + "'"


def _sql_literal(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return repr(value)
    if isinstance(value, (datetime, date)):
        return _sql_str(value.isoformat())
    return _sql_str(value)


def _doc_id(doc: dict[str, Any], explicit: str | None = None) -> str | None:
    did = (
        explicit
        or doc.get("_id")
        or doc.get("id")
        or doc.get("chunk_id")
        or doc.get("entity_id")
        or doc.get("event_id")
    )
    return str(did) if did is not None else None


class LanceDBStore:
    """LanceDB provider implementation owned by ``LanceDBVectorStore``."""

    def __init__(
        self,
        uri: str,
        *,
        storage_mode: str = "normal",
        embedding_dimensions: int | None = None,
    ) -> None:
        if storage_mode not in ("normal", "lite"):
            raise StorageModeError("storage_mode 必须是 normal 或 lite")
        self._uri = uri
        self._storage_mode = storage_mode
        self._embedding_dimensions = embedding_dimensions
        self._db: Any = None
        self._tables: dict[str, Any] = {}  # 表名 -> AsyncTable
        self._fts_ready: dict[str, set[str]] = {}  # 表名 -> 已建 FTS 索引的列
        self._lock = asyncio.Lock()  # 守护建表/建索引竞态

    # ── 连接 / 表管理 ─────────────────────────────────────────
    async def _conn(self) -> Any:
        if self._db is None:
            self._db = await lancedb.connect_async(self._uri)
        return self._db

    async def _table_names(self) -> list[str]:
        db = await self._conn()
        if hasattr(db, "list_tables"):
            result = await db.list_tables()
            # 新版返回 ListTablesResponse(.tables: list[str]);旧版返回 list
            names = getattr(result, "tables", result)
        else:
            names = await db.table_names()
        return [getattr(t, "name", t) for t in names]

    async def schema_table_names(self) -> frozenset[str]:
        """返回当前 LanceDB namespace 的表名（只读结构扫描 API）。"""
        return frozenset(await self._table_names())

    def _declared_schema(self, index: str, dimensions: int) -> Any:
        """构造正式 IndexSchema 的 Arrow 合同，不依赖业务首条数据。"""
        decl = INDEX_SCHEMAS[index]
        vector = [0.0] * dimensions
        return self._build_schema(index, [dict.fromkeys(decl.vector_fields, vector)])

    async def validate_declared_table(self, index: str, dimensions: int | None = None) -> None:
        """校验已有表的完整 Arrow schema；不更新、不扩展字段。"""
        if index not in INDEX_SCHEMAS:
            return
        tbl = await self._open_table(index)
        if tbl is None:
            return
        actual = await tbl.schema()
        actual_fields = {field.name: field.type for field in actual}
        if dimensions is None:
            import pyarrow as pa

            vector_names = INDEX_SCHEMAS[index].vector_fields
            vector_types = [actual_fields.get(name) for name in vector_names]
            dims = {
                value.list_size
                for value in vector_types
                if value is not None and pa.types.is_fixed_size_list(value)
            }
            if len(dims) != 1:
                from zleap.sag.exceptions import StorageSchemaMismatchError

                raise StorageSchemaMismatchError(f"LanceDB 表 {index} 的向量维度无法唯一识别")
            dimensions = dims.pop()
        expected = self._declared_schema(index, dimensions)
        expected_fields = {field.name: field.type for field in expected}
        diffs: list[str] = []
        for name, expected_type in expected_fields.items():
            actual_type = actual_fields.get(name)
            if actual_type is None:
                diffs.append(f"{index}.{name}: 缺少字段")
            elif actual_type != expected_type:
                diffs.append(f"{index}.{name}: Arrow 类型 {actual_type} != {expected_type}")
        # 存量表可能保留旧列（如已下线的 is_delete）；合同只约束当前声明字段，不拒绝列超集。
        if diffs:
            from zleap.sag.exceptions import StorageSchemaMismatchError

            raise StorageSchemaMismatchError(
                f"LanceDB 表 {index} 不符合向量 schema 合同", diffs=diffs
            )

    async def create_declared_table(self, index: str, dimensions: int) -> None:
        """仅在表完全不存在时按正式合同创建空表。"""
        table = _ident(index)
        async with self._lock:
            if table in await self._table_names():
                return
            db = await self._conn()
            tbl = await db.create_table(table, schema=self._declared_schema(index, dimensions))
            self._tables[table] = tbl

    async def _open_table(self, index: str) -> Any | None:
        """打开已存在的表;不存在返回 None(对齐 ES 索引缺失 → 空结果的语义)。"""
        table = _ident(index)
        if table in self._tables:
            cached = self._tables[table]
            try:
                # read_consistency_interval=None 时 AsyncTable 固定在打开时的版本。
                # API 与写入 Worker 是独立进程，复用表前必须显式跟进最新版本。
                await cached.checkout_latest()
                return cached
            except Exception as error:
                logger.warning(
                    "LanceDB 缓存表刷新失败，重新打开",
                    extra={"table": table, "error_type": safe_error_type(error)},
                )
                self._tables.pop(table, None)
        async with self._lock:
            if table in self._tables:
                cached = self._tables[table]
                try:
                    await cached.checkout_latest()
                    return cached
                except Exception as error:
                    logger.warning(
                        "LanceDB 缓存表刷新失败，重新打开",
                        extra={"table": table, "error_type": safe_error_type(error)},
                    )
                    self._tables.pop(table, None)
            if table not in await self._table_names():
                return None
            db = await self._conn()
            tbl = await db.open_table(table)
            self._tables[table] = tbl
            return tbl

    async def _reopen_table(self, index: str) -> Any | None:
        """Discard one stale process-local handle and open the latest table version."""

        table = _ident(index)
        async with self._lock:
            self._tables.pop(table, None)
            if table not in await self._table_names():
                return None
            db = await self._conn()
            tbl = await db.open_table(table)
            self._tables[table] = tbl
            return tbl

    async def _ensure_table(self, index: str, batch: list[dict[str, Any]]) -> Any:
        """打开或按首批文档创建表。"""
        from zleap.sag.core.storage.index_schemas import MODE_INDEX_NAMES
        from zleap.sag.exceptions import StorageError

        mode = self._storage_mode
        if index in INDEX_SCHEMAS and index not in MODE_INDEX_NAMES[mode]:
            raise StorageError(f"storage_mode={mode} 禁止访问向量表 {index}")
        tbl = await self._open_table(index)
        if tbl is not None:
            dimensions = None
            decl = INDEX_SCHEMAS.get(index)
            if decl is not None:
                for document in batch:
                    for name in decl.vector_fields:
                        value = document.get(name)
                        if _is_vector(value):
                            dimensions = len(value)
                            break
                    if dimensions is not None:
                        break
            await self.validate_declared_table(index, dimensions)
            return tbl
        table = _ident(index)
        async with self._lock:
            if table in self._tables:
                return self._tables[table]
            if table in await self._table_names():  # 竞态:他处已建
                db = await self._conn()
                tbl = await db.open_table(table)
            else:
                schema = self._build_schema(index, batch)
                db = await self._conn()
                tbl = await db.create_table(table, schema=schema)
            self._tables[table] = tbl
            return tbl

    def _build_schema(self, index: str, batch: list[dict[str, Any]]) -> Any:
        """由声明式 IndexSchema + 首批文档构建 pyarrow schema。"""
        import pyarrow as pa

        decl = INDEX_SCHEMAS.get(index)
        fields: list[Any] = [pa.field("id", pa.string())]
        seen: set[str] = {"id"}

        def _vector_dim(name: str) -> int:
            for doc in batch:
                v = doc.get(name)
                if _is_vector(v):
                    return len(v)
            # 该向量列在首批中缺失:借同批其它向量列的维度(同一 embedding 模型)
            for doc in batch:
                for v in doc.values():
                    if _is_vector(v):
                        return len(v)
            return self._embedding_dimensions or 1024

        if decl is not None:
            for f in decl.vector_fields:
                fields.append(pa.field(f, pa.list_(pa.float32(), _vector_dim(f))))
            for f in (
                decl.text_fields
                + decl.text_keyword_fields
                + decl.stored_text_fields
                + decl.keyword_fields
                + decl.object_fields
                + decl.datetime_fields
            ):
                fields.append(pa.field(f, pa.string()))
            for f in decl.array_fields:
                fields.append(pa.field(f, pa.list_(pa.string())))
            for f in decl.bool_fields:
                fields.append(pa.field(f, pa.bool_()))
            for f in decl.int_fields:
                fields.append(pa.field(f, pa.int64()))
            seen.update(decl.all_fields())

        # 声明之外的字段:按首批文档值推断(未知索引则全部走这里)
        for doc in batch:
            for name, value in doc.items():
                col = _ident(name)
                if col in seen or name == "_id" or value is None:
                    continue
                seen.add(col)
                fields.append(pa.field(col, self._infer_type(value)))
        return pa.schema(fields)

    @staticmethod
    def _infer_type(value: Any) -> Any:
        import pyarrow as pa

        if _is_vector(value):
            return pa.list_(pa.float32(), len(value))
        if isinstance(value, bool):
            return pa.bool_()
        if isinstance(value, int):
            return pa.int64()
        if isinstance(value, float):
            return pa.float64()
        if isinstance(value, (list, tuple)):
            return pa.list_(pa.string())
        return pa.string()  # str/datetime/dict 等统一字符串(dict → JSON)

    def _normalize_row(self, tbl: Any, did: str, doc: dict[str, Any]) -> dict[str, Any]:
        """按表 schema 规整一行:缺失补 None、datetime→ISO、类型强制;多余字段丢弃。"""
        import pyarrow as pa

        schema = tbl.schema if not asyncio.iscoroutine(tbl.schema) else None
        # AsyncTable.schema() 是协程;缓存到实例上避免每行 await
        row: dict[str, Any] = {"id": did}
        for f in self._schema_fields(tbl):
            name, ftype = f
            if name == "id":
                continue
            value = doc.get(name)
            if value is None:
                row[name] = None
            elif pa.types.is_fixed_size_list(ftype):
                row[name] = [float(x) for x in value] if _is_vector(value) else None
            elif pa.types.is_list(ftype):
                row[name] = [str(x) for x in value] if isinstance(value, (list, tuple)) else None
            elif pa.types.is_boolean(ftype):
                row[name] = bool(value)
            elif pa.types.is_integer(ftype):
                row[name] = int(value)
            elif pa.types.is_floating(ftype):
                row[name] = float(value)
            else:  # string
                if isinstance(value, (datetime, date)):
                    row[name] = value.isoformat()
                elif isinstance(value, (dict, list, tuple)):
                    row[name] = json.dumps(value, ensure_ascii=False)
                else:
                    row[name] = str(value)
        _ = schema
        return row

    def _schema_fields(self, tbl: Any) -> list[tuple[str, Any]]:
        cached = getattr(tbl, "_zleap_schema_fields", None)
        if cached is not None:
            return cached
        raise StateError("schema 未缓存:内部错误,应先经 _cache_schema")

    async def _cache_schema(self, tbl: Any) -> None:
        if getattr(tbl, "_zleap_schema_fields", None) is None:
            schema = await tbl.schema()
            tbl._zleap_schema_fields = [(f.name, f.type) for f in schema]

    def _vector_columns(self, tbl: Any) -> set[str]:
        import pyarrow as pa

        return {n for n, t in self._schema_fields(tbl) if pa.types.is_fixed_size_list(t)}

    def _non_vector_columns(self, tbl: Any) -> list[str]:
        """查询投影用的列名:剔除向量列,避免把大 embedding 拉回内存。"""
        vector_cols = self._vector_columns(tbl)
        return [n for n, _ in self._schema_fields(tbl) if n not in vector_cols]

    def _array_columns(self, tbl: Any) -> set[str]:
        import pyarrow as pa

        return {
            n
            for n, t in self._schema_fields(tbl)
            if pa.types.is_list(t) and not pa.types.is_fixed_size_list(t)
        }

    # ── 写入 ────────────────────────────────────────────────
    async def _write(self, index: str, rows: list[tuple[str, dict[str, Any]]]) -> int:
        if not rows:
            return 0
        tbl = await self._ensure_table(index, [doc for _, doc in rows])
        await self._cache_schema(tbl)
        normalized = [self._normalize_row(tbl, did, doc) for did, doc in rows]
        await (
            tbl.merge_insert("id")
            .when_matched_update_all()
            .when_not_matched_insert_all()
            .execute(normalized)
        )
        return len(normalized)

    async def index_document(
        self,
        index: str,
        document: dict[str, Any],
        doc_id: str | None = None,
        routing: str | None = None,
    ) -> str:
        did = _doc_id(document, doc_id) or ""
        await self._write(index, [(did, document)])
        return did

    async def bulk_index(
        self,
        index: str,
        documents: list[dict[str, Any]],
        return_details: bool = False,
        routing: str | None = None,
    ) -> Any:
        rows = []
        for d in documents:
            did = _doc_id(d)
            if did is None:
                continue
            rows.append((did, {k: v for k, v in d.items() if k != "_id"}))
        n = await self._write(index, rows)
        if return_details:
            return {
                "success": True,
                "total": len(documents),
                "success_count": n,
                "error_count": 0,
                "errors": [],
            }
        return n

    # ── filter_query(ES DSL)→ WHERE 字符串 ─────────────────
    def _translate_filter(self, tbl: Any, fq: dict[str, Any] | None) -> str:
        if not fq:
            return ""
        array_cols = self._array_columns(tbl)

        def _col(f: str) -> str:
            if f == "_id":
                return "id"
            return _ident(f.removesuffix(".keyword"))

        def _clause(node: Any) -> str:
            if not isinstance(node, dict):
                return ""
            if "bool" in node:
                b = node["bool"]
                parts: list[str] = []
                for key in ("must", "filter"):
                    sub = b.get(key, [])
                    sub = sub if isinstance(sub, list) else [sub]
                    parts.extend(p for c in sub if (p := _clause(c)))
                should = b.get("should", [])
                should = should if isinstance(should, list) else [should]
                should_parts = [p for c in should if (p := _clause(c))]
                if should_parts:
                    # ES 语义:有 must/filter 时 should 仅参与打分,除非 minimum_should_match≥1
                    msm = b.get("minimum_should_match", 0 if parts else 1)
                    if int(msm or 0) >= 1:
                        parts.append(" OR ".join(f"({p})" for p in should_parts))
                must_not = b.get("must_not", [])
                must_not = must_not if isinstance(must_not, list) else [must_not]
                parts.extend(f"NOT ({p})" for c in must_not if (p := _clause(c)))
                return " AND ".join(f"({p})" for p in parts) if parts else ""
            if "term" in node:
                body = node["term"]
                ((f, v),) = body.items()
                if isinstance(v, dict):  # {"f": {"value": x}} 形式
                    v = v.get("value")
                col = _col(f)
                if v is False:
                    return f"({col} = false OR {col} IS NULL)"
                return f"{col} = {_sql_literal(v)}"
            if "terms" in node:
                ((f, vals),) = node["terms"].items()
                col = _col(f)
                values = list(vals)
                if not values:
                    return "1 = 0"
                literals = ", ".join(_sql_literal(v) for v in values)
                if col in array_cols:
                    return f"array_has_any({col}, [{literals}])"
                return f"{col} IN ({literals})"
            if "range" in node:
                ((f, conds),) = node["range"].items()
                col = _col(f)
                ops = {"gte": ">=", "gt": ">", "lte": "<=", "lt": "<"}
                parts = [
                    f"{col} {ops[op]} {_sql_literal(v)}" for op, v in conds.items() if op in ops
                ]
                return " AND ".join(parts)
            if "exists" in node:
                return f"{_col(node['exists']['field'])} IS NOT NULL"
            if "match_all" in node:
                return ""
            return ""  # match/multi_match 等打分子句由词法路径处理

        return _clause(fq)

    # ── kNN ─────────────────────────────────────────────────
    async def vector_search(
        self,
        index: str,
        field: str,
        vector: list[float],
        size: int = 10,
        filter_query: dict[str, Any] | None = None,
        routing: str | None = None,
        include_vector: bool = False,
    ) -> list[dict[str, Any]]:
        tbl = await self._open_table(index)
        if tbl is None:
            logger.warning(f"向量检索:索引 '{index}' 不存在,返回空结果")
            return []

        async def _execute(current: Any) -> list[dict[str, Any]]:
            await self._cache_schema(current)
            q = current.query().nearest_to([float(x) for x in vector])
            # 多向量列表(如 source_chunks 的 heading_vector/content_vector)必须显式指定列
            if _ident(field) in self._vector_columns(current):
                q = q.column(_ident(field))
            q = q.distance_type("cosine").limit(int(size))
            where = self._translate_filter(current, filter_query)
            if where:
                q = q.where(where)
            if not include_vector:
                keep = self._non_vector_columns(current)
                q = q.select([*keep, "_distance"])  # 显式带计算列,避免 lance 自动投影告警
            rows = await q.to_list()
            return [
                {
                    **{k: v for k, v in row.items() if k != "_distance"},
                    "_score": cosine_distance_to_score(row["_distance"]),
                }
                for row in rows
            ]

        try:
            return await _execute(tbl)
        except Exception as error:
            if not _is_stale_snapshot_error(error):
                raise
            logger.warning(
                "LanceDB 查询命中过期快照，刷新后重试",
                extra={"table": _ident(index), "error_type": safe_error_type(error)},
            )
            refreshed = await self._reopen_table(index)
            if refreshed is None:
                return []
            return await _execute(refreshed)

    # ── search(词法 BM25 + 过滤)────────────────────────────
    async def search(
        self,
        index: str,
        query: dict[str, Any],
        size: int = 10,
        from_: int = 0,
        return_full_response: bool = False,
        routing: str | None = None,
        include_vector: bool = False,
        **kwargs: Any,
    ) -> Any:
        tbl = await self._open_table(index)
        if tbl is None:
            logger.warning(f"搜索:索引 '{index}' 不存在,返回空结果")
            return {"total": 0, "max_score": 0, "hits": []} if return_full_response else []
        sort = tuple(kwargs.pop("sort", ()))

        async def _execute(current: Any) -> Any:
            await self._cache_schema(current)
            lexical, residual = self._split_lexical(query)
            where = self._translate_filter(current, residual)
            fetch = int(size) + int(from_)

            if lexical:
                text_query, fields = lexical
                hits = await self._fts_search(current, index, text_query, fields, where, fetch)
            else:
                q = current.query()
                if where:
                    q = q.where(where)
                keep = (
                    [n for n, _ in self._schema_fields(current)]
                    if include_vector
                    else self._non_vector_columns(current)
                )
                q = q.select(keep)
                if sort:
                    q = q.order_by(
                        [
                            ColumnOrdering(
                                column_name=_ident(field),
                                ascending=direction == "asc",
                                nulls_first=False,
                            )
                            for field, direction in sort
                        ]
                    )
                rows = await q.offset(int(from_)).limit(int(size)).to_list()
                hits = [(row, 1.0) for row in rows]

            if lexical:
                hits = hits[int(from_) : int(from_) + int(size)]
            if return_full_response:
                return {
                    "total": len(hits),
                    "max_score": max((score for _, score in hits), default=0),
                    "hits": [
                        {
                            "id": row.get("id"),
                            "score": score,
                            "source": row,
                            "index": index,
                        }
                        for row, score in hits
                    ],
                }
            return [row for row, _ in hits]

        try:
            return await _execute(tbl)
        except Exception as error:
            if not _is_stale_snapshot_error(error):
                raise
            logger.warning(
                "LanceDB 查询命中过期快照，刷新后重试",
                extra={"table": _ident(index), "error_type": safe_error_type(error)},
            )
            refreshed = await self._reopen_table(index)
            if refreshed is None:
                return {"total": 0, "max_score": 0, "hits": []} if return_full_response else []
            return await _execute(refreshed)

    @staticmethod
    def _split_lexical(
        query: dict[str, Any] | None,
    ) -> tuple[tuple[str, list[tuple[str, float]]] | None, dict[str, Any] | None]:
        """把查询拆成 (词法子句, 剩余过滤)。

        词法子句返回 (查询文本, [(字段, 权重)]),支持:
        - {"multi_match": {"query": q, "fields": ["title^3", "content"]}}
        - {"match": {"f": "text"}} 与嵌套 {"match": {"f": {"query": "text", ...}}}
        以上可出现在顶层或 bool.must 里;其余子句原样保留作过滤。
        """

        def _parse(node: dict[str, Any]) -> tuple[str, list[tuple[str, float]]] | None:
            if "multi_match" in node:
                mm = node["multi_match"]
                fields: list[tuple[str, float]] = []
                for f in mm.get("fields", []):
                    name, _, boost = f.partition("^")
                    fields.append((name, float(boost) if boost else 1.0))
                return str(mm.get("query", "")), fields
            if "match" in node:
                ((f, v),) = node["match"].items()
                text = v.get("query") if isinstance(v, dict) else v
                return str(text), [(f, 1.0)]
            return None

        if not query:
            return None, None
        found = _parse(query)
        if found:
            return found, None
        if "bool" in query:
            b = dict(query["bool"])
            must = b.get("must", [])
            must = must if isinstance(must, list) else [must]
            rest = []
            for c in must:
                if found is None and isinstance(c, dict):
                    parsed = _parse(c)
                    if parsed:
                        found = parsed
                        continue
                rest.append(c)
            if found:
                b["must"] = rest
                return found, {"bool": b}
        return None, query

    async def _fts_search(
        self,
        tbl: Any,
        index: str,
        text_query: str,
        fields: list[tuple[str, float]],
        where: str,
        limit: int,
    ) -> list[tuple[dict[str, Any], float]]:
        """逐字段 BM25 检索,按 id 合并取 max(权重×分数),降序返回。"""
        table = _ident(index)
        decl = INDEX_SCHEMAS.get(index)
        text_cols = set(decl.text_fields + decl.text_keyword_fields) if decl else set()
        schema_cols = {n for n, _ in self._schema_fields(tbl)}
        keep = [n for n in schema_cols if n not in self._vector_columns(tbl)]

        merged: dict[str, tuple[dict[str, Any], float]] = {}
        for field_name, boost in fields:
            col = _ident(field_name.removesuffix(".keyword"))
            if col not in schema_cols or (text_cols and col not in text_cols):
                continue
            await self._ensure_fts(tbl, table, col)
            q = tbl.query().nearest_to_text(text_query, columns=[col])
            if where:
                q = q.where(where)
            try:
                rows = await q.select([*keep, "_score"]).limit(int(limit)).to_list()
            except Exception as e:  # 空表/无索引段等边界 → 该字段无命中
                if _is_stale_snapshot_error(e):
                    raise
                logger.warning("FTS 检索失败", extra={"error_type": safe_error_type(e)})
                continue
            for r in rows:
                score = float(r.pop("_score", 0.0)) * boost
                rid = str(r.get("id"))
                if rid not in merged or score > merged[rid][1]:
                    merged[rid] = (r, score)
        return sorted(merged.values(), key=lambda t: t[1], reverse=True)

    async def _ensure_fts(self, tbl: Any, table: str, col: str) -> None:
        ready = self._fts_ready.setdefault(table, set())
        if col in ready:
            return
        async with self._lock:
            if col in ready:
                return
            from lancedb.index import FTS

            try:
                await tbl.create_index(col, config=FTS())
            except Exception as e:  # 已存在等 → 幂等
                logger.debug(
                    "FTS 索引创建跳过",
                    extra={"error_type": safe_error_type(e)},
                )
            ready.add(col)

    # ── 单文档 / 元信息 ──────────────────────────────────────
    async def get_document(self, index: str, doc_id: str) -> dict[str, Any] | None:
        tbl = await self._open_table(index)
        if tbl is None:
            return None
        await self._cache_schema(tbl)
        keep = self._non_vector_columns(tbl)
        rows = await tbl.query().where(f"id = {_sql_str(doc_id)}").select(keep).limit(1).to_list()
        return rows[0] if rows else None

    async def get_documents(
        self,
        index: str,
        doc_ids: Sequence[str],
        *,
        include_vector: bool = False,
    ) -> list[dict[str, Any]]:
        """批量按 id 取文档:单次 ``id IN (...)`` 扫描替代逐条全表扫。

        默认投影与 :meth:`get_document` 一致；VectorStore 显式 include_vector=True 才回读数值。
        返回顺序不保证，调用方按 id 自行对齐。
        """
        ids = [str(doc_id) for doc_id in doc_ids]
        if not ids:
            return []
        tbl = await self._open_table(index)
        if tbl is None:
            return []
        await self._cache_schema(tbl)
        keep = (
            [name for name, _ in self._schema_fields(tbl)]
            if include_vector
            else self._non_vector_columns(tbl)
        )

        async def _fetch(batch: list[str]) -> list[dict[str, Any]]:
            # 复用 terms 过滤器翻译,不再自己拼 IN 列表(_col 会把 _id 映射到 id)。
            where = self._translate_filter(tbl, {"terms": {"_id": batch}})
            return await tbl.query().where(where).select(keep).limit(len(batch)).to_list()

        # 调用方一次可传 3000~5000 个 id(见 entity/event_entity repository),分片串行
        # 会把单次逻辑读放大成 N 次顺序扫描;返回顺序本就不保证,并发无语义影响。
        batches = [
            ids[offset : offset + _GET_MANY_CHUNK] for offset in range(0, len(ids), _GET_MANY_CHUNK)
        ]
        results = await asyncio.gather(*(_fetch(batch) for batch in batches))
        return [row for rows in results for row in rows]

    async def delete_document(self, index: str, doc_id: str) -> bool:
        tbl = await self._open_table(index)
        if tbl is None:
            return False
        await self._cache_schema(tbl)
        if await self.get_document(index, doc_id) is None:
            return False
        await tbl.delete(f"id = {_sql_str(doc_id)}")
        return True

    async def update_document(self, index: str, doc_id: str, update_data: dict[str, Any]) -> bool:
        tbl = await self._open_table(index)
        if tbl is None:
            return False
        await self._cache_schema(tbl)
        if await self.get_document(index, doc_id) is None:
            logger.warning(f"文档 {doc_id} 不存在")
            return False
        fields = dict(self._schema_fields(tbl))
        updates = {
            _ident(k): (v.isoformat() if isinstance(v, (datetime, date)) else v)
            for k, v in update_data.items()
            if _ident(k) in fields and k not in ("_id", "id")
        }
        if updates:
            await tbl.update(updates, where=f"id = {_sql_str(doc_id)}")
        return True

    async def count_documents(self, index: str, query: dict[str, Any] | None = None) -> int:
        tbl = await self._open_table(index)
        if tbl is None:
            return 0
        await self._cache_schema(tbl)
        where = self._translate_filter(tbl, query)
        return int(await tbl.count_rows(where or None))

    # ── 索引维护 ────────────────────────────────────────────
    # LanceDB 不像 ES 会在写入时自动维护索引：没有显式建索引，kNN 退化为全表
    # brute-force，标量 where 退化为全表扫描（且 prefilter 比无过滤更慢）。
    # 建索引需要足够样本训练 IVF 码本，因此由 optimize() 在写入完成后统一触发，
    # 而不是在 _write 里按批建。
    _MIN_ROWS_FOR_VECTOR_INDEX = 256

    async def create_index(self, index: str | None = None, *args: Any, **kwargs: Any) -> bool:
        """为已存在的表建立向量 + 标量索引（幂等）。

        index 为 None 时对当前 storage_mode 下的所有已存在表执行。
        """
        from zleap.sag.core.storage.index_schemas import MODE_INDEX_NAMES

        names = [index] if index else list(MODE_INDEX_NAMES[self._storage_mode])
        ok = True
        for name in names:
            if not await self._build_indices(name):
                ok = False
        return ok

    async def _build_indices(self, index: str) -> bool:
        tbl = await self._open_table(index)
        if tbl is None:
            return True  # 表不存在 → 无需建索引，对齐 ES 缺失索引的宽容语义
        await self._cache_schema(tbl)
        decl = INDEX_SCHEMAS.get(index)
        if decl is None:
            return True
        existing = await self._existing_index_columns(tbl)
        rows = int(await tbl.count_rows())
        ok = True
        # 向量列：IVF_FLAT 需要训练样本，行数太少时保持 brute-force（本身已足够快）。
        # 刻意不用 IVF_PQ：PQ 量化会失真距离（实测 recall@20 仅 0.76，top1 分数
        # 1.0 被压到 0.90），而 multi 检索的 0.9/0.4 阈值直接吃分数，失真会改召回。
        # IVF_FLAT 保留精确距离（recall 0.99，分数与 brute-force 一致），延迟相同。
        if rows >= self._MIN_ROWS_FOR_VECTOR_INDEX:
            from lancedb.index import IvfFlat

            partitions = max(1, min(256, int(rows**0.5)))
            for col in decl.vector_fields:
                if _ident(col) in existing or _ident(col) not in self._vector_columns(tbl):
                    continue
                if not await self._try_create_index(
                    tbl,
                    index,
                    col,
                    IvfFlat(distance_type="cosine", num_partitions=partitions),
                ):
                    ok = False
        # 标量列：消除 where / get_document 的全表扫，并让 kNN prefilter 走索引。
        from lancedb.index import BTree

        for col in decl.keyword_fields:
            if _ident(col) in existing:
                continue
            if not await self._try_create_index(tbl, index, col, BTree()):
                ok = False
        return ok

    async def _try_create_index(self, tbl: Any, index: str, col: str, config: Any) -> bool:
        try:
            await tbl.create_index(_ident(col), config=config, replace=False)
            logger.info(f"LanceDB 索引已创建: {index}.{col} ({type(config).__name__})")
            return True
        except Exception as e:
            # 已存在 / 列类型不支持 → 幂等跳过，不阻断其余索引
            logger.debug(
                "LanceDB 索引创建跳过",
                extra={"error_type": safe_error_type(e)},
            )
            return True

    async def _existing_index_columns(self, tbl: Any) -> set[str]:
        try:
            indices = await tbl.list_indices()
        except Exception as e:
            logger.debug(
                "LanceDB 索引列表读取失败",
                extra={"error_type": safe_error_type(e)},
            )
            return set()
        columns: set[str] = set()
        for meta in indices or []:
            for col in getattr(meta, "columns", None) or []:
                columns.add(col)
        return columns

    async def optimize(self, index: str | None = None) -> bool:
        """压实数据文件 + 建索引。写入完成后调用。

        每次 merge_insert 都会产生一个新 version 与新的 data fragment；不压实会
        让表体积和扫描代价随写入次数持续增长（实测可达理论体积的 2 倍以上）。
        显式保留 7 天旧版本，保护其它进程仍在读取的快照；
        禁止 cleanup_older_than=0，它会立刻删除 API 正在读取的数据片段。
        """
        from zleap.sag.core.storage.index_schemas import MODE_INDEX_NAMES

        names = [index] if index else list(MODE_INDEX_NAMES[self._storage_mode])
        ok = True
        for name in names:
            tbl = await self._open_table(name)
            if tbl is None:
                continue
            try:
                await tbl.optimize(cleanup_older_than=_SNAPSHOT_RETENTION)
                logger.info(f"LanceDB 表已压实: {name}")
            except Exception as e:
                logger.warning("LanceDB 压实失败", extra={"error_type": safe_error_type(e)})
                ok = False
            if not await self._build_indices(name):
                ok = False
        return ok

    async def index_exists(self, index: str) -> bool:
        return _ident(index) in await self._table_names()

    async def delete_index(self, index: str) -> bool:
        table = _ident(index)
        if table in await self._table_names():
            db = await self._conn()
            await db.drop_table(table)
        self._tables.pop(table, None)
        self._fts_ready.pop(table, None)
        return True

    async def ping(self) -> bool:
        try:
            await self._table_names()
            return True
        except Exception as e:
            logger.error("LanceDB 连接检查失败", extra={"error_type": safe_error_type(e)})
            return False

    async def check_connection(self) -> bool:
        return await self.ping()

    async def close(self) -> None:
        db, self._db = self._db, None
        self._tables.clear()
        self._fts_ready.clear()
        if db is not None and hasattr(db, "close"):
            result = db.close()
            if asyncio.iscoroutine(result):
                await result
