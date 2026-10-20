#!/usr/bin/env python3
"""B17/P1.4 Agent 真实评测结果计分（stdlib only）。

输入：
  --cases     scripts/eval/agent_eval_cases.json（20 条期望 route/fallback/citations）
  --results   真实 LLM 结果（schema 见 scripts/eval/agent_eval_result_schema.json）
  --manual-min 人工抽检最少题数（默认 10；协议要求 ≥20% 且不少于 10）
输出：控制台指标 + 可选 --json-out 机器可读汇总。

关键安全语义：
  - run.key_configured=false（无真实 key/离线 mock）→ 只打印 MOCK/OFFLINE 警告，quality_claim_allowed=false，
    不得把 mock 结果当作真实质量达标；
  - 人工抽检样本不足/比例低于阈值 → quality_claim_allowed=false；
  - 引用有效性、无证据拒答按协议门禁；路由/回退用于回归检查。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_CASES = ROOT / "agent_eval_cases.json"
DEFAULT_RESULTS = ROOT / "agent_eval_result_example.json"

ROUTE_PASS_THRESHOLD = 1.0       # 评测集内路由/回退契约应 100% 符合
CITATION_VALID_THRESHOLD = 1.0   # 引用有效性 100%
NO_EVIDENCE_REFUSAL_THRESHOLD = 1.0
MANUAL_SUPPORT_THRESHOLD = 0.90  # 抽样支持率 ≥90%
MANUAL_MIN_SAMPLES = 10


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def percentile(sorted_values: list[int], p: int) -> int:
    if not sorted_values:
        return 0
    idx = min(len(sorted_values) * p // 100, len(sorted_values) - 1)
    return int(sorted_values[idx])


def normalize_route(route: list[str] | None) -> list[str]:
    route = list(route or [])
    if route and route[-1] == "final":
        route = route[:-1]
    return route


def score(cases_doc: dict, results_doc: dict, manual_min: int) -> dict:
    cases = {c["id"]: c for c in cases_doc.get("cases", [])}
    result_cases = results_doc.get("cases", [])
    run = results_doc.get("run", {})
    key_configured = bool(run.get("key_configured", False))

    route_total = route_match = 0
    fallback_total = fallback_match = 0
    citation_total = citation_valid = citation_match = 0
    no_evidence_total = no_evidence_refused = 0
    latencies: list[int] = []
    manual_checked = manual_support = manual_partial = manual_unsupported = 0
    unknown_case_ids: list[str] = []
    missing_cases: list[str] = []
    errors: list[str] = []

    for entry in result_cases:
        case_id = entry.get("case_id", "")
        expected = cases.get(case_id)
        if expected is None:
            unknown_case_ids.append(case_id)
            expected = {}
        expected_route = entry.get("expected_route", expected.get("expectedToolRoute", []))
        expected_fallback = entry.get("expected_fallback", expected.get("expectedFallback"))
        expected_citations = entry.get("expected_citations", expected.get("expectedCitationIds", []))

        route_total += 1
        if normalize_route(entry.get("actual_route")) == normalize_route(expected_route):
            route_match += 1

        fallback_total += 1
        if entry.get("fallback") == expected_fallback:
            fallback_match += 1

        citations = list(entry.get("citations", []))
        if expected_citations:
            # 引用门禁只在“预期应有引用”的用例上计分；无证据/拒答用例由下方 refused 指标单独覆盖。
            citation_total += 1
            if bool(entry.get("citation_valid")) and len(citations) == len(set(citations)):
                citation_valid += 1
            if citations == list(expected_citations):
                citation_match += 1

        # 真正的“无证据拒答”用例：预期无引用且没有预期 fallback（fallback 用例可能转普通检索并产生引用，
        # 不应用拒答门禁误伤）。拒答/无证据语义见协议 §4-§5。
        if not expected_citations and expected_fallback is None:
            no_evidence_total += 1
            if bool(entry.get("refused")):
                no_evidence_refused += 1

        latencies.append(int(entry.get("latency_ms", 0)))
        support = entry.get("manual_support", "not_checked")
        if support in ("support", "partial", "unsupported"):
            manual_checked += 1
            if support == "support":
                manual_support += 1
            elif support == "partial":
                manual_partial += 1
            else:
                manual_unsupported += 1

    for case_id in cases:
        if case_id not in {c.get("case_id") for c in result_cases}:
            missing_cases.append(case_id)

    def rate(num: int, den: int) -> float:
        return round(num / den, 4) if den else 0.0

    route_rate = rate(route_match, route_total)
    fallback_rate = rate(fallback_match, fallback_total)
    citation_valid_rate = rate(citation_valid, citation_total)
    citation_match_rate = rate(citation_match, citation_total)
    no_evidence_refusal_rate = rate(no_evidence_refused, no_evidence_total)
    manual_checked_rate = rate(manual_checked, len(result_cases))
    manual_support_rate = rate(manual_support, manual_checked) if manual_checked else 0.0

    sorted_latencies = sorted(latencies)
    gates = {
        "route_contract": route_rate >= ROUTE_PASS_THRESHOLD and route_total > 0,
        "fallback_contract": fallback_rate >= ROUTE_PASS_THRESHOLD and fallback_total > 0,
        "citation_validity_100pct": citation_valid_rate >= CITATION_VALID_THRESHOLD and citation_total > 0,
        "citation_expected_match": citation_match_rate >= CITATION_VALID_THRESHOLD and citation_total > 0,
        "no_evidence_refusal_100pct": (
            no_evidence_refusal_rate >= NO_EVIDENCE_REFUSAL_THRESHOLD if no_evidence_total else True
        ),
        "manual_sample_min": manual_checked >= manual_min,
        "manual_support_90pct": manual_support_rate >= MANUAL_SUPPORT_THRESHOLD if manual_checked else False,
    }
    quality_claim_allowed = (
        key_configured
        and all(gates.values())
        and not unknown_case_ids
        and not missing_cases
    )
    if not key_configured:
        errors.append("KEY_NOT_CONFIGURED：无真实 LLM key/仅为 mock 或离线结果，禁止宣称真实质量")
    if manual_checked < manual_min:
        errors.append(f"MANUAL_SAMPLE_INSUFFICIENT：人工抽检 {manual_checked} < {manual_min}")
    if unknown_case_ids:
        errors.append(f"UNKNOWN_CASE_IDS：{unknown_case_ids}")
    if missing_cases:
        errors.append(f"MISSING_CASES：{missing_cases}")

    return {
        "schema_version": 1,
        "quality_claim_allowed": quality_claim_allowed,
        "key_configured": key_configured,
        "model": run.get("model"),
        "prompt_version": run.get("prompt_version"),
        "date": run.get("date"),
        "cases_total": len(result_cases),
        "expected_cases_total": len(cases),
        "route": {"matched": route_match, "total": route_total, "rate": route_rate},
        "fallback": {"matched": fallback_match, "total": fallback_total, "rate": fallback_rate},
        "citations": {
            "valid": citation_valid,
            "expected_match": citation_match,
            "total": citation_total,
            "valid_rate": citation_valid_rate,
            "expected_match_rate": citation_match_rate,
        },
        "no_evidence_refusal": {
            "refused": no_evidence_refused,
            "total": no_evidence_total,
            "rate": no_evidence_refusal_rate,
        },
        "manual": {
            "checked": manual_checked,
            "sample_rate": manual_checked_rate,
            "support": manual_support,
            "partial": manual_partial,
            "unsupported": manual_unsupported,
            "support_rate": manual_support_rate,
            "required_samples": manual_min,
        },
        "latency_ms": {
            "p50": percentile(sorted_latencies, 50),
            "p95": percentile(sorted_latencies, 95),
            "max": max(sorted_latencies) if sorted_latencies else 0,
        },
        "gates": gates,
        "errors": errors,
        "unknown_case_ids": unknown_case_ids,
        "missing_cases": missing_cases,
        "note": "离线 mock/无 key 结果不得作为真实质量声明；阈值见 docs/review/P1_4_QUALITY_EVAL_PLAN.md",
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="B17 Agent 真实评测计分（无 key 时拒绝质量声明）")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--manual-min", type=int, default=MANUAL_MIN_SAMPLES)
    parser.add_argument("--json-out", type=Path, default=None)
    args = parser.parse_args(argv)

    if not args.cases.is_file():
        print(f"cases 文件不存在：{args.cases}", file=sys.stderr)
        return 2
    if not args.results.is_file():
        print(f"results 文件不存在：{args.results}", file=sys.stderr)
        return 2
    try:
        cases_doc = load_json(args.cases)
        results_doc = load_json(args.results)
    except (OSError, json.JSONDecodeError) as exc:
        print(f"读取/解析 JSON 失败：{exc}", file=sys.stderr)
        return 2

    summary = score(cases_doc, results_doc, max(1, int(args.manual_min)))
    print("=" * 72)
    print("B17 Agent 评测计分（真实结果协议见 docs/review/P1_4_QUALITY_EVAL_PLAN.md）")
    print("=" * 72)
    print(f"cases={summary['cases_total']}/{summary['expected_cases_total']} "
          f"model={summary['model']} prompt={summary['prompt_version']}")
    print(f"route_match={summary['route']['matched']}/{summary['route']['total']} "
          f"({summary['route']['rate']:.2%})  "
          f"fallback_match={summary['fallback']['matched']}/{summary['fallback']['total']} "
          f"({summary['fallback']['rate']:.2%})")
    print(f"citation_valid={summary['citations']['valid']}/{summary['citations']['total']} "
          f"({summary['citations']['valid_rate']:.2%})  "
          f"expected_citation_match={summary['citations']['expected_match_rate']:.2%}")
    print(f"no_evidence_refusal={summary['no_evidence_refusal']['refused']}/"
          f"{summary['no_evidence_refusal']['total']} "
          f"({summary['no_evidence_refusal']['rate']:.2%})")
    print(f"manual: checked={summary['manual']['checked']} "
          f"(sample_rate={summary['manual']['sample_rate']:.2%}) "
          f"support={summary['manual']['support']} partial={summary['manual']['partial']} "
          f"unsupported={summary['manual']['unsupported']} "
          f"support_rate={summary['manual']['support_rate']:.2%} (需≥90%)")
    print(f"latency_ms: p50={summary['latency_ms']['p50']} "
          f"p95={summary['latency_ms']['p95']} max={summary['latency_ms']['max']}")
    print(f"QUALITY_CLAIM_ALLOWED={summary['quality_claim_allowed']}")
    for error in summary["errors"]:
        print(f"WARN: {error}")
    if not summary["quality_claim_allowed"]:
        print("⚠️ 结论：不得宣称 Agent/问答真实质量达标（仅离线契约/回归或样本不足）。")
    if args.json_out is not None:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"machine summary -> {args.json_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
