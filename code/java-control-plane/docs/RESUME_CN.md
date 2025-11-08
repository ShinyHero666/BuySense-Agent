# 简历项目表述

## BuySense 智购决策 Agent｜多领域搜广推与组合决策平台

- 主导设计并实现 Java 17 / Spring Boot 自适应编排层，以版本化 Domain Pack 和 Capability / Workflow Registry 解耦 3C、户外露营的词表、目录、评论证据与兼容规则，注册 10 项能力及有界委派图；依据输入完整度、品类跨度和套装复杂度，将请求单路由至 CLARIFICATION、确定性 WORKFLOW 或 Planner-Critic HYBRID，避免简单请求产生无效模型调用。
- 构建 Search、Recommendation、Ads 三路召回与 Weighted RRF 融合排序，将预算、必选品类和拒绝广告固化为不可覆盖的用户硬约束，仅允许 Query Rewrite 增强检索词与软偏好；针对多品类套装采用兼容图约束的有界全局枚举。固定种子基准覆盖 120 条 Query / 1,200 SPU，Recall@10 为 95.83%、NDCG@10 为 99.57%，硬过滤及广告策略违规为 0。
- 设计 Catalog、Review、Pricing 三类可插拔 Provider 契约，基于 Java HttpClient 原生接入 Shopify Admin GraphQL 2026-07，通过固定查询白名单、只读 Scope、禁用重定向与代理转发、严格 JSON、API 版本及 ID 完整性校验收紧外部数据边界；普通决策目录按 5 分钟 TTL 缓存、评价与报价按请求刷新，确认前主动失效目录缓存并重新校验价格、库存与兼容性，远端降级为本地快照时失败关闭。
- 实现服务端会话隔离与可恢复 Run 状态机，将数据库原子准入、有界线程池、请求绑定幂等、租约续期与 fencing、过期任务恢复、取消、SSE 回放和确认占位纳入同一生命周期；确认失败或取消后可安全重试，成功后仅生成 paymentAuthorized=false 的购物车草案。49 条 Java 单元、集成及契约测试实现 0 失败、0 错误。

**技术栈：** Java 17、Spring Boot、Spring JDBC、Flyway、H2 / PostgreSQL、Java HttpClient、Shopify GraphQL、SSE、React、TypeScript、LLM、Planner-Critic、Weighted RRF

## 数字口径

- 120 Query / 1,200 SPU：固定种子生成的合成检索回归，标签来自目录属性，不是人工金标或线上效果。
- Recall@10 95.83%、NDCG@10 99.57%：仅对应上述合成检索集。
- 49 条 Java 测试：本地可复跑；其中真实模型基准因无凭证默认跳过，未计入模型效果。
- 未配置真实 Shopify 凭证，不声称线上商城、CTR、CVR、GMV 或支付结果。