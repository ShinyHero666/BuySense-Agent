# 简历项目表述

## BuySense 智购决策 Agent｜多领域搜广推与组合决策平台

- 主导设计并实现 Java 17 / Spring Boot 自适应编排层，以版本化 Domain Pack 与 Capability / Workflow Registry 解耦 3C、户外露营的品类词表、目录、评论证据和兼容规则，注册 10 项能力及有界委派图；根据关键信息完整度、品类跨度和套装复杂度，将请求单路由至 CLARIFICATION、确定性 WORKFLOW 或 Planner-Critic HYBRID，避免简单请求产生无效模型调用。
- 构建 Search、Recommendation、Ads 三路召回与 Weighted RRF 融合排序，将预算、必选品类和拒绝广告固化为不可覆盖的用户硬约束，仅允许 Query Rewrite 扩展检索词与软偏好；针对多品类套装采用兼容图约束的有界全局枚举，返回最高分可行解及备选。固定合成检索基准覆盖 120 条 Query / 1,200 SPU，Recall@10 达 95.83%、NDCG@10 达 99.57%，硬过滤与广告策略违规均为 0。
- 设计 Catalog、Review、Pricing 三类可插拔零售 Provider 契约，校验鉴权、来源指纹、版本一致性、评论 ID 分区和报价集合，并通过超时、响应体上限、禁止重定向、严格 JSON 解析及非本机 HTTP 显式授权收紧外部数据边界；支持显式本地快照降级，在健康接口暴露实际来源、错误码与请求/降级计数。
- 实现服务端会话隔离与可恢复 Run 状态机，将请求绑定幂等、异步执行、取消、SSE 事件回放、结果持久化和购物车草案纳入同一生命周期；确认阶段校验同会话、原提案与金额指纹，跨会话统一返回 404，并以 `paymentAuthorized=false` 保留人工交易边界。40 条人工业务回归覆盖路由、澄清、套装和拒绝广告场景，4 条真实浏览器链路全部通过。

**技术栈：** Java 17、Spring Boot、Spring JDBC、Flyway、H2 / PostgreSQL、SSE、React、TypeScript、ECharts、LLM、Planner-Critic、Weighted RRF、HTTP Provider

## 数字口径

- `120 Query / 1,200 SPU`：固定种子生成的合成确定性检索回归，标签来自目录属性，不冒充人工金标或线上效果。
- `40 条`：人工编写的 Java 业务回归，门禁覆盖路由、任务完成、澄清、硬约束与广告策略。
- `4 条浏览器链路`：Playwright 离线端到端用例；真实 LLM 与真实 Shopify 未配置凭证时不计入已验证结果。