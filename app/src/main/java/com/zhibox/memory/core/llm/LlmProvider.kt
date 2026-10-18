package com.zhibox.memory.core.llm

/**
 * 远程 LLM 抽象（B8 只定义连通性测试；B11 会在此接口上扩展精排/引用问答/流式生成）。
 *
 * 实现约定：
 * - 禁止在实现内写日志（PRD/项目约定：密钥与正文不入日志，本模块直接不接入日志）；
 * - 失败以 [LlmTestResult.Failure] 返回值表达，不向调用方抛业务异常；
 * - 网络/Keystore 等阻塞操作不得在调用线程执行（实现内部切 IO 线程）。
 */
interface LlmProvider {
    /**
     * 测试与远程 LLM 的连通性（最小成本一次 `/chat/completions` 请求）。
     *
     * 行为保证：
     * - 禁网（[NetworkPolicy] 不允许）时直接返回 [LlmErrorCode.OFFLINE]，**不发起任何网络请求**；
     * - 仅允许 HTTPS（[LlmErrorCode.INSECURE_URL]）；
     * - 错误码/文案可读且不含 API Key / 请求正文。
     *
     * @param config 待测试的连接配置（可不落盘）。
     */
    suspend fun testConnection(config: LlmConfig): LlmTestResult
}
