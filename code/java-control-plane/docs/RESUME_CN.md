# 简历项目表述

## BuySense 智购引擎｜3C 商品智能决策与搜广推系统

- 主导设计并实现 Java 17 / Spring Boot 自适应执行架构，依据品类数、套装、多目标与信息完整度，将请求单路由至确定性工作流或 Planner-Critic 混合链路；模型超时或结构化输出异常时无损降级，避免简单请求承担额外 LLM 延迟与 token 成本。
- 构建 Search、Recommendation、Ads 多路召回及 Weighted RRF 融合排序，加入预算/品类硬过滤、前三位广告保护和兼容性校验；针对多品类套装采用有界全局枚举求解最优解与备选解，避免逐项贪心导致超预算或设备不兼容。
- 设计带来源与强度的约束模型，将预算、必选品类和广告偏好固化为不可覆盖字段；限制 Query Rewrite 仅增强检索词与软偏好，并以 APPROVE / RETRIEVE / CLARIFY 协议、单次补召回上限和确定性策略门约束 Critic 的决策权限。
- 实现可恢复 Run 状态机，支持幂等创建、任务取消、SSE 事件回放、执行状态恢复与购物车草案确认，使长链路可追踪、可重放且不会重复执行；以 40 条业务回归和 4 条真实 DeepSeek 双轨问句验证，硬约束/广告策略违规为 0，混合链路 8 次模型调用无降级。

**技术栈：** Java 17、Spring Boot、Spring JDBC、Flyway、H2/PostgreSQL、SSE、React、TypeScript、ECharts、LLM、Agent、Weighted RRF