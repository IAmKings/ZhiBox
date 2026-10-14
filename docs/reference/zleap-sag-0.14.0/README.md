# 参考素材：zleap-sag 0.14.0（只读）

本目录存放从上游提取的参考源码，作为端侧（Kotlin）实现的**行为对照**，不参与编译、不修改、不翻译。

## 来源与校验

| 项目 | 值 |
|---|---|
| 来源包 | `zleap-sag==0.14.0`（PyPI wheel，py3-none-any，3,327,527 bytes） |
| wheel SHA-256 | `c58d12f1af0c07010a966da16112337ce92734b164832af7a708f049ad7bc429` |
| `sag-api/octx_vector_protocol.py` | 取自 SAG 主仓库 commit `56f073c1c0c300b46707c7249d48732730656cb0` 的 `apps/api/sag_api/sag/octx_vector_protocol.py` |
| 提取日期 | 2026-10-15 |
| 提取任务 | 实施计划 P0-1（docs/SAG_Android_MVP_Implementation_Plan_v1.0.md 第 9 节） |

## 文件与用途映射

| 路径 | 用途 | 服务于 |
|---|---|---|
| `zleap/sag/core/storage/index_schemas.py` | 4 张 collection 的字段/向量列/FTS 列/keyword 列声明（source_chunks 双向量、event_vectors_wide、entity_vectors、event_entity_vectors） | 端侧 Room schema 设计对照（P1-W1-1） |
| `zleap/sag/db/models.py` | 关系库完整表定义（data_source/kb_document/article/entity/EventEntity/sag_operation 等） | Phase 2 关系层对照 |
| `zleap/sag/core/storage/lancedb_store.py` | `vector_search`/`search` 的过滤、投影、cosine 分数与 upsert 语义（17 方法 VectorStore 契约） | `VectorIndex` 行为正确性对照（P1-W2-1） |
| `zleap/sag/pipeline/chunk.py` | 分块策略（chunk 大小/边界/heading 处理） | Chunker 对照（P1-W1-2） |
| `zleap/sag/pipeline/`（其余） | 解析→分块→embedding→抽取→索引的完整管线编排（runner/ports/contracts） | 导入管线设计对照（P1-W1-4） |
| `zleap/sag/prompts/extract_document.yaml`、`rerank.yaml` 等 | 事件/实体抽取与重排的提示词 | Phase 2/3 备用，本期存档 |
| `sag-api/octx_vector_protocol.py` | 六向量角色（chunk.heading/content、event.title/content、entity.name、event_entity.relation）+ 文本规范化 + 向量指纹设计 | `chunk_embedding.role` 与 embedding space 管理对照（P1-W1-1/W1-3） |

## 升级流程

升级 SAG 参考版本时（须独立 PR）：

1. 下载新版本 wheel，记录版本号与 SHA-256；
2. 将本目录复制为 `docs/reference/zleap-sag-<new>/`，不做原位覆盖；
3. 对两个目录做 diff，重点核对 `index_schemas.py`（collection 声明）、`chunk.py`（分块参数）、`octx_vector_protocol.py`（角色与指纹）的变更；
4. 更新 `docs/UPSTREAM_VERSIONS.md` 锚点表。
