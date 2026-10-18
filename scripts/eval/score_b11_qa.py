#!/usr/bin/env python3
"""B11 真实问答评测计分：读取 B11QaEvalTest 导出的 answers.json，输出指标 JSON + 可读摘要。

指标（只统计生成/引用/拒答，不评检索）：
- 成功率 successRate：有证据题中返回 Completed 的比例；
- 引用合法率 citationValidRate：已作答中「至少 1 个合法引用 + 无非法 token + 非 unverified」的比例；
- 非法引用 citationInvalidCases / unverifiedCases / 无引用 noCitationCases；
- 无证据拒答正确率：4 条 KB 外问题中 refused 且 code=NO_EVIDENCE（或答案=固定拒答文案）的比例；
- refusedByCode：门禁未开/无 key/网络失败等被拒记录按 code 归类计数；
- 延迟 p50/p95（仅已作答）；
- 支持率 supportRate：可选 `--support-manual`（人工/LLM judge 填写的 JSON：{qid: true|false}）；
  未提供时输出 null，并可用 `--write-support-template` 生成待填模板。

用法：
  python3 scripts/eval/score_b11_qa.py --answers /tmp/b11-answers.json
  python3 scripts/eval/score_b11_qa.py --answers ... --support-manual /tmp/support.json --out /tmp/score.json
"""
import argparse
import json
import statistics
import sys
from pathlib import Path

DEFAULT_MANIFEST = Path(__file__).resolve().parent / "b11_qa_manifest.json"
REFUSAL_MESSAGE = "资料不足，无法回答"
NO_EVIDENCE_CODES = ("NO_EVIDENCE",)


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(len(ordered) * p))
    return ordered[index]


def is_valid_citation(record):
    return bool(record.get("citations")) and not record.get("invalid") and not record.get("unverified")


def is_no_evidence_refusal(record):
    if not record.get("refused"):
        return False
    code = record.get("code")
    if code in NO_EVIDENCE_CODES:
        return True
    return REFUSAL_MESSAGE in (record.get("answer") or "")


def build_report(answers, manifest, support_manual):
    records = answers.get("records") or []
    manifest_by_qid = {}
    if manifest:
        for item in manifest.get("queries") or []:
            manifest_by_qid[item.get("qid")] = item

    with_evidence = [r for r in records if not r.get("isNoEvidence")]
    no_evidence = [r for r in records if r.get("isNoEvidence")]
    answered = [r for r in with_evidence if not r.get("refused")]

    valid_answer_cases = [r for r in answered if is_valid_citation(r)]
    invalid_cases = [r for r in answered if r.get("invalid")]
    unverified_cases = [r for r in answered if r.get("unverified")]
    no_citation_cases = [r for r in answered if not r.get("citations")]
    unreferenced_cases = [r for r in answered if (r.get("unreferencedSentenceCount") or 0) > 0]

    refusals_by_code = {}
    for record in records:
        if record.get("refused"):
            code = record.get("code") or "UNKNOWN"
            refusals_by_code[code] = refusals_by_code.get(code, 0) + 1

    latency_values = [int(r.get("elapsedMs") or 0) for r in answered]
    no_evidence_correct = [r for r in no_evidence if is_no_evidence_refusal(r)]
    no_evidence_wrong = [r.get("qid") for r in no_evidence if not is_no_evidence_refusal(r)]

    def ratio(numerator, denominator):
        return None if denominator == 0 else round(numerator / denominator, 4)

    support_counts = {}
    support_rate = None
    if support_manual is not None:
        supported = 0
        judged = 0
        for qid, value in support_manual.items():
            if value is None:
                continue
            if str(qid) in {r.get("qid") for r in with_evidence}:
                judged += 1
                if value is True:
                    supported += 1
        support_counts = {"judged": judged, "supported": supported}
        support_rate = ratio(supported, judged)

    gate = answers.get("gate") or {}
    report = {
        "answersFile": str(answers.get("_sourcePath", "")),
        "generatedAt": answers.get("generatedAt"),
        "gate": gate,
        "runGated": bool(gate.get("gateOpen") is False),
        "counts": {
            "total": len(records),
            "withEvidence": len(with_evidence),
            "noEvidence": len(no_evidence),
            "answered": len(answered),
            "refused": len(records) - len(answered),
        },
        "qa": {
            "successRate": ratio(len(answered), len(with_evidence)),
            "citationValidRate": ratio(len(valid_answer_cases), len(answered)),
            "citationInvalidCases": len(invalid_cases),
            "citationInvalidQids": [r.get("qid") for r in invalid_cases],
            "unverifiedCases": len(unverified_cases),
            "unverifiedQids": [r.get("qid") for r in unverified_cases],
            "noCitationCases": len(no_citation_cases),
            "noCitationQids": [r.get("qid") for r in no_citation_cases],
            "unreferencedCases": len(unreferenced_cases),
            "unreferencedQids": [r.get("qid") for r in unreferenced_cases],
            "fullyCitedRate": ratio(len(answered) - len(unreferenced_cases), len(answered)),
            "avgCitationsPerAnswer": (
                None if not answered
                else round(statistics.mean(len(r.get("citations") or []) for r in answered), 3)
            ),
            "noEvidenceRefusalAccuracy": ratio(len(no_evidence_correct), len(no_evidence)),
            "noEvidenceWrongQids": no_evidence_wrong,
            "latencyP50Ms": percentile(latency_values, 0.50),
            "latencyP95Ms": percentile(latency_values, 0.95),
        },
        "refusedByCode": refusals_by_code,
        "support": {
            "available": support_manual is not None,
            "rate": support_rate,
            "counts": support_counts,
            "note": None if support_manual is not None
            else "未提供 --support-manual（人工/LLM judge 支持率未评）",
        },
    }
    return report


