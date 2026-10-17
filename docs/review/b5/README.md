# B5 性能基线（10k/100k 合成库）

本目录记录 P2-B5-3 的回归基线。生成方式：

```bash
./gradlew :app:testDebugUnitTest --tests "*SyntheticRetrievalScalingTest*" --info
# 在输出中搜索 B5_10K_ / B5_100K_ 前缀
```

真机侧 10k FTS 基线来自 `Fts10kPerfTest`（instrumentation，B2 已落地）：
`B2_10K_FTS_SEED chunks=10000 ... mid warm_p50_ms=5 warm_p95_ms=7`（OnePlus PJZ110/Android 16）。

> 10k 向量通道/关键词重排为纯 JVM 主机基线（不同机器绝对毫秒不可横向比较，用于同机回归比对）；
> 关键词重排每条 content = 1200 字符（300×"合成语料"）；堆字段为 JVM **used heap**（非增量/峰值，受 GC 影响）。
> 100k 规模仅断言"走流式扫描门限"，不实际分配 ~300MB 矩阵（门限单测见 `SearchStreamingTest`）。
> 注意：Robolectric 运行期自行下载的 `android-all-instrumented` 不经过 Gradle，因此不在
> `gradle/verification-metadata.xml` 的校验范围内（首次运行需联网，缓存后离线可用）。
