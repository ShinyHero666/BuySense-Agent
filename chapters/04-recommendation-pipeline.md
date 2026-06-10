# 04 推荐召回、过滤与流量池

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 主入口

推荐实现位于 `code/src/shoprec/recommend.py`，代码 Trace 采用以下显式十阶段契约：

```text
QUERY
  -> RECALL
  -> FILTER
  -> FEATURE
  -> ROUGHRANK
  -> FLOWPOOL
  -> RANK
  -> MODEL_RERANK
  -> RULE_RERANK
  -> RESULT
```

QUERY 阶段记录场景、用户兴趣类目、最近点击和实验版本。它把“请求解析”和“推荐触发器”明确留在 Trace 中，使一次推荐请求可以从入口完整回放。

## 2. 触发器

教学版从 `UserProfile` 取：

- Top 3 兴趣类目。
- 最近 10 个点击商品。

它们产生两类种子：

```text
category trigger: 手机、数码配件、电脑
item trigger: p002、p003、p007
```

生产触发器还需考虑：

- 时间衰减：1 分钟前点击比 30 天前更强。
- 行为类型：购买通常不等于继续推荐同款。
- 负反馈：快速跳过、屏蔽、退款。
- 会话主题：本次会话突然从手机转向摄影。
- 场景：详情页更依赖当前商品，首页更依赖用户。

## 3. 四路召回

### 3.1 Interest Recall

```python
top_categories = top3(user.category_interests)
interest = products where category in top_categories
```

它稳定但容易形成兴趣闭环。应设置：

- 类目兴趣的时间衰减。
- 兴趣置信度和最小行为数。
- 兴趣召回上限。
- 探索流量。

### 3.2 ItemCF Recall

教学版用同类目或同品牌模拟商品相似：

```python
if product.category in clicked_categories
or product.brand in clicked_brands
```

真实 ItemCF 通常离线统计：

```text
sim(i, j) =
  co_occurrence(i, j)
  / sqrt(popularity(i) * popularity(j))
```

还可使用：

- Swing：降低热门用户/商品的干扰。
- 图随机游走。
- 商品双塔或多模态 embedding。
- Session-based Transformer。

召回结果应保留 trigger item，才能解释“因为你看过 p002”。

### 3.3 Hot Recall

教学热门分：

```text
historical_ctr * historical_cvr * quality
```

生产热门榜要按场景、类目、城市、价格带和时间窗口切分，并使用贝叶斯平滑或 Wilson score，避免小样本 CTR 虚高。

### 3.4 Fresh Recall

新发布商品按发布时间召回。仅按时间会放大低质量内容，需同时满足：

- 商品信息完整。
- 图片和文本质量。
- 卖家可信度。
- 价格合理性。
- 风险审核通过。

## 4. 合并与去重

推荐合并与搜索相同：按 `product_id` 去重，保留所有来源。

生产中需要进一步记录：

```json
{
  "source": "item_cf",
  "trigger_id": "p002",
  "raw_score": 0.83,
  "source_rank": 7
}
```

同一候选可以有多个 source detail。压平为单字符串会丢失触发器和分数。

## 5. 过滤

教学版过滤：

```text
out_of_stock
inspection_not_passed
purchased
negative_feedback
already_exposed
```

推荐过滤顺序体现成本和业务优先级：

1. 商品库存和质检硬状态。
2. 强用户反馈。
3. 历史曝光/频控。
4. 复杂相似去重和业务规则。

曝光去重必须定义：

- 场景：主页曝光不一定阻止搜索页出现。
- 时间窗口：例如 24 小时、7 天。
- 曝光有效条件：滑到可视区域还是只下发。
- 多端合并：用户 ID 与设备 ID。

教学版使用线程安全的 `InMemoryExposureStore`，按用户、场景和商品保存 24 小时 TTL。第二次请求会换一批商品，测试可通过 `FixedClock` 推进时间验证过期，不需要真实等待。

## 6. 推荐特征

`recommendation_features` 包含：

- `interest`：用户对商品类目的兴趣。
- `item_similarity`：与最近点击商品的最大相似度。
- `quality`。
- `trust`：质检和服务承诺的可信度。
- `freshness`。
- `novelty`：是否为用户已知兴趣之外。
- `pctr`、`pcvr`。

相似度教学公式：

```text
0.55 * same_category
+ 0.25 * same_brand
+ 0.20 * tag_jaccard
```

生产中 item embedding 相似度要带版本，召回 embedding 和排序 embedding 不一定相同。

