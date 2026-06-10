# 墨圆智选 V2 术语表

第一次阅读时只需理解下面这些词，不必先掌握公式。

| 术语 | 含义 | 在项目中出现的位置 |
|---|---|---|
| SAR | Search、Ads、Recommendation，即搜索、广告、推荐 | 项目技术名 Moyuan SAR Agent |
| Agent | 能理解任务、选择能力并组织结果的角色 | Intent、Search、Ads、Critic、Lead |
| Pi Agent | 本项目使用的 Agent 运行框架 | TypeScript 控制面 |
| ModelPort | 统一承接本地模型认证、路由和配额的网关 | 真实千问模式 |
| Replay | 不调用真实模型的确定性替身 | 默认离线学习模式 |
| Candidate | 某个召回通道返回的候选商品 | Search / Recommendation / Ads 输出 |
| Slate | 多通道融合后、等待决策的候选列表 | 决策驾驶舱候选证据带 |
| RRF | Reciprocal Rank Fusion，按各通道排名融合候选 | Python 决策服务与 TypeScript 回退 |
| SPU | 一组共享产品概念的商品，例如某款手机 | 商品层 |
| SKU | SPU 的具体规格，例如容量和颜色 | 可选择规格层 |
| Offer | 某个销售方给 SKU 的价格、库存和有效期 | 实时报价层 |
| Constraint | 用户明确或模型推断的预算、品类、偏好等限制 | 约束雷达 |
| Artifact | Agent 之间传递的版本化结构化产物 | 计划、候选、方案、审核意见 |
| Critic | 独立检查预算、证据、广告与完整性的角色 | 协作拓扑 |
| SSE | Server-Sent Events，服务端持续推送运行事件 | `/api/v2/runs/{id}/events` |
| QDSR | Qualified Decision Success Rate，合格决策成功率 | 产品 North Star 指标 |
| Recall@10 | 前 10 个结果覆盖目标商品的能力 | 离线检索门禁 |
| NDCG@10 | 前 10 个结果的排序质量，越接近 1 越好 | 离线排序门禁 |
| Fail closed | 关键证据缺失时拒绝生成可确认结果 | 价格、兼容、评论门禁 |

## 三个最重要的边界

1. LLM 可以提出策略，但不能发明商品、价格或扩大硬约束。
2. 广告可以参与候选竞争，但必须经过相关性、质量、频控和自然结果保护。
3. 购物车草案必须由用户明确确认，项目不提供支付接口。
