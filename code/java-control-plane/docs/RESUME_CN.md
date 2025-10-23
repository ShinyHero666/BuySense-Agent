# 简历项目表述

## BuySense 智购引擎｜3C 商品智能决策与搜广推系统

- 主导设计并实现 Java 17 / Spring Boot 自适应决策架构，按品类跨度、套装关系、多目标与信息完整度，将请求路由至确定性工作流或 Planner-Critic 混合链路；为模型超时、协议错误及非结构化输出设置确定性降级，确保 LLM 失败不绕过业务约束。真实接入 DeepSeek 对 4 条复杂问句进行双轨校准：工作流 0 次模型调用 / 0 Token，混合链路 8 次调用 / 2,282 Token，降级 0 次。
- 构建 Search、Recommendation、Ads 三路召回与 Weighted RRF 融合排序，依次执行预算/品类硬过滤、广告位约束和兼容性校验；针对多品类套装采用有界全局枚举，返回最高分可行解及备选解。40 条人工业务回归覆盖单品、套装、拒绝广告和澄清场景，硬约束及广告策略违规均为 0。
- 设计具备来源与强度的约束模型，将预算、必选品类和拒绝广告等用户事实固化为不可覆盖字段，仅允许 Query Rewrite 扩展检索词与软偏好；以 APPROVE / RETRIEVE / CLARIFY 协议、单次补召回上限和确定性 Policy Gate 约束 Critic，阻断预算篡改、无效补召回与过度澄清。
- 实现可恢复 Run 状态机，将幂等创建、异步执行、SSE 事件、结果持久化与购物车草案纳入同一执行生命周期；端到端回归验证同一幂等键复用 Run、信息不足在召回前转入澄清、服务重载后事件与结果可恢复，并以 paymentAuthorized=false 强制保留人工确认边界。

**技术栈：** Java 17、Spring Boot、Spring JDBC、Flyway、H2/PostgreSQL、SSE、React、TypeScript、ECharts、LLM、Agent、Weighted RRF
