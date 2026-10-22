# B12 — 图谱/事件详情、设置完善、备份 v3 UI、验收与发布候选

## 目标
完成 P0 用户可见闭环与发布前验收；不改 release 自动化；不做 P1/P2。

## 范围
1) Sources 图谱：event 节点 + entity 连接（2D 列表/图）、source 过滤、分页/上限 ≤200、event 详情（title/summary/entities/chunk 原文）、无抽取空态。
2) Settings：SAG/问答/LLM 开关、预算档位（高/中/低，PRD §7.2）、抽取/网络策略说明、诊断 trace 导出（脱敏）、错误重试、隐私说明。
3) 备份 v3 UI/兼容：导出/导入、模型/空间依赖提示、旧备份导入后 SAG 空/补抽取提示、chat/trace 不导出。
4) 诊断：抽取/检索/问答 trace、错误码、失败重试。
5) 验收：三档真机 P0 矩阵、多跳/单跳回归、迁移/备份/删除/禁网/超时；P0 验收报告。
6) 文档/发布候选：README/RELEASE_NOTES/用户指南更新；准备 release candidate（不自动发布）。

## 非目标
P1/P2（多轮/工具/云同步/PDF等）；不改 release 自动化；不实现 B11 之外的生成问答增强。

## 验收
PRD §11 全项；三档设备矩阵（可用设备/限制如实记录）；B1–B7 回归全绿；release candidate 可安装/验签（若走现有流程）；无新增 P0 缺陷。