def format_summary(report):
    counts = report["counts"]
    qa = report["qa"]
    lines = []
    if report.get("runGated"):
        lines.append("!! 本次运行门禁未开（gateOpen=false）：有证据题全部被拒，指标不可用于质量结论。")
    lines.append(
        f"records={counts['total']} (withEvidence={counts['withEvidence']}, noEvidence={counts['noEvidence']}) "
        f"answered={counts['answered']} refused={counts['refused']}"
    )
    lines.append(
        f"successRate={qa['successRate']} citationValidRate={qa['citationValidRate']} "
        f"invalid={qa['citationInvalidCases']} unverified={qa['unverifiedCases']} "
        f"noCitation={qa['noCitationCases']}"
    )
    lines.append(
        f"noEvidenceRefusalAccuracy={qa['noEvidenceRefusalAccuracy']} "
        f"p50={qa['latencyP50Ms']}ms p95={qa['latencyP95Ms']}ms "
        f"avgCitations={qa['avgCitationsPerAnswer']}"
    )
    if report["refusedByCode"]:
        lines.append("refusedByCode=" + json.dumps(report["refusedByCode"], ensure_ascii=False))
    support = report["support"]
    if support["available"]:
        lines.append(f"supportRate={support['rate']} ({support['counts']})")
    else:
        lines.append("supportRate=null（可用 --support-manual 补充人工/LLM judge 结果）")
    for key, label in (
        ("citationInvalidQids", "非法引用题"),
        ("unverifiedQids", "未引用/未验证题"),
        ("noCitationQids", "无引用题"),
        ("noEvidenceWrongQids", "无证据拒答失败题"),
    ):
        if qa.get(key):
            lines.append(f"{label}: {', '.join(str(q) for q in qa[key])}")
    return "\n".join(lines)


def write_support_template(manifest, path):
    queries = (manifest or {}).get("queries") or []
    template = {
        "instructions": "对每题填 true（有证据支持且引用正确）/ false（不支持或引用错误）；null=未评。",
        "support": {q.get("qid"): None for q in queries if not q.get("isNoEvidence")},
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(template, f, ensure_ascii=False, indent=2)
        f.write("\n")


def main():
    parser = argparse.ArgumentParser(description="B11 问答评测计分")
    parser.add_argument("--answers", required=True, help="B11QaEvalTest 导出的 answers.json")
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST), help="b11_qa_manifest.json")
    parser.add_argument("--support-manual", default=None, help="人工/LLM judge JSON：{qid: true|false}")
    parser.add_argument("--write-support-template", default=None, help="生成待填支持率模板 JSON")
    parser.add_argument("--out", default=None, help="把完整指标 JSON 写入文件（否则打印到 stdout）")
    args = parser.parse_args()

    answers_path = Path(args.answers)
    if not answers_path.exists():
        print(f"answers 文件不存在：{answers_path}", file=sys.stderr)
        return 2
    answers = load_json(answers_path)
    answers["_sourcePath"] = str(answers_path)

    manifest = None
    manifest_path = Path(args.manifest)
    if manifest_path.exists():
        manifest = load_json(manifest_path)
    else:
        print(f"警告：manifest 不存在（{manifest_path}），跳过 manifest 关联", file=sys.stderr)

    support_manual = load_json(args.support_manual) if args.support_manual else None
    if support_manual is not None and "support" in support_manual:
        support_manual = support_manual["support"]

    if args.write_support_template and manifest is not None:
        write_support_template(manifest, args.write_support_template)

    report = build_report(answers, manifest, support_manual)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"report written: {args.out}")
    else:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    print("--- summary ---")
    print(format_summary(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
