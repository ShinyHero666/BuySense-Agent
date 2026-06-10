# 06 数据闭环与索引

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 三条数据链

搜推系统至少有三条数据链：

```text
商品链:
  商品创建/修改/状态 -> 索引和商品特征

行为链:
  曝光/点击/收藏/购买 -> 实时画像和湖仓

模型链:
  湖仓样本 -> 训练 -> 模型注册 -> 在线推理
```

只做在线 API 不做闭环，系统只能停留在手工规则。

## 2. 商品事件

推荐事件 schema：

```json
{
  "event_id": "01J...",
  "event_type": "product_updated",
  "product_id": "p001",
  "product_version": 42,
  "event_time": "2026-07-29T09:00:00Z",
  "payload": {
    "title": "iPhone 15 Pro 256G 国行",
    "category": "手机",
    "price": 6299,
    "stock": 1,
    "status": "ON_SALE"
  }
}
```

关键点：

- `event_id` 做幂等。
- `product_version` 处理乱序。
- `event_time` 与 `ingest_time` 分开。
- 删除/售出使用 tombstone 或明确状态事件。

索引更新伪代码：

```text
current = index.get(product_id)
if event.product_version <= current.product_version:
    ignore stale event
else if event.status is not searchable:
    delete or mark invisible
else:
    upsert document
```

## 3. 搜索索引设计

示例 OpenSearch/Elasticsearch 文档：

```json
{
  "product_id": "p001",
  "title": "iPhone 15 Pro 256G 国行",
  "title_terms": ["iphone", "15", "pro", "256g", "国行"],
  "category_id": "phone",
  "brand_id": "apple",
  "price": 6299,
  "city_id": "shanghai",
  "condition_level": 95,
  "stock": 1,
  "quality_score": 0.95,
  "publish_time": "2026-07-27T00:00:00Z",
  "embedding": [0.12, -0.08, 0.31],
  "product_version": 42
}
```

字段类型：

- `title`：text + keyword/multi-field。
- 类目、品牌、城市：keyword。
- 价格、质量、库存：numeric。
- 时间：date。
- embedding：knn/dense_vector。

不要把频繁变化的用户特征写入商品索引；那会造成高频重建和一致性问题。

## 4. 全量与增量

索引建设需要两种路径：

```text
全量:
  数据库/湖仓快照 -> 清洗 -> 新索引 -> 校验 -> alias 切换

增量:
  CDC/商品事件 -> Kafka -> Flink -> 当前在线索引
```

全量构建期间增量仍在发生。常用方法：

1. 记录全量快照位点。
2. 构建新索引。
3. 回放位点之后的增量。
4. 对账文档数、状态和抽样字段。
5. 原子切换 alias。
6. 保留旧索引用于回滚。

## 5. 行为事件

统一事件格式：

```json
{
  "event_id": "e-123",
  "request_id": "search-abc",
  "session_id": "sess-1",
  "user_id": "u001",
  "device_id": "d001",
  "scene": "search",
  "event_type": "click",
  "product_id": "p001",
  "position": 1,
  "query": "苹果手机",
  "event_time": "2026-07-29T09:00:02Z",
  "experiments": {"search_rank": "control"},
  "model_version": "search_rank_v12"
}
```

事件类型至少包括：

- request。
- item_delivered。
- item_visible_exposure。
- click。
- favorite。
- chat/contact。
- order_created。
- paid。
- dislike/hide/report。

`delivered` 和 `visible_exposure` 不能混为一谈。页面下发 20 条但用户只看到 5 条，CTR 分母差异很大。

## 6. 实时计算

Flink 可维护：

```text
user_category_click_10m
user_category_click_1d
item_exposure_5m
item_ctr_1h
seller_negative_rate_7d
query_product_click_7d
```

伪 SQL：

```sql
SELECT
  user_id,
  category_id,
  COUNT(*) AS clicks_10m,
  MAX(event_time) AS last_click_time
FROM behavior_events
WHERE event_type = 'click'
GROUP BY
  user_id,
  category_id,
  HOP(event_time, INTERVAL '1' MINUTE, INTERVAL '10' MINUTE);
```

输出到在线特征库时应包含：

- 特征值。
- 计算窗口截止时间。
- 特征版本。
- TTL。

## 7. 迟到、乱序和重复

流处理必须考虑：

- 客户端离线后补发。
- MQ 至少一次投递。
- 多端时钟偏差。
- 支付事件晚于点击数天。

处理手段：

- `event_id` 去重。
- Watermark 和 allowed lateness。
- Upsert key。
- 对可重算指标使用幂等状态。
- 离线回补与实时结果对账。

## 8. 曝光到反馈

教学代码 `record_event` 直接更新内存：

```python
if click:
    user.recent_clicks.append(product_id)
elif purchase:
    user.purchased.add(product_id)
elif dislike:
    user.disliked_products.add(product_id)
```

生产路径：

```text
HTTP Event Collector
  -> Kafka
  -> Flink
      -> Redis/Feature Store
      -> OLAP 实时看板
  -> Lakehouse
      -> 样本构建
      -> 离线评估
      -> 模型训练
```

主请求服务不应同步执行复杂画像更新。

## 9. 数据质量

必须监控：

- 事件量和按类型比例。
- request 与 exposure/click 的关联率。
- event_time 延迟分布。
- 重复率。
- 必填字段缺失率。
- 商品索引与源库状态一致率。
- 在线/离线特征差异。
- 各客户端版本埋点差异。

数据量“看起来正常”不等于口径正确。一次客户端升级可能把 position 从 0-based 改成 1-based，事件量不变但训练数据已污染。

## 10. 隐私和保留

用户行为属于敏感数据。设计时需要：

- 只收集实现目的所需字段。
- 用户标识伪匿名化。
- 传输和静态加密。
- 按角色授权。
- 访问审计。
- 明确保留期和删除流程。
- 训练数据可响应用户删除请求。

Debug 平台尤其容易暴露 Query、位置和用户行为，必须做脱敏、采样和访问控制。

## 11. 对账

每日对账可包括：

```text
源库可搜索商品数
  vs 索引可搜索文档数

服务下发曝光数
  vs 客户端收到数
  vs 客户端可视曝光数

支付订单数
  vs 行为流归因订单数
```

差异超过阈值时阻止模型训练或索引切换。

## 12. 练习

1. 给事件 schema 增加 `schema_version` 并设计兼容规则。
2. 设计商品售出后 10 秒内从搜索消失的链路和 SLA。
3. 写一个按 `event_id` 去重、按 `product_version` 拒绝旧事件的处理器。
4. 说明为什么不能用点击后的用户画像回填曝光样本。
