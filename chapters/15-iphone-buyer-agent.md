# 15 iPhone 购买决策搜推 Agent

> **Legacy 教材**：本章描述 V1 二手购买决策 Agent，不代表墨圆智选当前多 Agent 产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

本章把一个真实可演示的购买场景走到底。目录包含 200 条确定性生成的合成 iPhone 商品，覆盖型号、容量、价格、城市、质检等级、电池、质保、维修史、库存和异常内容。

## 1. 使用场景

用户输入：

> 预算不超过 3600 元，想买 128G 以上的二手 iPhone，电池至少 87，必须有质保，不接受维修和进水。

Agent 需要完成：

1. 抽取 `budget_max=3600`、`storage_min_gb=128`、`battery_min=87`；
2. 把预算、电池、质保和维修偏好标成硬约束；
3. 调用搜索，最多返回 3 个候选；
4. 对每个候选给出型号、容量、价格、电池、质保和维修史证据；
5. 比较时只使用目录工具输出；
6. 保存前再问一次确认。

## 2. 数据到业务 API

数据文件是 `src/shoprec/data/iphone_catalog.jsonl`。可重复生成：

```bash
PYTHONPATH=src:. python3 scripts/generate_agent_data.py
wc -l src/shoprec/data/iphone_catalog.jsonl
```

应得到 200。所有数据均为合成资产，不表示真实墨圆平台库存或承诺。

`create_agent_service()` 复用 Part I 的 `CommerceDiscoveryService`。Agent 搜索不是再造一个检索器，而是在现有搜索 API 之上增加：

- iPhone 域边界；
- `platform_inspected` 和质检通过过滤；
- 容量、型号和维修史后置硬过滤；
- 商品标题 Prompt Injection 拦截；
- 最多 3 个可解释候选。

## 3. 搜索、推荐与比较如何分工

- 用户有明确 Query 和约束：`search_products`；
- 用户身份明确但需求较泛：允许模型选择 `recommend_products`，业务策略仍可归一为搜索；
- 用户说“比较前三个”：`compare_products`；
- 没有结果：提示用户调用 `suggest_relaxations`；
- 用户明确点击或不喜欢某个 ID：`record_feedback`。

比较工具给出的 `median_price` 是本合成目录中同型号、容量和质检等级商品的中位挂牌价，并明确标记 `not a market valuation`。

## 4. 无结果处理

输入：

> 预算不超过 800 元，必须质保，不接受维修和进水，想买 iPhone 15。

搜索应返回空列表。Agent 不会删除 iPhone 15 或抬高预算，而是说明“当前约束下无候选”。用户要求最小放宽建议后，工具可能列出：预算 +200、电池阈值 -3、取消同城或调整容量。

这些选项全部是 `applied=false`。库存、质检通过、进水和主板维修安全条件不会被自动放宽。

## 5. 三种使用方式

交互 CLI：

```bash
PYTHONPATH=src:. python3 -m shoprec.cli agent
```

HTTP：

```bash
PYTHONPATH=src:. python3 -m shoprec.server --host 127.0.0.1 --port 18080
curl -sS http://127.0.0.1:18080/api/agent \
  -H 'content-type: application/json' \
  -d '{"session_id":"demo-1","user_id":"u001","message":"预算3000元，必须质保，不接受维修"}'
```

最小教学页面：打开 `http://127.0.0.1:18080/agent-lab`。

## 6. 状态与记忆

V1 只保留进程内会话记忆：已确认需求、最近候选、待保存清单、证据和 Trace。不会把原始对话自动写入长期画像。显式保存只写候选 ID，并以 `(user_id, session_id)` 隔离。

生产化时可替换会话和清单 Store，但需要补充 TTL、删除权、用途说明、审计和用户主动授权，不能把内存字典直接换成数据库就宣称合规。

## 7. 练习

1. 对“想买个省心的 iPhone”观察两次以内的澄清；
2. 验证“保存前两个”不会立即写入；
3. 让模型尝试返回 5 个候选，证明工作流仍裁到 3 个；
4. 增加一个城市硬约束并验证不可静默放宽。

对应实验：[Lab 14](../code/labs/lab14-buyer-workflow.md)。
