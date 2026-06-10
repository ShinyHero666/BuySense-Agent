# 18 个实验参考解答

这份文件提供实现方向和关键检查点。完整参考实现就是当前 `src/shoprec` 基线；建议先独立完成，再对照源码。

## Lab 1：跟踪搜索请求

入口是 `SearchEngine.search`。沿 Candidate 查看：

```text
recall_sources -> features -> rough -> rank -> rerank -> result
```

用 `--view explain` 同时查看 Query 决策、阶段漏斗和商品级解释；需要机器可读 JSON 时再使用 `--view full`。

## Lab 2：Query 理解

`QueryAnalyzer.analyze` 的顺序必须保持：

```text
normalize -> correction -> synonym -> intent -> risk -> broad decision
```

泛 Query 判断先检查品牌/数字等精确信号，再检查泛词词典。

## Lab 3：多路召回

合并不能覆盖旧来源：

```python
candidate = merged.setdefault(product.product_id, Candidate(product))
candidate.recall_sources.add(source)
```

验收重点是 `p001` 同时保留 lexical、category_intent 和 brand_intent。

## Lab 4：过滤数量守恒

每个删除候选记录一个主理由：

```text
filter.input = filter.output + sum(filter_reasons)
```

如果希望记录多个理由，需要另建 secondary reasons，不能混入主理由计数。

## Lab 5：搜索实验

实验参数从 `config/experiments.json` 加载。用稳定 token 命中各版本，再比较同一 Query。配置修改后必须创建新 Service，因为示例不做热加载。

## Lab 6：重排解释

重排前记录 `before_position`，选出结果后记录 `after_position`。发生移动时写入 `POSITION_CHANGED`；类目或卖家惩罚分别记录独立理由。

## Lab 7：推荐召回

推荐使用 interest、item_cf、hot、fresh 四路。合并逻辑和搜索相同，但触发器来自用户兴趣和最近点击，而不是文本 Query。

## Lab 8：Flow Pool

先计算整数目标：

```text
control(size=8)      -> interest=5, explore=2, fresh=1
explore_more(size=8) -> interest=3, explore=3, fresh=2
```

某池不足时从全局模型顺序补量，因此 `pool_targets` 和 `pool_counts` 可能不同。最终规则重排必须消费目标，否则实验只会停留在日志中。

## Lab 9：曝光 TTL

Key 使用 `(user_id, scene, product_id)`，Value 是过期时间。读取前清理过期项。测试通过 `FixedClock.advance(hours=25)` 推进时间，不使用真实 sleep。

## Lab 10：事件闭环

HTTP/Service 只做校验和事件写入。`InMemoryUserStore` 在锁内更新状态，请求读取的是深拷贝快照，避免并发共享可变 UserProfile。

## Lab 11：模型和规则重排

阶段顺序：

```text
RANK
-> MODEL_RERANK: 学习型列表分
-> RULE_RERANK: Flow Pool、卖家和类目约束
```

`rankers.py` 提供 `FailingModelReranker`。把它注入 `create_demo_service(model_reranker=...)` 后，MODEL_RERANK 捕获 `TimeoutError`，把 `model_rerank` 分数设为 `rank`，并在 Trace 写入 `degraded=true`；RULE_RERANK 仍继续执行。

## Lab 12：HTTP Adapter

测试不要占用固定端口：

```python
server = build_server("127.0.0.1", 0, service)
port = server.server_address[1]
```

生产容器监听 `0.0.0.0`，本地学习可以监听 `127.0.0.1`。参数错误应返回结构化 400。

## Lab 13：Agent 边界

`AgentToolRegistry.call` 在 handler 前验证字段集合、必填项、JSON 类型、长度/数值边界和确认状态。工具可见性按当前状态缩小；卖家估价不在购买 Agent registry 中。

## Lab 14：购买闭环

`BuyerSearchRecAgent` 以 `pending_shortlist` 表示待确认操作。保存请求只进入 `CONFIRM`，肯定答复才以幂等键调用 `save_shortlist`。候选数和硬参数由需求状态生成。

## Lab 15：Grounding 与安全

商品事实引用来自 `product_citations`；规则引用来自 `GroundingStore`。在商品进入上下文前过滤注入标题，手机号/地址在写入会话前脱敏，session ID 发往 ModelPort 前哈希。

## Lab 16：Agent 评测

`agent_eval.py` 逐轮检查工具和参数，不只比较最终文本。正常、失败和安全用例分别验证完成、恢复和拒绝；任一零容忍指标非零即失败。

## Lab 17：ModelPort

`ModelPortChatCompletionsAdapter` 固定 `parallel_tool_calls=false`，透传 request/routing evidence。模型选择工具意图，工作流使用确定性参数执行；ModelPort 失败不在 Agent 层重试。

## Lab 18：诊断 Agent

`SearchRecDiagnosticAgent` 只消费 search 结果/Trace，finding 必须引用具体阶段、数量或理由码。它不持有事件、配置、发布、订单和清单写接口。
