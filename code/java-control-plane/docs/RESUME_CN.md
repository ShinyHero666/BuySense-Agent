# 简历项目表述

## BuySense 智购引擎｜3C 商品智能决策与搜广推系统

- 主导设计并实现 Java 17 / Spring Boot 自适应决策架构，基于品类数、套装、多目标与信息完整度将请求单路由至确定性工作流或 Planner-Critic 混合链路，支持模型异常无损降级，避免简单请求承担 LLM 延迟与 token 成本。
- 构建 Search、Recommendation、Ads 多路召回与 Weighted RRF 融合排序，加入前三位广告保护、预算/品类硬过滤及兼容性校验，并通过有界全局枚举生成套装最优解与备选方案，避免逐项贪心造成超预算或不兼容。
- 设计约束来源与强度模型，限制 Query Rewrite 仅增强检索词和软偏好；实现 Critic 的 `APPROVE/RETRIEVE/CLARIFY` 协议、最多一次补充检索及确定性策略门，阻止 LLM 篡改硬约束或因软偏好过度追问。
- 建立 40 条人工业务回归集，覆盖工作流、混合路由、套装、拒绝广告和澄清场景，硬约束与广告策略违规均为 0；完成 4 条复杂问句的真实 DeepSeek 双轨基准，混合链路 8 次调用 0 降级、任务完成率 100%。

**技术栈：** Java 17、Spring Boot、Spring JDBC、Flyway、H2/PostgreSQL、SSE、React、TypeScript、ECharts、LLM、Agent、RRF
