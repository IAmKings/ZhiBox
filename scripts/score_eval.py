#!/usr/bin/env python3
"""
W3 评测计分：读取 EvalSetRunner 原始排名 JSON，输出三通道指标。
指标：Recall@5/10、MRR@10、NDCG@10（二值相关）；分类型（fact/synonym/multihop/exact）与语言；
对抗类单独报告：关键词通道误报率（命中任何文档即为误报）与向量 Top1 分布。

用法: python3 scripts/score_eval.py <eval_results.json> [--tag 标签]
"""
import argparse
import json
import math
from collections import defaultdict

K = 10


def channel_metrics(pairs):
    """单个通道指标：pairs = [(ranked_docs, relevant_set)]（只含有效相关集）。"""
    n = len(pairs)
    if n == 0:
        return None
    rec5 = rec10 = mrr = ndcg = 0.0
    for ranking, rel in pairs:
        hits5 = sum(1 for x in ranking[:5] if x in rel)
        hits10 = sum(1 for x in ranking[:K] if x in rel)
        rec5 += min(1.0, hits5 / len(rel))
        rec10 += min(1.0, hits10 / len(rel))
        for i, x in enumerate(ranking[:K]):
            if x in rel:
                mrr += 1.0 / (i + 1)
                break
        rels = [1 if x in rel else 0 for x in ranking[:K]]
        ideal_len = min(len(rel), K)
        idcg = sum(1.0 / math.log2(i + 2) for i in range(ideal_len))
        dcg = sum(1.0 / math.log2(i + 2) for i, v in enumerate(rels) if v)
        ndcg += dcg / idcg if idcg else 0.0
    return {
        "n": n,
        "R@5": round(rec5 / n, 4),
        "R@10": round(rec10 / n, 4),
        "MRR@10": round(mrr / n, 4),
        "NDCG@10": round(ndcg / n, 4),
    }


def build_report(path, tag):
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)

    channels = ["vector_docs", "keyword_docs", "fused_docs"]
    grouped = defaultdict(lambda: defaultdict(list))  # bucket -> channel -> [(ranking, rel)]
    adversarial = {"keyword_fp": 0, "vector_any": 0, "n": 0}

    for row in data["rows"]:
        if row.get("refused"):
            continue
        if row["type"] == "adversarial":
            adversarial["n"] += 1
            adversarial["keyword_fp"] += bool(row.get("keyword_docs"))
            adversarial["vector_any"] += bool(row.get("vector_docs"))
            continue
        rel = set(row.get("relevant") or [])
        if not rel:
            continue
        for bucket in ("ALL", f"type:{row['type']}", f"lang:{row['lang']}"):
            for channel in channels:
                grouped[bucket][channel].append((row.get(channel) or [], rel))

    report = {
        "file": path.split("/")[-1],
        "tag": tag,
        "queries": data["n_queries"],
        "refused": data.get("refused", 0),
        "avg_query_ms": data.get("avg_ms"),
        "adversarial": {
            key: (round(value / adversarial["n"], 3) if key != "n" and adversarial["n"] else value)
            for key, value in adversarial.items()
        },
    }
    for bucket in sorted(grouped):
        report[bucket] = {
            channel.replace("_docs", ""): channel_metrics(pairs)
            for channel, pairs in grouped[bucket].items()
        }
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="W3 评测计分（三通道 Recall/MRR/NDCG + 对抗误报）",
    )
    parser.add_argument("path", help="EvalSetRunner 原始排名 JSON（docs/phase1/eval-results/*.json）")
    parser.add_argument("--tag", default=None, help="报告标签（如 路径A/440M）")
    args = parser.parse_args(argv)
    print(json.dumps(build_report(args.path, args.tag), ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
