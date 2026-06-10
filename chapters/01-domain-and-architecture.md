# 01 业务、领域模型与总体架构

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 先定义业务问题

搜推系统不是“给商品算一个分”。它在有限延迟内解决一组相互冲突的问题：

- 用户要立刻看到相关、有吸引力且可以买的商品。
- 平台要提升点击、交易、留存，同时控制风险和成本。
- 卖家和生态需要新商品、长尾商品得到合理曝光。
- 运营需要活动干预，但不能摧毁长期用户信任。
- 工程团队需要快速实验、准确归因和稳定回滚。

搜索场景中，用户已经表达了意图，因此相关性和筛选约束是底线。推荐场景中没有显式 Query，需要从画像、行为和上下文推断意图，并保留探索空间。

## 2. 核心领域对象

教学代码在 `code/src/shoprec/models.py` 定义了五组对象。

### 2.1 Product

```python
@dataclass(frozen=True)
class Product:
    product_id: str
    title: str
    category: str
    brand: str
    price: float
    condition: str
    city: str
    seller_id: str
    stock: int
    publish_time: datetime
    tags: tuple[str, ...]
    quality_score: float
    historical_ctr: float
    historical_cvr: float
```

字段分为：

- 检索字段：标题、类目、品牌、标签。
- 硬过滤字段：库存、城市、价格、状态。
- 排序字段：质量、历史 CTR/CVR、新鲜度。
- 列表约束字段：卖家、类目、品牌。

生产系统不要直接把商品服务的完整数据库表当作搜推文档。搜索索引和特征存储应只保留在线决策需要的字段，并明确更新语义。

### 2.2 UserProfile

```python
class UserProfile:
    user_id
    city
    category_interests
    recent_clicks
    purchased
    disliked_products
```

教学版把长期兴趣、短期行为和负反馈放在一个对象里。生产中通常拆成：

- 静态画像：城市、设备、用户阶段。
- 长期兴趣：周/月级类目与品牌偏好。
- 短期会话：最近分钟/小时行为。
- 历史集合：曝光、点击、已购、不喜欢。
- 向量表示：用户 embedding。

### 2.3 Request

`SearchRequest` 包含 Query、筛选、排序和分页；`RecommendRequest` 包含场景、数量和是否过滤已曝光。

请求中必须有 `request_id` 和 `experiment_token`：

- `request_id` 关联日志、Trace、曝光与点击。
- `experiment_token` 保证同一主体稳定分桶。

生产中还应带 session、device、入口、locale、客户端版本、地理位置和超时 deadline。

### 2.4 QueryContext

原始 Query 不能直接传到召回和排序。`QueryContext` 保存：

- 归一化和改写后的 Query。
- 可匹配 terms。
- 类目与品牌意图。
- 泛 Query 标记。
- 风险状态和判定原因。

它是 Query 理解阶段与下游的契约。生产中每个字段都应可选、有版本，并支持部分降级。

### 2.5 Candidate

`Candidate` 是全课程最重要的对象：

```python
class Candidate:
    product
    recall_sources
    features
    scores
    pool
```

候选商品在链路中不断“增广”：

```text
召回后:
  product + recall_sources

特征后:
  product + recall_sources + features

粗排后:
  ... + rough_score

流量池后:
  ... + pool

精排后:
  ... + rank_score
```

这比每阶段创建互不兼容的 Map 更容易理解和 Debug。生产实现可以为了性能使用 protobuf、列式批处理或数组，但逻辑上仍应保持清晰的数据契约。

## 3. 编排与能力服务

总体上分两类服务。

### 3.1 Orchestrator

编排服务负责：

- 解析请求和 deadline。
- 获取实验与场景配置。
- 决定调用哪些阶段。
- 并行执行独立召回。
- 收集结果、处理超时和降级。
- 组装 Debug。

编排服务不应拥有所有算法实现，否则会变成无法维护的单体。

### 3.2 Capability Service

能力服务包括：

- Query Understanding
- Recall
- Feature Store
- Rank/Model Serving
- Strategy/Rerank
- Experiment/Config
- Debug/Observability

教学版为了可运行，把它们放在一个进程中，但通过模块边界模拟服务边界。

## 4. Facade 的作用

`code/src/shoprec/service.py` 中的 `CommerceDiscoveryService` 是应用门面：

```python
def search(self, payload):
    request = SearchRequest(...)
    return asdict(self.search_engine.search(request, user))

def recommend(self, payload):
    request = RecommendRequest(...)
    return asdict(self.recommendation_engine.recommend(request, user))
```

它隔离了协议适配与领域逻辑：

- CLI 传字典。
- HTTP 服务传 JSON。
- 测试直接调用方法。
- 核心 Search/Recommendation Engine 不知道 HTTP。

生产中对应 Hexagonal/Clean Architecture 的 Adapter 与 Application 边界。RPC 框架、鉴权和序列化不应渗入算法核心。

## 5. 一次请求的状态变化

以搜索“苹果手机”为例：

```text
原请求
  query = 苹果手机
  max_price = 7000

QueryContext
  rewritten_query = iphone
  category_intents = [手机]
  brand_intents = [Apple]
  is_broad = false

候选 p001
  recall_sources = [lexical, category_intent, brand_intent]
  features = {lexical: 1, intent: 1, quality: 0.95, ...}
  scores = {rough: 0.99, rank: 0.86}
```

这条解释链让工程师能回答“为什么这个商品排第一”，也能比较实验组之间具体哪一个特征或权重导致变化。

## 6. 阶段契约

每个阶段都要定义：

| 契约项 | 例子 |
|---|---|
| 输入 | `QueryContext + UserProfile + Candidate[]` |
| 输出 | 增广或过滤后的 `Candidate[]` |
| 顺序 | FILTER 必须在 RANK 之前 |
| 超时 | 模型推理 40 ms |
| 失败 | 保留粗排顺序 |
| 幂等 | 相同请求不产生重复副作用 |
| Debug | 输入数、输出数、理由、版本 |

一个阶段的“代码能跑”不是完成标准；只有契约和失败语义都明确，才能放入大型 Pipeline。

## 7. 搜推边界

公共能力应统一，但业务目标不能混淆：

| 维度 | 搜索 | 推荐 |
|---|---|---|
| 意图来源 | 显式 Query | 用户/场景/上下文 |
| 召回底线 | 与 Query 相关 | 与兴趣或探索目标相关 |
| 硬约束 | 筛选条件优先 | 历史与频控更多 |
| 多样性 | 泛 Query 特别重要 | 几乎所有场景重要 |
| 兜底 | Query 热门/类目热门 | 场景热门/新鲜 |
| 指标 | 无结果率、相关性、CTR、CVR | CTR、CVR、留存、多样性、覆盖 |

推荐候选不能直接混入搜索而绕开相关性，搜索点击也不能不经时间和场景处理直接永久定义推荐兴趣。

## 8. 本章练习

1. 给 `Product` 增加 `risk_level` 和 `shipping_available`，说明它们属于过滤还是排序。
2. 给 `SearchRequest` 增加 `latitude/longitude`，设计距离排序所需字段。
3. 画出 `request_id` 从请求、曝光到点击事件的传递。
4. 思考 Candidate 使用不可变对象和可变对象各有什么成本。
