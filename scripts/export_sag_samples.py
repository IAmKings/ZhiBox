#!/usr/bin/env python3
"""B9 质量抽检：从 App 的 SQLite（v7）导出 active event/entity 样本为 JSON。

隐私：本脚本只读本地备份出来的 DB 文件；输出含知识库正文，勿提交仓库/上传。

用法：
  # 1) 从 debug 设备拉 DB（需应用已启动并完成 v6→v7 迁移；WAL 与主库放同一目录）
  adb exec-out run-as com.zhibox.memory cat databases/zhibox.sqlite3 > /tmp/zhibox.sqlite3
  adb exec-out run-as com.zhibox.memory cat databases/zhibox.sqlite3-wal > /tmp/zhibox.sqlite3-wal
  # 2) 导出最多 50 个 active event 样本
  python3 scripts/export_sag_samples.py --db /tmp/zhibox.sqlite3 --out /tmp/sag_samples.json --limit 50
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True, help="SQLite 主库路径（同目录建议同时有 -wal）")
    ap.add_argument("--out", required=True, help="输出 JSON 路径")
    ap.add_argument("--limit", type=int, default=50, help="最多导出 active event 数（建议 30–50）")
    args = ap.parse_args()

    try:
        con = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
        con.row_factory = sqlite3.Row
        rows = con.execute(
            """
            SELECT e.id AS event_id, e.chunkId AS chunk_id, e.title, e.summary, e.content,
                   e.language, e.extractorVersion, e.modelId, e.promptVersion, e.createdAt,
                   c.content AS chunk_text, d.title AS document_title, s.title AS source_title
            FROM event e
            JOIN chunk c ON c.id = e.chunkId
            JOIN document d ON d.id = c.documentId
            JOIN source s ON s.id = d.sourceId
            WHERE e.active = 1
            ORDER BY e.id DESC
            LIMIT ?
            """,
            (max(1, args.limit),),
        ).fetchall()
        samples = []
        for r in rows:
            entities = [
                dict(x)
                for x in con.execute(
                    """
                    SELECT en.entityType AS type, en.name, en.description,
                           ee.role AS relation_role, ee.weight
                    FROM event_entity ee JOIN entity en ON en.id = ee.entityId
                    WHERE ee.eventId = ? ORDER BY ee.weight DESC, en.id
                    """,
                    (r["event_id"],),
                ).fetchall()
            ]
            samples.append(
                {
                    "event_id": r["event_id"],
                    "chunk_id": r["chunk_id"],
                    "source_title": r["source_title"],
                    "document_title": r["document_title"],
                    "chunk_text": r["chunk_text"],
                    "event": {
                        "title": r["title"],
                        "summary": r["summary"],
                        "content": r["content"],
                        "language": r["language"],
                    },
                    "entities": entities,
                    "extractor_version": r["extractorVersion"],
                    "model_id": r["modelId"],
                    "prompt_version": r["promptVersion"],
                    "created_at": r["createdAt"],
                    # 人工标注字段（score_sag_extraction.py 读取）
                    "annotation": {
                        "event_faithful": None,
                        "entities": [
                            {"type_correct": None, "name_correct": None}
                            for _ in entities
                        ],
                        "notes": "",
                    },
                }
            )
        con.close()
    except sqlite3.Error as e:
        print(f"读取 DB 失败：{e}", file=sys.stderr)
        return 2

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(
            {
                "schema": "zhibox-sag-extraction-eval/v1",
                "db": args.db,
                "limit": args.limit,
                "count": len(samples),
                "samples": samples,
            },
            f,
            ensure_ascii=False,
            indent=2,
        )
    print(f"已导出 {len(samples)} 条 active event 样本 → {args.out}")
    print("下一步：人工填写 annotation.*；再运行 scripts/score_sag_extraction.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