## 7. 粗排

教学版：

```text
rough =
  0.28 * interest
+ 0.24 * item_similarity
+ 0.14 * quality
+ 0.08 * trust
+ 0.12 * freshness
+ 0.10 * pctr
+ 0.04 * pcvr
```

粗排前应批量获取轻量特征，避免逐候选 RPC。常见做法：

- Feature Store `batch_get(user, item_ids, feature_names)`。
- 静态商品特征随召回结果携带。
- 请求级用户特征只读取一次。
- 默认值和缺失率记录在 Debug。

## 8. Flow Pool

教学版分池规则：

```python
if interest >= 0.55 or item_similarity >= 0.55:
    pool = "interest"
elif freshness >= 0.72:
    pool = "fresh"
else:
    pool = "explore"
```

然后按实验配额生成最终整数目标：

```text
control:
  interest 60%, explore 25%, fresh 15%

explore_more:
  interest 35%, explore 40%, fresh 25%
```

### 8.1 为什么在精排前分池

分池后只让一定数量进入高成本精排，可以：

- 保证不同业务意图都有精排机会。
- 控制各池计算成本。
- 防止单一热门源淹没列表。

但过早硬配额也会损失全局最优。常见折中是：

- 各池先保底。
- 剩余名额全局竞争。
- 重排时再满足最终配额。

教学 `_allocate` 正是“各池配额 + 全局补量”。

### 8.2 配额取整

教学实现使用最大余数法把比例转换为整数，保证目标之和严格等于 `size`。Flow Pool 阶段完成分类和目标计算，RULE_RERANK 执行最终配额；某池不足时再按模型顺序补量。

生产需明确：

- 四舍五入方式。
- 某池不足如何补量。
- 一个商品属于多个池时归属优先级。
- 小页面 size 时如何避免比例失真。

## 9. 精排

教学精排更重 pCTR/pCVR：

```text
rank =
  0.38 * pctr
+ 0.22 * pcvr
+ 0.12 * interest
+ 0.08 * item_similarity
+ 0.08 * quality
+ 0.04 * freshness
+ 0.08 * trust
```

为什么粗排和精排权重不同：

- 粗排需要简单稳定，优先减少候选。
- 精排使用更完整的行为预测和交叉特征。
- 粗排保覆盖，精排提准确。

## 10. 重排

教学版先执行 MODEL_RERANK，用新颖性和新鲜度对精排分做列表级调整；再执行 RULE_RERANK，落实：

- 同卖家最多 1 个。
- 最近 3 个结果中同类目扣分。

卖家上限是硬约束：如果候选中的独立卖家不足，教学实现宁可返回少于请求 `size` 的结果，也不会用重复卖家补满页面。Trace 中 `RULE_RERANK.input/output` 会明确显示因此减少的数量。

生产常见约束：

- SPU/相似图片去重。
- 卖家频控。
- 类目和业务线打散。
- 广告、活动和自然结果位置。
- 新鲜/探索保底。
- 负反馈和安全最终校验。

模型重排失败应保留精排顺序；规则重排失败时，安全硬约束不能失效，可以降级为本地最小规则集。

## 11. 曝光写入

教学代码在 RESULT 阶段：

```python
exposure_store.add_many(user_id, scene, returned_product_ids)
```

生产不建议把曝光存储写入放在主响应强依赖上。可采用：

- 返回前同步写短期去重缓存，但严格超时。
- 同时异步发曝光事件。
- 写失败不阻塞响应，但增加指标和补偿。
- 客户端真实曝光再发一条可视曝光事件。

需要区分“服务下发”和“用户真实看到”，训练 CTR 通常以可视曝光为更准确分母。

## 12. 冷启动

新用户没有画像时：

```text
上下文热门
  + 城市/时间
  + 新鲜高质量
  + 少量探索
```

新商品没有行为时：

```text
内容/多模态特征
  + 卖家质量
  + 类目先验
  + 新品流量池
  + 快速收集反馈
```

不能用“没有历史分数”为理由永远不给新商品流量，否则系统无法学习。

## 13. 练习

1. 给 ItemCF 结果保留 `trigger_id` 和 `raw_score`。
2. 为不同场景配置不同曝光 TTL。
3. 增加“同城”流量池，并设计配额不足时补量。
4. 模拟精排失败，返回粗排顺序并在 Trace 标记 degraded。
5. 增加详情页场景：当前商品作为最高优先级 trigger。
