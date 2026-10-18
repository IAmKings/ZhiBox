#!/usr/bin/env python3
"""B9 质量抽检：读取 export_sag_samples.py 输出 + 人工 annotation，计算 PRD 指标。

指标（仅在人工已标注的字段上计算，未标注不进入分母）：
- event 事实忠实性 = event_faithful=True 数 / 已标注 event 数（目标 ≥90%）
- 实体类型准确率 = type_correct=True 数 / 已标注 entity 数（目标 ≥85%）
- 每 event ≥1 entity 覆盖率 = entities 非空 event 数 / 全部 event 数（目标 100%）
另输出 name_correct 准确率（参考）、失败/未标注清单；不达标退出码 1。
"""
from __future__ import annotations

import argparse
import json
import sys


def ratio(num: int, den: int) -> float | None:
    return (num / den) if den else None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", required=True, help="export_sag_samples.py 输出 JSON")
    ap.add_argument("--out", required=True, help="评分结果 JSON 路径")
    args = ap.parse_args()

    with open(args.samples, encoding="utf-8") as f:
        data = json.load(f)
    samples = data.get("samples", [])
    total = len(samples)
    if total == 0:
        print("没有样本；请先导入/补抽取 30–50 chunk", file=sys.stderr)
        return 2

    event_labeled = event_faithful = 0
    entity_labeled = entity_type_ok = entity_name_ok = 0
    uncovered = []
    unlabeled_events = []
    for s in samples:
        ann = s.get("annotation") or {}
        ev = ann.get("event_faithful")
        if isinstance(ev, bool):
            event_labeled += 1
            event_faithful += 1 if ev else 0
        else:
            unlabeled_events.append(s.get("event_id"))

        entities = s.get("entities") or []
        annotations = ann.get("entities") or []
        if not entities:
            uncovered.append(s.get("event_id"))
        for i, ent in enumerate(entities):
            a = annotations[i] if i < len(annotations) else {}
            tc = a.get("type_correct")
            nc = a.get("name_correct")
            if isinstance(tc, bool):
                entity_labeled += 1
                entity_type_ok += 1 if tc else 0
            if isinstance(nc, bool):
                entity_name_ok += 1 if nc else 0

    faithful = ratio(event_faithful, event_labeled)
    type_acc = ratio(entity_type_ok, entity_labeled)
    coverage = ratio(total - len(uncovered), total)
    name_den = sum(
        1
        for s in samples
        for a in (s.get("annotation") or {}).get("entities") or []
        if isinstance(a.get("name_correct"), bool)
    )
    name_acc = ratio(entity_name_ok, name_den)

    thresholds = {"event_faithfulness": 0.90, "entity_type_accuracy": 0.85, "entity_coverage": 1.0}
    passed = (
        faithful is not None and faithful >= thresholds["event_faithfulness"]
        and type_acc is not None and type_acc >= thresholds["entity_type_accuracy"]
        and coverage == thresholds["entity_coverage"]
    )
    result = {
        "schema": "zhibox-sag-extraction-score/v1",
        "samples": total,
        "event_faithfulness": faithful,
        "entity_type_accuracy": type_acc,
        "entity_name_accuracy_reference": name_acc,
        "entity_coverage": coverage,
        "thresholds": thresholds,
        "passed": passed,
        "unlabeled_events": unlabeled_events,
        "events_without_entity": uncovered,
        "notes": "真实 LLM 输出指标；未标注字段不进入分母",
    }
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    def fmt(x: float | None) -> str:
        return "n/a" if x is None else f"{x * 100:.1f}%"

    print(f"样本={total}  event忠实性={fmt(faithful)}  实体类型={fmt(type_acc)}  覆盖率={fmt(coverage)}")
    print(f"未标注 event={len(unlabeled_events)}；无实体 event={len(uncovered)}")
    print(f"结果：{'PASS' if passed else 'FAIL/未完成'} → {args.out}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
