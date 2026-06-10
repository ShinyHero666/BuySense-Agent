# 08 Debug、可观测性与排障

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 三种观测对象

搜推排障需要同时观察：

| 对象 | 回答的问题 | 示例 |
|---|---|---|
| 系统 | 服务是否健康？ | QPS、P99、错误率、CPU |
| 数据 | 输入和特征是否正常？ | 事件量、特征缺失、索引延迟 |
| 决策 | 为什么返回这些商品？ | 召回来源、过滤理由、分数、重排动作 |

只有系统指标时，你知道“慢了”，但不知道哪个策略导致候选暴涨；只有业务指标时，你知道“CTR 降了”，但不知道是 Query 改写、召回还是模型问题。

## 2. DebugTrace

教学实现 `code/src/shoprec/observability.py` 为每阶段记录：

```python
StageRecord(
    name,
    input_count,
    output_count,
    elapsed_ms,
    metadata
)
```

搜索 Trace：

```text
QUERY -> RECALL -> FILTER -> FEATURE -> ROUGHRANK -> RANK -> RERANK -> RESULT
```

推荐 Trace：

```text
QUERY -> RECALL -> FILTER -> FEATURE -> ROUGHRANK -> FLOWPOOL
-> RANK -> MODEL_RERANK -> RULE_RERANK -> RESULT
```

## 3. 数量漏斗

一次推荐示例：

```text
RECALL      16 -> 16
FILTER      16 -> 13
FEATURE     13 -> 13
ROUGHRANK   13 -> 13
FLOWPOOL    13 -> 13
RANK        13 -> 13
RERANK      13 -> 5
```

看到最终只返回 5 条时，先判断：

- 是请求本来只要 5 条？
- 还是某阶段候选异常减少？

数量守恒检查：

```text
FILTER.input
= FILTER.output
+ sum(primary_filter_reasons)
```

若不相等，说明有候选无理由消失或重复计数。

## 4. 过滤理由

理由码应满足：

- 稳定枚举，不把动态文本作为 code。
- 支持分阶段和主/次理由。
- 能聚合监控。
- 不暴露敏感风控细节给普通用户。

示例：

```text
PRODUCT_OUT_OF_STOCK
PRODUCT_RISK_BLOCKED
USER_ALREADY_PURCHASED
USER_NEGATIVE_FEEDBACK
EXPOSURE_FREQUENCY_LIMIT
CITY_MISMATCH
PRICE_RANGE_MISMATCH
```

教学版使用较短字符串，生产可以加 namespace 和版本。

## 5. 候选级解释

每个最终 Candidate 输出：

```json
{
  "product_id": "p001",
  "recall_sources": ["brand_intent", "category_intent", "lexical"],
  "pool": "interest",
  "features": {
    "lexical": 1.0,
    "quality": 0.95,
    "pctr": 0.558
  },
  "scores": {
    "rough": 0.992,
    "rank": 0.860
  }
}
```

生产 Debug 还应记录：

- 每路原始分和排名。
- 模型/特征/策略版本。
- 特征缺失与默认值来源。
- 重排前后位置。
- 哪条规则移动/删除/插入商品。

## 6. 请求级 Debug 的安全

Debug 不能默认面向所有线上用户：

- 需要内部鉴权。
- 对用户 ID、Query、位置脱敏。
- 采样或按 request_id 定向开启。
- 控制保留期。
- 记录访问审计。
- 对风控规则只显示抽象理由。

开启 Debug 不应显著改变请求时序，否则排查结果与真实请求不同。

## 7. OpenTelemetry 映射

阶段可映射为 spans：

```text
search.request
  query.analyze
  recall.parallel
    recall.lexical
    recall.vector
    recall.category
  filter.apply
  feature.batch_get
  model.roughrank
  model.rank
  rerank.rules
```

Span attributes：

```text
request_id
scene
experiment_versions
input_count/output_count
model_version
degraded
timeout
```

高基数字段如完整 Query、product_ids 不应直接作为 metrics label。

## 8. 指标

系统指标：

- QPS。
- P50/P95/P99。
- 错误和超时率。
- 线程池/连接池/队列。
- CPU、内存、GC。

链路指标：

- 每阶段候选数分位数。
- 各召回源成功率、超时率、候选数。
- 各过滤理由比例。
- 特征缺失率和读取延迟。
- 模型分数分布。
- 各 Flow Pool 实际占比。
- 降级率。

业务指标：

- 无结果率。
- CTR/CVR。
- 多样性和覆盖。
- 投诉、负反馈、退款。

## 9. 排障案例一：搜索突然无结果

顺序：

```text
1. 请求参数是否为空/错误
2. QUERY 是否 blocked 或错误改写
3. 每路 RECALL 是否有结果
4. 合并后数量是否正常
5. FILTER 理由是否异常集中
6. 索引状态是否滞后
7. 配置/实验是否改变路由
```

若：

```text
RECALL.output = 500
FILTER.output = 0
city_mismatch = 500
```

问题在城市筛选或城市字段，不在召回。

## 10. 排障案例二：推荐结果重复

检查：

- 曝光写入是否成功。
- user/device/session 的去重 key 是否一致。
- TTL 是否过短。
- SPU 去重和 product_id 去重是否混淆。
- 多路合并是否保留同一商品多个对象。
- 重排是否只限制同卖家但未限制同款。

## 11. 排障案例三：P99 飙升

先看：

- 哪个 span 变慢。
- 候选数是否变大。
- 某召回源是否超时重试。
- 特征是否从批量退化为 N+1。
- 模型 batch 是否过小。
- 配置是否提高候选上限。
- 下游连接池是否耗尽。

延迟问题常由“数据量变化”触发，不一定是代码变慢。

## 12. 排障案例四：CTR 下降但系统正常

按版本和人群拆分：

- 实验分桶是否漂移。
- Query 改写率是否变化。
- 召回来源占比是否变化。
- 特征分布和模型分数是否漂移。
- 重排后位置变化。
- 客户端曝光口径是否变化。
- 商品供给结构是否变化。

使用固定请求集回放旧版和新版，比较每阶段 diff：

```text
QueryContext diff
candidate set diff
feature diff
score diff
position diff
```

## 13. 告警原则

告警应指向行动：

- P99 超阈值且持续 5 分钟。
- 某关键召回成功率低于 99%。
- 库存过滤率相对基线上升 3 倍。
- 特征缺失率超过 1%。
- 降级率超过 0.5%。
- 索引延迟 P99 超过 SLA。

避免为每次单请求错误发告警；使用聚合阈值，并附 Dashboard、Runbook 和最近变更。

## 14. 练习

1. 给重排记录 `before_position`、`after_position` 和理由。
2. 写数量守恒断言，过滤计数不一致时测试失败。
3. 模拟 Feature Store 超时并增加 `degraded=true`。
4. 为“推荐无结果”和“搜索延迟高”各写一页 Runbook。
