# 简历项目表述

## BuySense 智购决策 Agent｜多领域搜广推与组合决策平台

- **自适应编排：**主导实现 Spring Boot 智购决策平台，以 Domain Pack 隔离 3C 与露营资产；按输入完整度和复杂度选择澄清、Workflow 或 Planner-Critic，LLM 仅增强软偏好，硬约束由 Java 策略门执行。
- **搜广推决策：**实现 Search、Recommendation、Ads 三路召回、Weighted RRF 融合与兼容图组合求解；当前 Java 基准覆盖 120 个固定问句、2,400 次决策，非空结果率 100%，品类、预算和广告违规均为 0。
- **数据可信：**抽象零售 Provider SPI，以 Java HttpClient 实现 Shopify 只读适配；固定 GraphQL 查询并校验 Scope / 版本，确认前刷新价格与库存，远端快照禁止确认，7 条契约测试通过。
- **运行治理：**构建可恢复异步 Run 状态机，以数据库原子准入、有界线程池、租约续期和 fencing 防止重复执行与过期 Worker 回写；支持请求幂等、SSE 回放及确认占位，失败可重试，成功仅生成未授权支付的购物车草案。

**技术栈：** Java 17、Spring Boot、Spring JDBC、Flyway、H2 / PostgreSQL、Java HttpClient、Shopify GraphQL、SSE、React、TypeScript、LLM、Planner-Critic、Weighted RRF

## 数字口径

- Java 决策基准直接执行当前 `IntentParser + DecisionEngine`：120 个固定问句，预热 5 轮后测量 20 轮，共 2,400 次；本地目录为 14 个 SKU / 13 个 SPU。
- 非空结果率 100%，品类、预算、广告策略违规均为 0；报告见 `docs/benchmarks/buysense-java-decision-v1.json`。
- P50 / P95 仅为本地内存核心链路耗时，排除 LLM、网络与数据库，不写入简历，也不外推线上性能。
- 常规 Maven 测试仍为 49 条：48 条执行通过、0 失败、0 错误，1 条真实模型基准因无凭证跳过。
- Shopify 仅完成 Mock GraphQL 契约验证，不声称真实商城、线上库存、CTR、CVR、GMV 或支付结果。
