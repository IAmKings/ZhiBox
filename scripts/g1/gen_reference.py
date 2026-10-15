#!/usr/bin/env python
"""G1 判定门参考向量生成（桌面端，实施计划 P0-3）。

用 HuggingFace 官方权重 google/embeddinggemma-2（纯文本模式）按官方任务规范生成两组参考向量：
  - query_mode:    prompt_name="SearchQuery"
  - document_mode: 手工格式 "title: none | text: {text}"（不传 prompt，避免双重前缀）

产物 reference.json 与端侧三模板模式（RAW / DOCUMENT_PREFIX / QUERY_PREFIX）对照，
余弦 ≥ 0.99 判定 G1 通过（实施计划判定门标准）。

用法:
  .venv-g1/bin/python scripts/g1/gen_reference.py [--out scripts/g1/reference.json]
"""
import argparse
import datetime
import json
import os
import sys
from pathlib import Path

# 国内网络：HF 直连被阻断，强制走 hf-mirror；模型缓存放项目内（gitignored）
ROOT = Path(__file__).resolve().parents[2]
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HOME", str(ROOT / ".hf-cache"))

MODEL_ID = "google/embeddinggemma-2"
DOC_TEMPLATE = "title: none | text: {}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(Path(__file__).parent / "reference.json"))
    parser.add_argument("--samples", default=str(Path(__file__).parent / "samples.json"))
    args = parser.parse_args()

    from sentence_transformers import SentenceTransformer

    print(f"[1/4] 加载 {MODEL_ID}（text-only；经 {os.environ['HF_ENDPOINT']}）...")
    model = SentenceTransformer(
        MODEL_ID,
        config_kwargs={"vision_config": None, "audio_config": None},
    )

    revision = ""
    try:
        from huggingface_hub import model_info
        revision = model_info(MODEL_ID).sha or ""
    except Exception as e:  # 网络异常不阻断
        print(f"  warning: 无法获取 revision: {e}")
    print(f"  revision: {revision or 'unknown'}")
    print(f"  维度: {model.get_sentence_embedding_dimension()}")

    samples = json.loads(Path(args.samples).read_text(encoding="utf-8"))["samples"]
    print(f"[2/4] 样本 {len(samples)} 条；开始编码（每组两种模式）...")

    vectors = {}
    for i, s in enumerate(samples):
        q = model.encode(
            s["text"], prompt_name="SearchQuery", normalize_embeddings=True
        ).tolist()
        d = model.encode(
            DOC_TEMPLATE.format(s["text"]), normalize_embeddings=True
        ).tolist()
        vectors[s["id"]] = {"query_mode": q, "document_mode": d}
        if (i + 1) % 10 == 0:
            print(f"  {i + 1}/{len(samples)}")

    out = {
        "generator": "gen_reference.py",
        "model_id": MODEL_ID,
        "revision": revision,
        "dim": model.get_sentence_embedding_dimension(),
        "generated_at": datetime.datetime.now().isoformat(timespec="seconds"),
        "prompt_policy": {
            "query_mode": "prompt_name=SearchQuery",
            "document_mode": "format=title: none | text: {} (no prompt)",
        },
        "samples": samples,
        "vectors": vectors,
    }
    Path(args.out).write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(f"[4/4] 已写出 {args.out}（{len(vectors)} 条 × 双模式 × {out['dim']}d）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
