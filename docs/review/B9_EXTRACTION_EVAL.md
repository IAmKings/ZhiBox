# B9 抽取质量抽检（30–50 chunk）

> 状态：**待执行**（测试设备未配置真实 OpenAI-compatible LLM endpoint/API key；不会伪造真实质量数据）。
> 门禁目标：event 事实忠实性 ≥90%、实体类型准确率 ≥85%、每 event ≥1 entity 覆盖率 100%；100 chunk 真实抽取成功率 ≥99%。

## 1. 准备

1. 在 App「设置 → SAG 增强 / LLM」保存真实 endpoint/model/API key（仅 HTTPS），开启 **SAG 增强**，关闭 **禁止网络**。
2. 准备 30–50 个 chunk 的知识库（建议覆盖时间/人物/地点/机构/作品/指标等类型，至少 10 个文档）；导入/补抽取。
3. 或由测试方提供可用测试 endpoint + 明确授权测试数据。

## 2. 拉取样本（debug 设备）

```bash
adb exec-out run-as com.zhibox.memory cat databases/zhibox.sqlite3 > /tmp/zhibox.sqlite3
adb exec-out run-as com.zhibox.memory cat databases/zhibox.sqlite3-wal > /tmp/zhibox.sqlite3-wal
python3 scripts/export_sag_samples.py --db /tmp/zhibox.sqlite3 --out /tmp/sag_samples.json --limit 50
```

若使用非 debug 构建：先用 App 备份导出（v3 包）→ 用工具解包读取 `chunks/events/entities`，或提供只读 DB 副本。

## 3. 人工标注

对每个样本填写 `annotation`：

```json
{
  "event_faithful": true,
  "entities": [
    {"type_correct": true, "name_correct": true}
  ],
  "notes": "可选：幻觉/关系错误"
}
```

- `event_faithful`：event 内容是否**全部可由 chunk 原文支持**（不推断、不补充、不跨 chunk）；无依据/幻觉=false。
- `type_correct`：实体类型是否属于 11 类中的正确类型。
- `name_correct`：实体名是否与原文一致（别名/简称按可接受口径判断）。
- 至少 30 个、建议 50 个连续 active event；不要只挑成功项。

## 4. 评分

```bash
python3 scripts/score_sag_extraction.py --samples /tmp/sag_samples.json --out /tmp/sag_score.json
```

输出 `event_faithfulness` / `entity_type_accuracy` / `entity_coverage` / `passed`；不达标退出码 1。

## 5. 记录

把 `sag_score.json`（不含正文）与失败样本 ID/失败模式写入 `B9_FIX_REPORT.md` §4；质量不达标时调整 prompt/model 后重抽，不阻塞 B10 的 fast 路径开发，但**不关闭 B9 质量门禁**。
