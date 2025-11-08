# BuySense Grilling 设计树审查

审查日期：2026-08-14

## 审查前提

用户已经确定三项上层决策：项目以 Java AI 应用开发求职为目标；工作流与 Agent 按业务复杂度选择，不追求 Agent 化本身；老师最新版能力应进入最终主链路，但不要求逐行翻译。因决策已明确，本轮不重复向用户提问，而是把每个决策的下游分支全部展开，用代码、测试和事实边界回答。

## Grading Scale

| 维度 | 权重 | 得分 | 审查结论 |
|---|---:|---:|---|
| 业务边界与落地价值 | 15% | 9.0 | 3C 购买决策主场景明确，露营包只验证领域扩展，不宣称万能电商 |
| Java 架构一致性 | 15% | 9.2 | Java 是唯一主控与零售适配运行时；Python/TypeScript 仅保留为上游参考 |
| Agent / Workflow 取舍 | 10% | 9.0 | CLARIFICATION、WORKFLOW、HYBRID 按请求单路由，模型不覆盖硬约束 |
| 外部数据与安全边界 | 15% | 9.0 | 固定 GraphQL、只读 Scope、无重定向/代理、严格 JSON、版本锁定 |
| 数据一致性与降级 | 15% | 9.1 | 普通决策缓存目录，确认前强制刷新；快照不能冒充实时报价 |
| 并发与运行治理 | 10% | 9.0 | 原子准入、有界队列、租约、fencing、恢复和确认占位均可追问 |
| 测试与数字可信度 | 10% | 9.2 | 49 条 Java 测试，专项覆盖 Shopify 七类关键边界，口径可复跑 |
| 真实环境证据 | 10% | 6.5 | 未持有真实 Shopify 店铺凭证，也未执行真实模型基准或线上交易 |
| **加权总分** | **100%** | **8.8 / 10** | **适合写入 Java AI 应用简历；仍须明确 Mock 与真实商城的边界** |

## 设计树质询

| 决策节点 | 反向质询 | 代码 / 测试证据 | 结论 |
|---|---|---|---|
| 语言边界 | Shopify 是否必须使用 Python？ | `ShopifyGraphQlClient` 和 `ShopifyRetailProvider` 均由 Java 17 实现 | 不必须；已消除 Python Bridge 运行依赖 |
| Provider 抽象 | 直接把 Shopify 代码写进 Gateway 是否更简单？ | `RetailProvider` SPI，Local、HTTP、Shopify 三种互斥实现 | 抽象有真实变化点，不是为了模式而模式 |
| 凭证语义 | 凭证是否只在生产环境需要？ | 店铺域名定位真实 Store，Admin Token 授权读取；本地 Mock 不需要 | 任何真实开发店、测试店、生产店都需要 |
| 数据范围 | Shopify 提供的到底是什么？ | Product、Variant、CNY Contextual Price、库存、标签、Metafield 评论 | 是具体店铺及店内商品数据，不是模型能力 |
| 查询控制 | 用户能否拼接任意 GraphQL？ | 客户端仅允许 Catalog、Review、Pricing 三条固定文档 | 通过，查询结构不可由外部输入控制 |
| 最小权限 | Token 能否带写权限？ | 每页检查 `currentAppInstallation.accessScopes`；无 `read_products` 或含 `write_*` 即失败 | 通过，写权限错误禁止 fallback |
| Token 外发 | 302 或系统代理会不会转发 Token？ | `Redirect.NEVER` 与 `Proxy.NO_PROXY` | 通过，Token 只发往校验后的 myshopify.com Endpoint |
| API 漂移 | Shopify 静默降级版本怎么办？ | 固定 2026-07，并核对 `X-Shopify-API-Version` | 通过，版本不一致失败关闭 |
| 目录分页 | 大目录会不会无限翻页或循环 Cursor？ | 每页 5、总量 2,000、Cursor 去重、Variant 上限 25 | 通过，有明确资源上限 |
| 普通性能 | 每次请求是否全量拉目录？ | 目录按 Domain Pack 缓存 5 分钟；评价与报价按请求刷新 | 通过，控制查询成本 |
| 确认一致性 | 缓存会不会让兼容性校验过期？ | `beforeConfirmation` 主动删除缓存，随后重拉目录、评价和报价 | 通过，已修复审查发现的陈旧窗口 |
| 报价完整性 | Provider 少返回或替换 Offer 怎么办？ | Gateway 要求 Quote ID 集合与请求集合完全一致 | 通过，未知或缺失报价不能混入结果 |
| 评论完整性 | 缺评论是错误还是合法缺失？ | found 与 missing 必须对请求 ID 做无交集完整分区 | 通过，合法缺失与异常响应可区分 |
| 脏数据 | 重复 JSON 键、小数库存、重复 GID 怎么办？ | Duplicate Detection、Integral Number、唯一 GID/SPU/SKU/Offer 校验 | 通过，歧义数据失败关闭 |
| fallback 分类 | Token 错误能否偷偷回本地数据？ | 鉴权、Scope、API 版本、请求拒绝等列为不可降级错误 | 通过，配置与权限错误不掩盖 |
| fallback 真相 | 网络故障回快照后能否确认购买？ | `confirmation_requires_remote_pricing` | 通过，样本数据不会冒充实时报价 |
| 多领域 | Shopify 商品如何归属 Domain Pack？ | 唯一 Domain Pack Tag 与 `sar_product.domain_pack_id` 双重一致 | 通过，跨领域污染被拒绝 |
| 并发成本 | 多个 Run 会不会同时打爆 Store？ | 单 Store GraphQL Client 对完整请求和响应串行化；目录再加 TTL | 当前规模合理，未来高吞吐需令牌桶或队列 |
| 主实现 CI | GitHub Actions 是否还只测旧 Python？ | 新增 `java-main` job，Temurin 17 执行 Maven 全量测试 | 通过，Java 主实现进入持续集成 |
| 指标真实性 | 49 条测试是否等于真实商城验收？ | 七条 Shopify 测试使用本地 Mock GraphQL；文档明确无真实凭证 | 通过，不混淆契约验证与线上效果 |

