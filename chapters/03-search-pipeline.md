# 03 搜索召回、排序与重排

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

第一次阅读本章前，先运行：

```bash
cd moyuan-sar-agent/code
bash scripts/bootstrap.sh
bash scripts/quick-search.sh --section rewrite
bash scripts/quick-search.sh --section broad
```

第一条请求展示 Query 改写和多路召回，第二条展示泛 Query 的列表多样性与位置解释。

## 1. 代码入口

搜索主逻辑位于 `code/src/shoprec/search.py`：

```python
def search(request, user):
    query = QUERY
    candidates = RECALL
    filtered = FILTER
    candidates = FEATURE
    rough = ROUGHRANK
    ranked = RANK
    reranked = RERANK
    response = RESULT
    return response
```

这八个阶段构成课程的可执行搜索契约：特征准备被显式拆成 `FEATURE`，`RESULT` 记录分页和组装数量。

## 2. 多路召回

教学版包含五类召回来源：

```python
lexical = title/tags 与 query terms 有交集
category = 商品类目命中 category_intents
brand = 商品品牌命中 brand_intents
broad_explore = 泛 Query 补充少量高质量相关类目探索商品
hot_fallback = 词法、类目和品牌均无结果时按历史 CTR × 质量兜底
```

中文词法使用连续词和双字词，不把“手机”拆成单字“机”；跨类目结果只能由显式的 `broad_explore` 引入。这样“为什么召回”可以通过 `recall_sources` 准确解释。

### 2.1 为什么召回要多路

单路召回有结构性盲区：

- 词法找不到同义表达。
- 类目召回太宽。
- 品牌召回无法区分型号。
- 向量召回可能语义相似但不可购买。
- 热门召回忽略 Query。

多路召回让不同机制互补，再由排序决定优先级。

### 2.2 合并逻辑

```python
candidate = merged.setdefault(product_id, Candidate(product))
candidate.recall_sources.add(source)
```

这里有两个不变量：

1. 同一 `product_id` 最终只有一个 Candidate。
2. 所有命中的来源都被保留。

生产中还要处理：

- 同一 SPU 的多个 SKU。
- 不同源返回的商品版本冲突。
- 每路召回的原始分和位置。
- 每路超时、空结果和错误。
- 召回配额和总候选上限。

### 2.3 并行召回

教学版顺序扫描内存。生产编排应并行调用彼此独立的召回器：

```text
                  ┌-> lexical/BM25
QueryContext -----├-> category/attribute
                  ├-> vector ANN
                  ├-> user-interest
                  └-> activity
                       |
                    merge/dedup
```

等待策略通常是“总 deadline 内收集已完成结果”，而不是被最慢一路拖住。关键召回可设置更高优先级，非关键召回超时直接丢弃。

## 3. 过滤

教学过滤顺序：

```text
库存
  -> 质检状态
  -> 类目
  -> 品牌
  -> 城市
  -> 最低价
  -> 最高价
  -> 成色
  -> 供给渠道
  -> 质检等级
  -> 电池健康
  -> 质保可用性
```

库存和不合格质检是不可绕过的硬约束；用户筛选随后执行。把便宜且淘汰率高的条件放前面可以节省后续成本。生产中应进一步区分：

- 索引侧过滤：尽量减少网络返回。
- 召回后过滤：跨源统一规则。
- 结果前校验：防止商品状态瞬时变化。

每个被删除的 Candidate 只能记录一个主理由还是多个理由，需要提前定口径。教学版记录第一个命中理由，便于数量守恒：

```text
召回数 = 通过数 + 各主过滤理由数
```

如果记录多个理由，监控计数会大于候选数，但能提供更多诊断信息。两种口径不能混用。

## 4. Facet 聚合

Facet 是筛选项计数：

```python
{
    "category": Counter(...),
    "brand": Counter(...),
    "city": Counter(...),
    "condition": Counter(...)
}
```

教学版在过滤后候选上计算。生产搜索引擎通常使用 aggregation，需明确：

- 聚合是在全部命中集还是截断候选上计算。
- 当前筛选字段是否自排除，例如选了 Apple 后品牌 Facet 是否仍展示其他品牌。
- 聚合与结果查询是否一致。
- 聚合超时是否影响主结果。

Facet 常可异步或独立超时，避免拖慢商品列表。

## 5. 特征

`search_features` 为每个候选生成：

