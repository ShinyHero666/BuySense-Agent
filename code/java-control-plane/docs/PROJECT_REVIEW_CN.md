# BuySense 多领域智能购买决策平台：融合版架构复审

审查日期：2026-08-14

## 1. 项目定位

BuySense 以 3C 数码购买决策为主业务。用户给出预算、目标品类、用途、品牌偏好、广告偏好和套装需求后，系统完成需求解析、多路召回、融合排序、组合求解、证据校验与可确认购物车草案。

户外露营 Domain Pack 用来验证领域资产可替换，但项目不宣称支持任意商品。新增运动器材等领域时，若仍复用现有货币、库存与 connector/protocol 兼容语义，可以新增 Domain Pack；若引入尺码、场地、租赁或多币种，则必须升级公共契约与工作流版本。

本次最终融合不是把两个程序并排摆放，而是确定一条主链路：

1. Java 17 / Spring Boot 负责请求路由、Agent 编排、确定性决策、Run 生命周期和安全边界。
2. Java Provider SPI 统一 Catalog、Review、Pricing 三类外部零售数据能力，本地快照、通用 HTTP 与 Shopify 是互斥实现。
3. Java 原生 Shopify Provider 直接调用 Admin GraphQL，将上游适配器中的固定查询、分页、Scope、元数据、报价和降级规则迁入主链路。
4. TypeScript/Python 上游实现仅保留为原始契约与回归参照，不参与最终 Java 运行链路。

## 2. 工作流与 Agent 的选择

工作流和 Agent 是按任务特征选择的执行形态，不存在“Agent 一定更高级”。每个请求只走一条路径。

~~~mermaid
flowchart LR
    Q[用户需求] --> P[硬约束解析]
    P --> R{ExecutionRouter}
    R -->|缺关键输入| C[CLARIFICATION]
    R -->|信息完整且简单| W[WORKFLOW]
    R -->|套装或语义复杂| A[HYBRID Planner]
    A --> E[只增强软检索信息]
    W --> S[Search / Recommendation / Ads]
    E --> S
    S --> F[Weighted RRF]
    F --> O[有界全局组合求解]
    O --> V[评论 / 报价 / 兼容证据]
    V --> K[Critic]
    K -->|APPROVE| D[可确认提案]
    K -->|RETRIEVE| B[最多一次补召回]
    K -->|CLARIFY| C
    B --> D
    D --> X[确认前刷新价格库存兼容性]
    X --> Y[购物车草案，支付禁用]
~~~

- WORKFLOW：预算、品类、广告政策、库存和兼容性由确定性 Java 服务执行。
- HYBRID：Planner 处理别名、多目标、模糊用途和检索词扩展；Critic 复核方案，但不能覆盖用户硬约束。
- CLARIFICATION：缺预算或目标品类时，在召回前停止，只提出一个高价值问题。
- fallback：模型故障回到确定性链路；零售数据远端故障默认失败关闭。

## 3. 老师最新版能力的融合结果

| 老师最新版能力 | 最终融合位置 | 状态 |
|---|---|---|
| normal-3c-v1 与 outdoor-camping-v1 | Java Domain Pack Registry 与两套版本化资产 | 已吸收 |
| Capability / Workflow Registry | Java 注册 10 项能力和有界委派边 | 已吸收 |
| 搜索、推荐、广告与兼容证据 | Java 决策主链路 | 已吸收并保留自适应路由 |
| 通用 HTTP 零售 Provider | Java `HttpRetailProvider`，作为可选外部数据实现 | 已吸收 |
| Shopify 只读适配器 | Java `ShopifyRetailProvider` 直接调用 Admin GraphQL 2026-07 | 已迁移并进入主链路 |
| 来源健康、显式 fallback | Java 单一主链路的来源校验、健康状态与失败关闭 | 已吸收 |
| Run、SSE、取消、幂等 | Java 持久化 Run 状态机 | 已吸收 |
| 有界并发与队列 | Java 可配置线程池与数据库原子准入 | 已吸收并加强跨实例竞态 |
| 租约、fencing 与恢复 | Java 租约抢占、续租、旧 Worker 写隔离和过期 Run 扫描 | 已吸收 |
| 确认前刷新报价 | Java 重新加载价格、库存和兼容关系 | 已吸收 |
| 提案只能成功确认一次 | 数据库确认占位；失败/取消释放后可重试 | 已吸收 |
| 启动与凭证安全门禁 | 环境变量、固定查询、只读 Scope、HTTPS、禁重定向/代理、响应体上限 | 已吸收并 Java 化 |

“已吸收”指业务行为进入最终主链路并有测试覆盖，不表示逐行翻译上游代码。考虑到本项目以 Java 学习和求职展示为目标，Shopify 适配器不再作为 Python 旁路复用，而是按同一业务契约迁入 Java；固定拓扑中不必要的全角色模型调用仍由自适应路由替换。

