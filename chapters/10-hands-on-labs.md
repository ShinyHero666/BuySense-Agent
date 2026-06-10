# 10 实验课

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 实验工作流

每个实验都有独立检查点。开始前先备份：

```bash
cd moyuan-sar-agent/code
bash scripts/start-lab.sh 1
```

检查进度：

```bash
bash scripts/check-lab.sh 1
```

恢复基线：

```bash
bash scripts/reset-lab.sh
```

参考解答位于 [code/labs/solutions.md](../code/labs/solutions.md)。建议顺序是：运行基线、读代码、完成扩展、运行检查点、最后看答案。Lab 13–18 是搜推 Agent Part II，任务入口见 [实验索引](../code/labs/README.md)。

## Lab 1：跟踪一次搜索请求

运行：

```bash
.venv/bin/shoprec search "苹果手机" --user u001 --view summary
.venv/bin/shoprec search "苹果手机" --user u001 --view trace
.venv/bin/shoprec search "苹果手机" --user u001 --view explain
```

搜索阶段：

```text
QUERY -> RECALL -> FILTER -> FEATURE
-> ROUGHRANK -> RANK -> RERANK -> RESULT
```

观察：

- `苹果手机` 改写成 `iphone`。
- 第一名同时命中 lexical、category_intent、brand_intent。
- `p016` 因库存为 0 被过滤。
- RESULT 只组装分页后的商品。

扩展：给 Trace 增加总耗时和每阶段耗时占比。

验收：

```bash
bash scripts/check-lab.sh 1
```

## Lab 2：Query 理解

目标：理解 normalize、纠错、同义词、意图、风险和泛 Query 的顺序。

运行：

```bash
.venv/bin/shoprec search "手机" --view explain
.venv/bin/shoprec search "iphnoe" --view explain
```

扩展：支持“3000 以下的 iPhone”，在 `QueryContext` 中输出结构化价格约束，并与显式 filters 取交集。

边界：

- “iPhone 15 256G”不能被判成泛 Query。
- 低置信纠错应保留原词召回。
- 自动解析不能覆盖客户端显式筛选。

## Lab 3：增加语义召回

基线已有 lexical、category、brand、hot 四路。新增教学语义图：

```python
semantic_neighbors = {
    "电脑包": {"背包", "通勤"},
    "降噪": {"安静", "通勤耳机"},
}
```

要求：

- Source 名为 `semantic`。
- 合并后保留其他来源。
- Semantic 异常不影响 lexical。
- Trace 输出每路候选数量。

验收重点：同一商品可能同时命中多个 source。

## Lab 4：过滤理由数量守恒

运行价格过滤：

```bash
.venv/bin/shoprec search "iphone" --max-price 3000 --view trace
```

守恒公式：

```text
FILTER.input
= FILTER.output
+ sum(primary_filter_reasons)
```

扩展：故意删除一个候选但不写理由，确认检查点失败；修复后再次运行。

因为实验前已创建 `.lab-backup`，不需要手工回忆原代码。

## Lab 5：搜索实验

```bash
.venv/bin/shoprec compare-search "手机" --size 8
```

观察：

- CLI 自动寻找稳定命中每个 variant 的 token。
- 相同 token 重复请求始终在同一组。
- `freshness_boost` 改变新商品的顺序。

扩展：在 `config/experiments.json` 增加 `conversion_focus`。注意同层 traffic 必须合计 100，参数必须合计 1。

非法配置应在启动时失败，而不是请求处理中部分生效。

## Lab 6：重排解释

```bash
.venv/bin/shoprec search "手机" --view explain
```

检查每个结果：

```json
{
  "before_position": 4,
  "after_position": 2,
  "reasons": ["POSITION_CHANGED"]
}
```

扩展：增加 `BRAND_REPEAT_PENALTY` 和成色打散，并确保精确 Query “iPhone 15”不启用泛词多样性。

## Lab 7：跟踪推荐十阶段

```bash
.venv/bin/shoprec recommend --user u001 --size 8 --view trace
```

阶段：

```text
QUERY -> RECALL -> FILTER -> FEATURE -> ROUGHRANK
-> FLOWPOOL -> RANK -> MODEL_RERANK -> RULE_RERANK -> RESULT
```