- `lexical`：Query 与标题/标签覆盖率。
- `intent`：类目或品牌意图匹配。
- `quality`：商品质量。
- `trust`：质检等级、质保、退货和瑕疵披露形成的信任特征。
- `freshness`：指数时间衰减。
- `user_interest`：用户类目兴趣。
- `source_strength`：命中召回源数量。
- `pctr`、`pcvr`：教学版启发式预测。

注意：命中三路不一定永远比命中一路好。`source_strength` 是弱信号，不能压过强相关性。

## 6. 粗排

粗排代码：

```python
rough_score = (
    0.45 * lexical
    + 0.25 * intent
    + 0.15 * source_strength
    + 0.10 * quality
    + 0.05 * trust
)
rough = top_100(candidates)
```

为什么不直接精排所有候选：

- 在线特征批量读取成本高。
- DNN/交叉编码器推理昂贵。
- 大候选会扩大尾延迟。

粗排的评估重点是 Recall@K：最终好商品在粗排 Top K 中保留了多少，而不是只看粗排自己的 NDCG。

## 7. 精排

精排权重来自 `search_rank` 实验版本：

```python
rank_score = sum(feature[name] * weight[name])
```

控制组与新鲜度增强组的主要差异是 `freshness` 权重。稳定分桶保证同一 token 不会在两个版本间跳动。

生产模型可使用：

- LambdaMART：结构化特征强、解释性较好。
- Wide & Deep/DeepFM：高阶交叉。
- 多任务网络：CTR/CVR/价值联合。
- Cross Encoder：高精度语义相关性，成本高。

模型选型要与候选规模、特征可用性、延迟和团队能力一起考虑。

## 8. 排序模式

教学版支持：

```text
relevance
price_asc
price_desc
newest
```

非相关性排序仍使用 Query 召回和硬过滤。若价格排序把极弱相关商品排前面，应先设置相关性门槛，再在通过门槛的商品中按价格排序。

生产需要稳定次级排序键，例如：

```text
ORDER BY price ASC, relevance DESC, product_id ASC
```

否则翻页时相同价格商品可能重复或丢失。

## 9. 泛 Query 重排

教学版只在 `sort=relevance` 且 `is_broad=true` 时启用多样性重排：

```python
adjusted_score =
    rank_score
    - category_repeat_penalty
    - seller_repeat_penalty
```

每轮贪心选最高调整分。它保留大体相关性，同时减少连续重复。

泛 Query 重排需要特别强调：

- 前 20 位保持强相关。
- 滑动窗口内按类目、品牌、成色、业务线打散。
- 新跨类目链路只在特定词型和条件下启用。

生产实现应把“硬约束”和“软惩罚”分开：

- 硬约束：同一卖家最多 N 个、安全规则。
- 软约束：重复类目扣分、探索加分。

## 10. 完整示例

请求：

```json
{
  "query": "苹果手机",
  "user_id": "u001",
  "filters": {"max_price": 7000},
  "sort": "relevance",
  "page_size": 3
}
```

执行：

```text
QUERY
  苹果手机 -> iphone
  类目=手机，品牌=Apple，精确 Query

RECALL
  p001/p002/p016 同时命中 lexical/category/brand
  其他手机命中 category
  Apple 电脑/耳机命中 brand

FILTER
  p016 stock=0 -> out_of_stock

FEATURE
  p001 lexical=1, intent=1, quality=0.95

ROUGHRANK
  强匹配候选优先

RANK
  使用 token 对应实验权重

RERANK
  非泛 Query，不启用类目打散
```

这也解释了为什么品牌召回可能带来 Apple 电脑和耳机：召回允许扩展，精排需要用类目意图把它们压低。若业务要求绝不跨类目，应把类目变成硬过滤。

## 11. 生产中的分页

深分页 `from + size` 成本高且结果不稳定。建议使用：

- `search_after` + 稳定排序键。
- 游标包含模型/配置版本和最后排序值。
- 游标设置有效期。
- 大页码场景降级到简化排序或缓存。

不能让第一页和第二页使用不同实验或模型版本，否则会重复/漏商品。

## 12. 练习

1. 新增 `semantic` 召回器，用标签同义图模拟。
2. 为每路召回增加 `raw_score` 和 `raw_rank`。
3. 实现相关性门槛后的价格升序。
4. 把 Facet 改为自排除聚合。
5. 模拟一条召回超时，验证其他召回仍能返回。
