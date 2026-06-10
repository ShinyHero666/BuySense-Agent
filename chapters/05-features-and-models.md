# 05 特征与模型

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 模型在链路中的位置

不要从“用什么神经网络”开始。先回答：

- 模型处理多少候选？
- 延迟预算是多少？
- 有哪些在线可用特征？
- 模型失败时用什么结果？
- 业务优化目标是什么？

典型多级排序：

| 层级 | 输入候选 | 特征成本 | 模型 | 目标 |
|---|---:|---|---|---|
| 召回打分 | 万到千 | 极低 | BM25/ANN/CF 原始分 | 高覆盖 |
| 粗排 | 千到数百 | 低 | 线性/LR/轻量 DNN | 保住优质候选 |
| 精排 | 数百到几十 | 中高 | GBDT/DNN/多任务 | 准确预测效用 |
| 重排 | 几十 | 列表级 | 规则/序列模型/优化器 | 全局列表收益与约束 |

## 2. 特征契约

每个特征应有机器可读定义：

```yaml
name: user_category_interest_7d
type: float32
entity: user_category
owner: recommendation-feature-team
event_time_semantics: before_request
window: 7d
default: 0.0
range: [0.0, 1.0]
freshness_sla: 10m
offline_source: lake.user_category_interest_daily
online_source: redis://feature/user_category
version: v3
```

如果只有名称，没有窗口、默认值和时间语义，训练与在线很容易“同名不同义”。

## 3. 教学版搜索特征

`code/src/shoprec/ranking.py` 中的 `search_features`：

```text
lexical
intent
quality
trust
freshness
user_interest
source_strength
pctr
pcvr
```

### 3.1 文本相关性

教学版：

```text
lexical =
  |query_terms ∩ title_or_tag_terms| / |query_terms|
  + exact_match_bonus
```

生产可组合：

- BM25。
- Query-Title 词覆盖、顺序和距离。
- 类目/品牌/属性匹配。
- 双塔 cosine。
- Cross Encoder 相关性。

不同相关性分数要校准。BM25、cosine 和模型概率不能未经处理直接相加。

### 3.2 新鲜度

教学版：

```text
freshness = exp(-age_days / 30)
```

半衰期要按类目调节：

- 手机和潮流商品变化快。
- 图书和收藏品变化慢。
- 二手商品的“新鲜”还意味着更可能仍在售。

可以设置：

```text
freshness(category) = exp(-age / tau_category)
```

### 3.3 质量

质量不是单一字段，可能由：

- 图片清晰度和完整度。
- 标题/描述信息量。
- 价格异常。
- 卖家信誉。
- 商品真实性。
- 投诉/退款/下架率。

质量通常既用于过滤，也用于排序。严重风险做硬过滤，轻微低质做降权。

### 3.4 二手交易信任

二手商品的一物一况要求把质检和服务承诺显式建模。教学版 `trust_score` 组合：

```text
质检状态 + 质检等级 + 质保覆盖 + 退货覆盖 - 瑕疵披露惩罚
```

`trust` 与 `quality` 不同：

- `quality` 表达内容、商品和历史表现的综合质量。
- `trust` 表达质检与履约承诺是否充分、透明和可执行。

不合格质检属于硬过滤，不能依赖较低的 `trust` 分等待模型降权。

## 4. 教学版推荐特征

`recommendation_features`：

- 用户类目兴趣。
- 与最近点击商品的相似度。
- 商品质量、质检信任和新鲜度。
- 新颖性。
- pCTR/pCVR。

特征可分三组：

```text
User:
  category_interest, price_preference, activity_level

Item:
  category, brand, price, quality, trust, freshness, popularity

Cross:
  user_item_similarity, price_fit, category_interest,
  distance, seller_affinity
```

交叉特征通常最有预测力，也最容易引入在线成本。

## 5. pCTR 和 pCVR

教学版通过 sigmoid 启发式模拟概率：

```python
pctr = sigmoid(
    bias
    + w1 * relevance
    + w2 * interest
    + w3 * quality
)
```

它只是帮助理解分数流动。真实训练需要定义样本：

```text
CTR:
  样本 = 有效曝光
  label = 是否点击

CVR:
  样本 = 点击或曝光（取决于模型定义）
  label = 是否在归因窗口内转化
```

