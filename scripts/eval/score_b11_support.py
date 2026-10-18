#!/usr/bin/env python3
"""B11 支持率评测计分/模板（50 条抽样，task-58）。

复用 `scripts/eval/score_b11_qa.py` 的指标实现（citation validity / fullyCitedRate /
unreferenced / p50-p95 / supportRate），默认对接 B11SupportEvalTest 的
`support-answers.json` 与 `b11_support_manifest.json`。

用法：
  # 1) 生成支持率待填模板（{qid: null}，人工/LLM judge 填 true/false）
  python3 scripts/eval/score_b11_support.py --write-support-template
  # 2) 计分（未提供 manual 时 supportRate=null，规则指标仍输出）
  python3 scripts/eval/score_b11_support.py --answers support-answers.json --out support-score.json
  # 3) 填好模板后
  python3 scripts/eval/score_b11_support.py --answers support-answers.json \
      --support-manual b11_support_template.json --out support-score.json

指标：supportRate（人工/LLM judge）、citationValidRate（ID 级引用合法）、fullyCitedRate
（逐句有引用：idLevelValid 且 unreferencedSentenceCount==0）、unreferencedCases、invalid/unverified、
successRate、refusedByCode、p50/p95。门禁未开（gateOpen=false）时明确标注指标不可用于质量结论。
"""
import argparse
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
import score_b11_qa as base  # noqa: E402  (同目录复用指标实现)

DEFAULT_MANIFEST = SCRIPT_DIR / "b11_support_manifest.json"
DEFAULT_TEMPLATE = SCRIPT_DIR / "b11_support_template.json"


def write_template(manifest, path):
    queries = (manifest or {}).get("queries") or []
    template = {
        "instructions": (
            "对每题填 true（答案被证据支持且引用正确）/ false（不支持或引用错误）；null=未评。"
            "只改 support 映射，不要改 qid。"
        ),
        "source": "B11SupportEvalTest（b10 q001..q050）",
        "support": {q.get("qid"): None for q in queries if not q.get("isNoEvidence")},
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(template, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"support template written: {path}")


def print_support_summary(report):
    counts = report["counts"]
    qa = report["qa"]
    lines = []
    if report.get("runGated"):
        lines.append("!! gateOpen=false：有证据题全部被拒（无 key/未开联网/未同意/未开启问答），指标不可用于质量结论。")
    lines.append(
        f"records={counts['total']} answered={counts['answered']} refused={counts['refused']} "
        f"successRate={qa['successRate']}"
    )
    lines.append(
        f"supportRate={report['support']['rate']} "
        f"citationValidRate={qa['citationValidRate']} fullyCitedRate={qa.get('fullyCitedRate')}"
    )
    p50 = qa["latencyP50Ms"]
    p95 = qa["latencyP95Ms"]
    latency = "p50=null p95=null（无已作答记录）" if p50 is None else f"p50={p50}ms p95={p95}ms"
    lines.append(
        f"unreferencedCases={qa.get('unreferencedCases')} invalid={qa['citationInvalidCases']} "
        f"unverified={qa['unverifiedCases']} {latency}"
    )
    if report["refusedByCode"]:
        lines.append("refusedByCode=" + json.dumps(report["refusedByCode"], ensure_ascii=False))
    if qa.get("unreferencedQids"):
        lines.append("unreferencedQids: " + ", ".join(str(q) for q in qa["unreferencedQids"]))
    if qa.get("citationInvalidQids"):
        lines.append("invalidQids: " + ", ".join(str(q) for q in qa["citationInvalidQids"]))
    if report["support"]["available"]:
        lines.append(f"supportManifest: {report['support']['counts']}")
    else:
        lines.append("supportRate=null（先用 --write-support-template 生成模板并人工/LLM judge 填 true/false）")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="B11 支持率评测计分/模板（50 条抽样）")
    parser.add_argument("--answers", default=None, help="support-answers.json（从设备 pull 出）")
    parser.add_argument("--manifest", default=str(DEFAULT_MANIFEST), help="b11_support_manifest.json")
    parser.add_argument("--support-manual", default=None, help="人工/LLM judge JSON：{qid: true|false}")
    parser.add_argument(
        "--write-support-template",
        nargs="?",
        const=str(DEFAULT_TEMPLATE),
        default=None,
        help=f"生成支持率待填模板（省略路径时写 {DEFAULT_TEMPLATE.name}）",
    )
    parser.add_argument("--out", default=None, help="把完整指标 JSON 写入文件（否则打印到 stdout）")
    args = parser.parse_args()

    manifest = None
    manifest_path = Path(args.manifest)
    if manifest_path.exists():
        manifest = base.load_json(manifest_path)
    else:
        print(f"警告：manifest 不存在（{manifest_path}）", file=sys.stderr)

    if args.write_support_template:
        if manifest is None:
            print("生成模板需要 manifest（--manifest）", file=sys.stderr)
            return 2
        write_template(manifest, args.write_support_template)

    if not args.answers:
        # 只生成模板/查看 manifest 元信息时允许不带 answers
        if manifest is not None:
            print(json.dumps({
                "manifest": str(manifest_path),
                "total": len(manifest.get("queries") or []),
                "selection": manifest.get("selection"),
            }, ensure_ascii=False, indent=2))
            return 0
        print("缺少 --answers", file=sys.stderr)
        return 2

    answers_path = Path(args.answers)
    if not answers_path.exists():
        print(f"answers 文件不存在：{answers_path}", file=sys.stderr)
        return 2
    answers = base.load_json(answers_path)
    answers["_sourcePath"] = str(answers_path)

    support_manual = base.load_json(args.support_manual) if args.support_manual else None
    if support_manual is not None and "support" in support_manual:
        support_manual = support_manual["support"]

    report = base.build_report(answers, manifest, support_manual)
    report["harness"] = "B11SupportEvalTest"
    report["manifest"] = str(manifest_path)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"report written: {args.out}")
    else:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    print("--- support summary ---")
    print(print_support_summary(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