观察：

- QUERY 输出场景、兴趣类目数和实验。
- RECALL 包含 interest、item_cf、hot、fresh。
- `p002` 已购过滤，`p011` 负反馈过滤。
- 模型重排和规则重排是两个独立失败边界。

## Lab 8：Flow Pool 实验

```bash
.venv/bin/shoprec compare-flowpool --size 8
```

基线目标：

```text
control      -> interest=5, explore=2, fresh=1
explore_more -> interest=3, explore=3, fresh=2
```

`pool_targets` 是目标，`pool_counts` 是实际。某池候选不足时从全局模型顺序补量。

扩展：增加 `local` 同城池，并使用最大余数法保证整数目标之和永远等于 size。

## Lab 9：曝光 TTL

基线使用：

```text
(user_id, scene, product_id) -> expires_at
```

测试不使用 `sleep`，而是：

```python
clock.advance(hours=25)
```

扩展：

- 为不同 scene 设置不同 TTL。
- 增加单商品曝光频次而非二值去重。
- 设计 Redis ZSet Adapter。

## Lab 10：事件闭环

回放完整场景：

```bash
.venv/bin/shoprec scenario scenarios/feedback-loop.json --full
```

它会：

1. 请求第一页推荐。
2. 记录一次摄影商品点击。
3. 在同一 Service 状态中请求第二页。

扩展：点击后实时提高短期类目兴趣，并为兴趣设置时间衰减。

## Lab 11：模型超时降级

基线已经拆分：

```text
RANK -> MODEL_RERANK -> RULE_RERANK
```

基线提供 `ModelReranker` 接口和会抛 `TimeoutError` 的 `FailingModelReranker`。在测试中注入它并验证：

- MODEL_RERANK 失败时保留 RANK 顺序。
- Trace 标记 `degraded=true`。
- RULE_RERANK 仍执行安全和池配额。
- HTTP 仍返回 200。

验收不能只看“有结果”，还要检查降级元数据。

## Lab 12：HTTP、Docker 和生产 Adapter

本地：

```bash
.venv/bin/shoprec-server --host 127.0.0.1 --port 18080
```

Docker：

```bash
docker compose up --build
curl -fsS http://127.0.0.1:18080/health/ready
```

接口输入错误应返回结构化 400。容器内监听 `0.0.0.0`，本机可以监听 `127.0.0.1`。

扩展：任选一个 Adapter：

- 商品仓库：内存 → OpenSearch。
- 用户/曝光：内存 → Redis。
- 事件发布：同步 → Kafka。
- Ranker：本地函数 → HTTP/gRPC。

核心 Engine 不得直接依赖云 SDK。

## 墨圆业务专题加练

以下练习建立在[第 13 章](./13-moyuan-secondhand-business-case.md)上，不计入 12 个基础检查点。

### 加练 A：墨圆质选搜索

为手机搜索增加质检等级、电池健康、质保和供给渠道筛选。验证不合格质检商品始终在排序前退出。

### 加练 B：回收估价

运行 `value-device`，增加一个故障因子。要求输出价格影响、风险标志，并保持“最终价需要实物质检”的契约。

### 加练 C：寄卖库存

为寄卖商品增加 `inventory_age_days`，设计降价建议或有限探索策略。证明它不会无限挤压强相关商品。

### 加练 D：质检复价回放

构造“用户自述 B 级、质检发现进水”的场景，比较预估价与最终报价，并输出可解释差异。

## 大作业

实现“详情页猜你喜欢”：

```text
输入:
  user_id + current_product_id + scene=detail

召回:
  同类目、同品牌、ItemCF、向量、同城、热门

过滤:
  当前商品、已售、已购、曝光、同卖家频控

Flow Pool:
  similar 60%, alternative 25%, explore 15%

精排:
  item_similarity + price_fit + pCTR + pCVR + quality

重排:
  相似优先、价格带多样、卖家打散
```

交付标准：

- 请求、响应和事件契约。
- 完整阶段 Trace。
- 可运行实现和不少于 15 个测试。
- 两个可观察差异的 AB 版本。
- 超时降级与结构化错误。
- 场景 JSON 和预期结果。
- Docker Compose 启动。
- 从教学 Adapter 到生产组件的映射。