如果 CVR 只在点击样本上训练，却在线对所有曝光候选直接解释为 `P(purchase | impression)`，概率语义就错了。常见关系：

```text
P(purchase | impression)
= P(click | impression)
* P(purchase | click, impression)
```

## 6. 多目标排序

电商目标不只有 CTR。只优化 CTR 容易得到：

- 标题党。
- 低价但低质量商品。
- 用户爱点但不买的内容。
- 热门商品过度集中。

一个可解释的起点：

```text
expected_value =
  pCTR
  * pCVR_after_click
  * expected_order_value
  * quality_factor
```

再加入：

```text
final_utility =
  expected_value
  + long_term_value
  + ecosystem_bonus
  - risk_penalty
  - repetition_penalty
```

乘法对小概率很敏感，加法容易量纲混乱。应先校准各模型，再用回放和 AB 验证。

## 7. 样本构建

一条训练样本至少需要：

```json
{
  "request_id": "r123",
  "user_id": "u001",
  "product_id": "p001",
  "position": 1,
  "event_time": "2026-07-29T10:00:00Z",
  "features_at_impression": {},
  "clicked": 1,
  "purchased_in_7d": 0,
  "experiment": {},
  "model_version": "rank_v12"
}
```

### 7.1 位置偏差

排得靠前更容易被点，点击不完全代表更相关。处理方式：

- 随机小流量收集无偏数据。
- IPS/倾向得分校正。
- 将位置作为特征，但注意推理时的可用性。
- 使用点击模型。

### 7.2 负采样

曝光未点击是常见负样本，但数量巨大。负采样时要：

- 保留样本权重。
- 按场景和位置分层。
- 避免只采“容易负样本”。
- 与线上候选分布一致。

### 7.3 时间切分

训练、验证、测试应按时间切分，而不是随机切分，否则会泄漏未来商品和用户状态。

## 8. 离线指标

搜索：

- Recall@K：召回是否覆盖相关商品。
- NDCG@K：考虑位置的相关性质量。
- MRR：第一个相关结果位置。
- 无结果率。
- Query 分桶指标：头部、长尾、泛词、品牌词。

推荐：

- AUC/GAUC：概率排序能力。
- LogLoss：概率质量。
- Recall@K/NDCG@K。
- Coverage：商品/卖家/类目覆盖。
- Diversity/Novelty。
- Calibration：预测分布与真实分布一致。

离线指标不能替代线上 AB。数据来自旧策略，无法完全预测新策略改变后的用户行为和生态反馈。

## 9. 在线指标

主指标按业务阶段选择：

- CTR、收藏率、咨询率。
- 下单率、支付率、GMV。
- 人均有效浏览、次日/七日留存。
- 搜索成功率、Query 改写率。

守护指标：

- 投诉、退款、负反馈。
- 无结果率、低质率。
- 延迟、错误率、超时率。
- 商品/卖家覆盖和集中度。
- 新商品曝光。

实验结果必须同时满足统计显著性、业务重要性和守护指标。

## 10. 模型发布

推荐流程：

```text
训练
  -> 数据/特征校验
  -> 离线评估
  -> 固定请求回放
  -> 影子流量
  -> 1% 灰度
  -> 分层 AB
  -> 扩量
  -> 全量或回滚
```

模型制品应绑定：

- 模型二进制/图。
- 特征 schema 和字典版本。
- 训练数据时间范围。
- 代码提交。
- 离线指标。
- 发布审批和回滚版本。

## 11. 推理稳定性

模型服务要支持：

- Batch inference。
- Deadline 透传。
- 特征缺失监控。
- 输入值域校验。
- 模型预热。
- 多版本并存。
- 超时退化。

教学链路中的降级顺序：

```text
精排失败 -> 粗排顺序
模型重排失败 -> 精排顺序
在线特征失败 -> 默认/缓存特征
```

## 12. 练习

1. 把线性精排替换成一个手写 Logistic Regression 模型文件。
2. 为价格加入 `log1p(price)` 和用户价格带匹配。
3. 统计每个特征的缺失率并写入 Trace。
4. 设计搜索相关性人工标注格式，计算 NDCG@10。
5. 分析只提高 pCTR 权重可能带来的副作用。
