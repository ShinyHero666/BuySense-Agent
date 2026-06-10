# 07 配置、AB 实验与运营干预

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 配置也是运行时代码

搜推系统中，以下行为通常无需发版即可改变：

- 启用哪些召回器。
- 粗排/精排候选上限。
- 模型版本。
- 流量池配额。
- 过滤和重排规则。
- 同义词、泛词和类目映射。
- 运营置顶、屏蔽和活动策略。

所以配置错误与代码 Bug 有相同破坏力，甚至传播更快。

## 2. 三类配置

### 2.1 Pipeline 配置

决定组件执行顺序：

```yaml
stages:
  - name: query
    components: [normalize, correct, intent]
  - name: recall
    parallel: [lexical, category, vector, hot]
  - name: filter
    components: [status, risk, stock, user_history]
  - name: rank
    components: [feature_fetch, model_predict]
```

真实执行顺序可能同时受 YAML 和代码硬编码影响。生产治理目标是只有一个权威顺序来源；若框架必须硬编码，应在启动时输出最终执行图并校验。

### 2.2 参数配置

例如：

```yaml
recall:
  lexical_limit: 500
  vector_limit: 300
roughrank:
  output_limit: 400
rerank:
  category_window: 5
  max_per_seller: 2
```

参数必须有类型、范围和默认值。`output_limit=-1` 或权重和不合理时应在发布前拒绝。

### 2.3 业务/运营配置

包括活动、类目扶持、屏蔽和手动商品。应带：

- 生效场景。
- 开始/结束时间。
- 优先级。
- 最大占比。
- 审核人。
- 过期自动清理。

## 3. 教学版 AB

`code/src/shoprec/experiments.py` 实现稳定哈希：

```python
digest = sha256(f"{layer}:{token}")
bucket = int(digest[:8], 16) % 100
```

然后按累计流量选择版本：

```text
0-49  -> control
50-99 -> treatment
```

同一 token 在同一 layer 稳定；layer 进入哈希盐，使搜索排序和推荐流量池可以独立分桶。

教学服务启动时真实加载 `code/config/experiments.json`，也可以通过 `--config` 或 `SHOPREC_EXPERIMENT_CONFIG` 指定路径。配置会检查必需 layer、精确参数名、流量总和、重复名称、有限非负参数和参数总和；即使所有 variant 都把 `lexical` 拼成同一个错误的 `lexcal`，也会在启动时拒绝，而不会把排序分数静默变成零。

## 4. 分桶主体

可选主体：

| 主体 | 优点 | 风险 |
|---|---|---|
| user_id | 跨会话稳定 | 未登录无 ID |
| device_id | 匿名可用 | 多设备不一致、隐私限制 |
| session_id | 会话内稳定 | 长期指标难归因 |
| request_id | 简单 | 每次跳组，不适合大多数体验实验 |
| item_id | 适合商品侧实验 | 用户体验可能混杂 |

教学默认使用 `experiment_token`，没有时退化为 `user_id`。

## 5. 分层与互斥

如果同时实验召回和排序，建议分层：

```text
recall_layer:
  recall_control vs vector_recall

rank_layer:
  rank_v11 vs rank_v12

flowpool_layer:
  normal vs explore_more
```

同层互斥，跨层可正交。这样能同时运行多个实验并估计独立效果。

以下情况不宜简单正交：

- 新召回只对新排序模型有效。
- 两个实验修改同一参数。
- 一个实验大幅改变另一个实验的样本分布。

此时使用联合实验：

```text
A: old recall + old rank
B: new recall + old rank
C: old recall + new rank
D: new recall + new rank
```

## 6. 实验参数覆盖

教学控制组：

```json
{
  "lexical": 0.30,
  "intent": 0.18,
  "freshness": 0.08,
  "trust": 0.18
}
```

新鲜度增强组：

```json
{
  "lexical": 0.12,
  "intent": 0.08,
  "freshness": 0.40,
  "trust": 0.20
}
```

教学数据量很小，因此这里故意使用强干预权重，让两组排序差异能够稳定观察。生产实验通常从更小的参数变化和更低流量开始。

一次请求响应中返回：

```json
"experiments": {
  "search_rank": "freshness_boost"
}
```

曝光和点击事件必须携带相同实验信息，否则无法正确归因。

## 7. 配置优先级

建议明确覆盖顺序：

```text
代码安全默认值
  < 环境配置
  < 场景配置
  < 实验参数
  < 紧急安全开关
```

越靠右优先级越高。紧急安全开关只能用于止损，不能成为长期业务配置。

Debug 应输出最终有效值和来源：

```json
{
  "roughrank.output_limit": {
    "value": 400,
    "source": "experiment:roughrank_v3"
  }
}
```

只输出原始多份配置无法判断最终行为。

## 8. 发布流程

```text
编辑
  -> Schema 校验
  -> 语义校验
  -> 单元/回放测试
  -> 审批
  -> 预发布
  -> 1% 灰度
  -> 指标守护
  -> 扩量
  -> 全量
```

语义校验示例：

- 所有流量比例和为 100。
- Flow Pool 配额非负。
- 必需组件不能被删除。
- 模型要求的特征都存在。
- 策略引用的字段在 schema 中。
- 生效时间和过期时间合理。

## 9. 灰度与回滚

配置客户端应保留：

- 当前成功版本。
- 上一成功版本。
- 本地持久快照。

配置中心不可用时继续使用最近成功版本，不要使用空配置。新配置加载失败应拒绝切换，而不是部分生效。

回滚按版本号，不要依赖人工重新输入旧值。

## 10. 运营干预边界

运营置顶应先通过：

- Query/场景相关性门槛。
- 商品安全与库存。
- 最大占位比例。
- 频控。
- 明确标识商业内容。

绝不能让配置绕过安全过滤。技术上可把安全过滤作为不可移除的框架阶段。

## 11. 实验分析

上线前定义：

- Primary metric。
- Guardrail metrics。
- 最小可检测效应。
- 样本量和实验周期。
- 停止规则。
- 分群分析计划。

不要在每天反复查看后“看到显著就停止”，这会增加假阳性。需要序贯检验或预先约定周期。

## 12. 练习

1. 修改 `experiments.json`，增加 `conversion_focus` 搜索版本，并用 `shoprec compare-search "手机"` 验证。
2. 写校验器确保 traffic 总和为 100。
3. 设计一个同时实验向量召回和排序模型的 2×2 方案。
4. 模拟配置中心返回非法配额，验证保留旧版本。
