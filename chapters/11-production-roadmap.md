# 11 生产落地路线

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 目标系统

假设业务是中等规模二手电商：

- 搜索峰值 2,000 QPS。
- 推荐峰值 5,000 QPS。
- 商品 1,000 万。
- 日行为事件 5 亿。
- 搜索/推荐 P99 目标 200 ms。

这些是规划示例。实际方案必须替换成真实流量、商品规模、地域和预算。

## 2. Phase 0：需求和基线，2 周

产物：

- 搜索/推荐场景列表和优先级。
- 事件字典和指标口径。
- 延迟、可用性、数据新鲜度 SLO。
- 现有系统基线。
- 固定 Query/用户回放集。
- 隐私和合规评审。

完成标准：

- 产品、算法、后端、数据对指标定义一致。
- 每个事件有 owner 和验收方式。
- 可以回答“上线后成功或失败如何判断”。

## 3. Phase 1：规则 MVP，4 到 6 周

搜索：

- 词法索引。
- Query 归一化和基础同义词。
- 类目、品牌、价格、城市筛选。
- 规则/线性排序。
- Facet 和无结果兜底。

推荐：

- 热门、新鲜、类目兴趣、ItemCF 基础召回。
- 库存、已购、曝光过滤。
- 规则粗排和多样性重排。

平台：

- 统一 request_id。
- 基础 Trace 和过滤理由。
- 事件采集。
- 配置版本与回滚。
- Docker/K8s 和基础监控。

完成标准：

- 主链路可用且可解释。
- 所有硬过滤可测试。
- 线上问题可定位到阶段。
- 事件关联率达到预设门槛。

## 4. Phase 2：数据闭环，4 到 8 周

建设：

- Kafka/Pulsar 事件总线。
- Flink 实时聚合。
- 湖仓明细和宽表。
- 在线 Feature Store。
- 商品索引全量+增量。
- 数据质量和对账。
- 训练样本管线。

完成标准：

- 曝光、点击、支付可通过 request_id 关联。
- 在线/离线特征一致性有自动检查。
- 商品状态进入索引的 P99 延迟满足 SLA。
- 数据异常能自动阻止训练或发布。

## 5. Phase 3：模型化，6 到 10 周

优先顺序：

1. 搜索相关性和推荐 CTR 基线模型。
2. 轻量粗排。
3. 精排 pCTR/pCVR 多任务。
4. 向量召回。
5. 列表级重排。

配套：

- 模型注册和版本。
- 离线评估与固定回放。
- 影子流量。
- AB 平台。
- 特征 schema。
- 推理降级。

完成标准：

- 模型效果超过规则基线。
- 模型失败不影响可用性。
- 模型、特征、实验和曝光可完整归因。

## 6. Phase 4：平台化，持续

重点不是重写业务，而是沉淀重复能力：

- Pipeline DSL/SDK。
- 召回器、Filter、Ranker 插件接口。
- 场景配置模板。
- 请求级 Debug。
- 模型/特征自助发布。
- 自动灰度和指标门禁。
- 成本和容量看板。

平台化完成标准：

- 新场景大部分工作是配置和少量插件。
- 公共能力有明确 SLA 和 owner。
- 业务团队能自助回放、灰度、回滚。
- 平台升级不强迫所有场景同时改造。

## 7. 服务边界建议

初始：

```text
search-api
recommend-api
event-collector
index-pipeline
training-pipeline
```

规模增长后：

```text
query-service
search-recall-service
recommend-recall-service
feature-service
rank-service
rerank/strategy-service
experiment-config-service
debug-service
```

搜索和推荐召回可共享基础检索 SDK/索引，但业务召回策略最好保留清晰边界。

## 8. 团队职责

| 角色/团队 | 主要责任 |
|---|---|
| 搜索 | Query、相关性、搜索召回、Facet |
| 推荐 | 用户兴趣、推荐召回、Flow Pool、重排 |
| 算法 | 特征、模型、评估、实验 |
| 数据 | 事件、Flink、湖仓、样本、质量 |
| 平台 | Pipeline、Feature/Model SDK、AB、Debug |
| SRE | SLO、容量、发布、故障与成本 |
| 商品/风控 | 商品状态、质量、安全契约 |

每个跨团队契约必须有 owner。没有 owner 的“共享服务”通常会成为问题黑洞。

## 9. SLO

示例：

```text
Search API:
  availability 99.95%
  P99 < 200 ms
  error rate < 0.1%

Recommend API:
  availability 99.95%
  P99 < 180 ms
  degraded response < 0.5%

Product Index:
  update P99 < 30 s
  stale searchable status < 0.01%

Feature Store:
  batch read P99 < 15 ms
  feature freshness within declared SLA
```

SLO 必须对应错误预算和发布策略。错误预算耗尽时降低变更频率，优先稳定性。

## 10. 容量规划

对每阶段测量：

```text
QPS
candidate count P50/P95/P99
feature count
payload bytes
CPU ms/request
memory/request
downstream calls
```

单 Pod 安全吞吐应在目标 P99 下测量，不以 CPU 100% 时的最大吞吐为准。

估算：

```text
required_pods =
  peak_qps
  / safe_qps_per_pod
  * headroom_factor
```

headroom 常从 1.3 到 2.0 起步，取决于扩容速度和流量突发。

## 11. 成本治理

主要成本：

- 搜索/向量索引内存和副本。
- 在线特征存储。
- 模型推理 GPU/CPU。
- 行为事件存储和流处理。
- Debug 日志。

优化顺序：

1. 减少无效候选和重复计算。
2. 批量读取/推理。
3. 分级模型和候选上限。
4. 缓存稳定结果。
5. 日志采样与分层保留。
6. 选择合适硬件。

先优化链路放大因子，通常比更换云实例更有效。

## 12. 主要风险

| 风险 | 预防 |
|---|---|
| 事件口径错误 | Schema、端到端对账、版本门禁 |
| 特征泄漏/不一致 | 时间语义、快照、在线离线校验 |
| 配置误发 | Schema、回放、灰度、自动回滚 |
| 模型超时 | Batch、deadline、粗排降级 |
| 候选暴涨 | 阶段上限、指标、熔断 |
| 过度微服务化 | 按容量和所有权演进 |
| 平台过早抽象 | 先完成两个真实场景再抽公共接口 |
| 指标单一 | 主指标 + 守护 + 长期生态 |

## 13. 发布检查表

- 请求/响应 schema 向后兼容。
- 固定请求回放无意外 diff。
- 所有过滤有理由码。
- 候选上限已配置。
- 下游 deadline 小于总 deadline。
- 降级路径已测试。
- 实验分桶稳定。
- 指标、Dashboard、告警和 Runbook 已就绪。
- 配置/模型可一键回滚。
- 事件携带实验和版本。
- 安全与隐私评审通过。
- 容量和故障演练通过。

## 14. 从本课程开始

最实际的推进顺序：

1. 跑通教学 Demo，理解每个阶段。
2. 抽出 Repository/FeatureStore/Ranker/EventPublisher 接口。
3. 先替换商品索引和事件流。
4. 再替换在线特征和模型服务。
5. 保留相同 Trace 和 Candidate 解释契约。
6. 用固定请求回放保证替换基础设施不改变业务语义。