## 4. 四个可追问的工程点

### 4.1 版本化领域与有界编排

Domain Pack 承载品类、同义词、用途、品牌、目录、评论和兼容图；稳定内核承载 Run、路由、搜广推、组合优化和安全策略。Capability Registry 注册能力，Workflow Registry 声明允许的委派边，未登记的 source 到 target 在执行前被拒绝。

追问重点：为什么 Domain Pack 不是万能插件；什么时候必须升级公共 Schema；如何验证第三个领域包没有悬空能力。

### 4.2 多路召回与全局组合求解

Search、Recommendation、Ads 原始分数不可直接横向比较，因此先按通道排名执行 Weighted RRF，再进行资格和策略校验。拒绝广告只关闭 Ads 通道，不误删自然搜索命中的同一商品。套装不采用逐品类 Top1 贪心，而是在有界候选空间枚举组合，同时满足总预算、品类覆盖、库存和 connector/protocol 兼容规则。

追问重点：RRF 为什么不能替代业务规则；全局枚举复杂度如何受控；广告为什么必须单独披露和限位。

### 4.3 外部数据与确认一致性

Catalog、Review、Pricing 由 Java Provider SPI 统一。通用 HTTP 实现校验 Bearer、来源指纹、Content-Type、响应体上限和严格 JSON；Shopify 实现只允许三条固定 GraphQL 查询，要求 `read_products` 且拒绝任意 `write_*` Scope，禁用重定向和代理转发，锁定 Admin API 2026-07，并校验 GID、Domain Pack 标签、Metafield、分页游标与 CNY 上下文价格。

Shopify 目录在普通决策中按 5 分钟 TTL 缓存，避免每次决策全量翻页；评价与报价每次加载都刷新。确认会先失效目录缓存，再重新获取目录元数据、报价、库存和兼容性，而不是复用旧提案金额。若远端 Provider 故障后返回本地 fallback，确认失败关闭，避免把本地样本冒充实时时价。

Java Mock GraphQL 契约测试覆盖直连鉴权头、目录/评价/报价映射、缓存命中、确认前强制目录刷新与价格变化、API 版本降级、写 Scope 拒绝、瞬时故障降级及 fallback 确认阻断；最终链路不需要 `server.py` 或 Python 进程。

### 4.4 可恢复 Run 与并发安全

服务端签发会话身份，Run、取消、SSE 和确认均校验同会话所有权。请求幂等键绑定完整请求语义，不能用相同键替换消息、Domain Pack、Workflow 或 proposalRunId。

准入检查与 Run 插入使用数据库全局锁放在同一事务，避免两个实例同时通过“先计数后插入”。Worker 获取带 token 的租约后才能提交事件和终态；租约定时续期，过期 Run 被扫描恢复，旧 Worker 的写入因 fencing token 不匹配而回滚。

同一提案只保留一个有效确认占位。确认失败或取消时，事务释放占位，允许新幂等键重试；成功确认保留占位并只生成 paymentAuthorized=false 的购物车草案。

## 5. 验证结果与数字口径

| 层级 | 结果 | 口径 |
|---|---:|---|
| Java 单元、集成、契约测试 | 49 条，0 失败，0 错误，1 跳过 | 新增 7 条 Java Shopify 契约测试；跳过项为无凭证真实模型基准 |
| 人工业务回归 | 40 条，全部通过 | 覆盖路由、任务完成、澄清、套装、硬约束和广告政策 |
| 固定合成检索基准 | 120 Query / 1,200 SPU | Recall@10 95.83%，NDCG@10 99.57%，硬过滤与广告违规为 0 |
| Playwright E2E | 4/4 通过 | 离线端到端业务链路 |

120 Query 的标签由目录属性生成，不是人工金标。当前没有提交真实 Shopify 凭证，也没有可复核的线上 CTR、CVR、GMV 或支付结果，因此简历不得把上述指标写成线上业务效果。

## 6. 方案复审结论

最终版本相对老师最新版不是简单“换成 Java”，而是三项结构性迭代：

1. 固定业务阶段仍保留，但请求级路由会根据输入完整度和复杂度选择澄清、确定性工作流或混合 Agent，减少无效模型调用。
2. 将老师最新版的 Shopify 商品、评价、报价、分页、Scope 和失败关闭语义迁入 Java Provider SPI，消除 Python Bridge 运行依赖，同时保留外部数据与决策内核的边界。
3. Java Run 层补齐数据库原子准入、队列上限、租约续期、fencing、过期恢复、确认实时重验和失败重试，避免只停留在演示工作流。

剩余真实边界：

- 未配置真实 Shopify 凭证，不能声称已接入线上商城。
- 普通测试跳过真实模型基准，不能声称已测得真实模型 Token、延迟或准确率。
- 当前目录仍是参考快照，合成检索结果不能外推为线上用户行为。
- 支付明确禁用，系统输出的是可确认购物车草案，不是交易系统。