## 审查中发现并修复

1. 原方案为了快速吸收上游功能，Java 经 Python Bridge 间接访问 Shopify，与“Java 学习和求职主项目”目标不一致；已迁移为 Java 原生 Shopify Provider。
2. Shopify 网络安全、GraphQL 协议和领域映射曾集中在一个千行类；已拆分 `ShopifyGraphQlClient` 与 `ShopifyRetailProvider`。
3. 普通目录缓存若直接用于确认，兼容元数据可能在 TTL 内陈旧；已增加 Provider 确认钩子并在确认前强制失效缓存。
4. `canConvertToInt()` 单独使用可能接受小数并在 `intValue()` 时截断；已增加 `isIntegralNumber()` 校验。
5. 原 CI 主要验证 Python / TypeScript 上游；已新增独立 Java 17 Maven 质量门禁。
6. 新增 Java Shopify 契约测试，覆盖直连、目录分页、确认刷新、写 Scope、API 版本、重复 JSON 和瞬时故障降级。

## 最终验证

- Java：49 条测试，0 失败，0 错误，1 条真实模型基准因无凭证跳过。
- Shopify 专项：7 条 Mock GraphQL 契约测试全部通过。
- 固定业务门禁：40 条人工业务回归沿用现有验证结果。
- 合成检索基准：120 Query / 1,200 SPU，口径仍为目录属性生成标签。
- Playwright：4/4 离线用户链路沿用现有验证结果。

## 剩余风险

- 未配置真实 Shopify Store Domain 和 Admin Token，不能声称已接入真实开发店或生产商城。
- 当前为单 Store 配置；多租户店铺需要凭证隔离、Provider Registry 和每店限流。
- 当前依赖 5 分钟 TTL 与确认强刷，没有接入 Shopify Webhook 做事件驱动失效。
- 遇到 429 / THROTTLED 时进行错误分类与可选降级，尚未实现基于查询成本的自适应退避。
- 确认阶段为保证兼容元数据新鲜会重拉有界目录；大目录生产场景应改为按选中 GID 定向重验。
- 货币固定 CNY、每商品 Variant 上限 25、目录上限 2,000，扩展多币种或超大目录必须升级契约。
- 支付明确禁用，只生成购物车草案；没有线上 CTR、CVR、GMV、SLA 或交易成功率。

最终判断：本轮改造把“老师已有 Python 适配器能用”升级成了“用户能够用 Java 解释和维护的完整外部数据链路”。从 Java AI 应用求职和面试追问角度，当前版本明显强于保留 Python Bridge 的版本；其短板不再是玩具架构，而是尚未持有真实店铺与线上运行证据。