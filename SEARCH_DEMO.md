# Legacy 搜推课程：搜索系统演示手册

> **Legacy 教材**：本文演示传统搜索 Pipeline，不是墨圆智选当前产品入口。当前搜广推多 Agent 决策工作台见 [Moyuan SAR Agent V2](./code/agent-control-plane/README.md)。

这份手册用于第一次运行、课堂演示和现场 Workshop。目标不是只看到一页商品，而是在一小时内理解一次请求如何经过 Query、召回、过滤、特征、排序、重排和结果组装。

## 1. 60 秒运行

在 Linux Bash 终端中执行：

```bash
cd moyuan-sar-agent/code
bash scripts/quick-search.sh --section rewrite
```

脚本会在缺少虚拟环境时自动运行初始化，然后展示：

- “苹果手机”如何改写成 `iphone`。
- lexical、category_intent、brand_intent 如何合并。
- 缺货商品如何被过滤。
- 特征怎样形成粗排和精排分数。
- 每个商品为什么进入结果。

完整导览：

```bash
bash scripts/quick-search.sh
```

完整导览包含六个独立演示：

| 演示 | 核心问题 |
|---|---|
| Query 改写 | 用户词与系统标准词如何统一？ |
| 泛 Query 多样性 | “手机”为什么不能只按单点分数排序？ |
| 筛选与价格排序 | 硬约束如何改变候选漏斗？ |
| 热门兜底 | 没有词法命中时怎样避免空结果？ |
| 排序实验 | 两组权重如何稳定分桶并改变顺序？ |
| 风险拦截 | 为什么风险 Query 不应进入召回？ |

只运行一个部分：

```bash
bash scripts/quick-search.sh --section broad
bash scripts/quick-search.sh --section filters
bash scripts/quick-search.sh --section fallback
bash scripts/quick-search.sh --section experiments
bash scripts/quick-search.sh --section safety
```

## 2. 四种输出视图

### Summary：快速看结果

```bash
.venv/bin/shoprec search "苹果手机" --size 5 --view summary
```

适合查看实验版本、Query 改写、结果数量、价格和最终分数。

### Trace：看阶段漏斗

```bash
.venv/bin/shoprec search "手机" --view trace
```

重点观察：

```text
QUERY -> RECALL -> FILTER -> FEATURE
-> ROUGHRANK -> RANK -> RERANK -> RESULT
```

每个阶段都有输入数、输出数、耗时和元数据。排障时先找数量或耗时从哪个阶段开始异常。

### Explain：完整课堂讲解

```bash
.venv/bin/shoprec search "手机" --size 5 --view explain
```

该视图依次展示：

1. Query 归一化、改写、意图和判定理由。
2. 八阶段候选漏斗。
3. 结构化过滤理由。
4. 过滤后 Facets。
5. 每个结果的属性、召回源、特征和分数。
6. 重排前后位置与理由码。

### Full：程序调试和二次开发

```bash
.venv/bin/shoprec search "iphone" --size 3 --view full
```

输出完整 JSON，适合写断言、接 Notebook 或保存为回放样本。

## 3. 搜索命令矩阵

### Query 改写

```bash
.venv/bin/shoprec search "苹果手机" --view explain
.venv/bin/shoprec search "iphnoe" --view explain
.venv/bin/shoprec search "笔记本电脑" --view explain
```

检查 `rewritten` 和 `reasons`，分别观察同义词、纠错和类目标准化。

### 精确 Query 与泛 Query

```bash
.venv/bin/shoprec search "iphone 15" --view explain
.venv/bin/shoprec search "手机" --view explain
```

品牌、型号和数字是精确信号；纯类目词更可能是泛 Query。泛 Query 在相关性基础上增加类目和卖家多样性。

### 类目、品牌、城市和成色筛选

筛选参数可以重复：

```bash
.venv/bin/shoprec search "手机" --category "手机" --view explain
.venv/bin/shoprec search "手机" --brand "Apple" --view explain
.venv/bin/shoprec search "电脑" --city "上海" --view explain
.venv/bin/shoprec search "iphone" --condition "95新" --condition "99新" --view explain
```

检查 FILTER 阶段是否满足：

```text
input_count = output_count + sum(filter_reasons)
```

### 价格筛选

```bash
.venv/bin/shoprec search "手机" --min-price 2500 --max-price 5000 --view explain
```

重点区分 `below_min_price`、`above_max_price` 和 `out_of_stock`。

### 排序模式

```bash
.venv/bin/shoprec search "iphone" --sort relevance --view summary
.venv/bin/shoprec search "iphone" --sort price_asc --view summary
.venv/bin/shoprec search "iphone" --sort price_desc --view summary
.venv/bin/shoprec search "相机" --sort newest --view summary
```

`relevance` 执行相关性排序和泛 Query 重排；显式价格或时间排序仍保留召回和硬过滤。

### 分页

```bash
.venv/bin/shoprec search "手机" --page 1 --size 3
.venv/bin/shoprec search "手机" --page 2 --size 3
```

比较 `total` 与当前页结果数，确认分页发生在重排之后。

### 热门兜底

```bash
.venv/bin/shoprec search "露营灯" --view explain
```

没有词法召回时，RECALL 元数据和结果的 `recall_sources` 应出现 `hot_fallback`。兜底结果仍要经过库存和请求筛选。

### 风险与非法输入

```bash
.venv/bin/shoprec search "假证" --view explain
.venv/bin/shoprec search "手机" --page 0
.venv/bin/shoprec search "手机" --min-price 5000 --max-price 1000
```

风险 Query 返回可解释的空结果；非法分页和倒置价格范围返回结构化校验错误并使用退出码 `2`。

## 4. 查看教学商品目录

```bash
.venv/bin/shoprec catalog
.venv/bin/shoprec catalog --category "摄影"
.venv/bin/shoprec catalog --brand "Apple"
```

目录展示商品 ID、类目、品牌、价格、库存、城市和标题。修改样例数据前先用它确认当前测试数据。

## 5. 实验比较

```bash
.venv/bin/shoprec compare-search "手机" --size 8
```

命令会寻找稳定命中每个 variant 的 token。比较时不要只看第一名，还要检查：

- 两组权重是否不同。
- 商品集合是否变化。
- 相同商品的位置是否变化。
- 新鲜度增强是否牺牲了部分相关性。
- 改动是否只发生在目标实验层。

一键导览中的实验部分会输出 `changed: True/False`：

```bash
bash scripts/quick-search.sh --section experiments
```

## 6. 场景回放

完整搜索学习路径：

```bash
.venv/bin/shoprec scenario scenarios/search-learning-path.json
```

筛选与排序回归：

```bash
.venv/bin/shoprec scenario scenarios/search-sorts-and-filters.json
```

场景断言覆盖 Query 判定、顶部商品、结果前缀、召回源、过滤理由、字段一致性、排序方向、重排理由和阶段顺序。

## 7. HTTP 演示

启动服务：

```bash
.venv/bin/shoprec-server --host 127.0.0.1 --port 18080
```

另开一个终端：

```bash
curl -fsS http://127.0.0.1:18080/api/search \
  -H 'Content-Type: application/json' \
  --data-binary '{
    "query": "手机",
    "user_id": "u001",
    "sort": "price_asc",
    "page_size": 5,
    "filters": {
      "categories": ["手机"],
      "max_price": 5000
    }
  }'
```

API 与 CLI 共用同一 Service 和请求校验，因此结果契约、Trace 和错误结构一致。

## 8. 45 分钟课堂流程

| 时间 | 教师操作 | 学员应回答 |
|---|---|---|
| 0-5 分钟 | 运行 rewrite 导览 | Query 为什么改写？三路召回如何合并？ |
| 5-12 分钟 | 切换 Trace | 哪个阶段减少了候选？ |
| 12-20 分钟 | 运行 broad 导览 | 哪些商品被重排，原因是什么？ |
| 20-28 分钟 | 增加类目和价格筛选 | 过滤数量是否守恒？ |
| 28-34 分钟 | 运行 fallback 和 safety | 兜底与风险空结果有何区别？ |
| 34-40 分钟 | 比较实验 | 如何证明变化由实验参数造成？ |
| 40-45 分钟 | 修改一个场景期望并回放 | 自动回归怎样阻止错误上线？ |

## 9. 学员练习

1. 为“相机”增加品牌筛选并解释召回来源。
2. 比较 `relevance` 和 `newest` 的前五名。
3. 找到一个会触发 `hot_fallback` 的新 Query。
4. 修改同义词，让“苹果机”改写为 `iphone`，补测试。
5. 增加一个价格区间场景，断言排序和过滤理由。
6. 修改实验新鲜度权重，预测并验证位置变化。
7. 注释掉缺货过滤，观察哪项场景和测试首先失败。

完成后执行：

```bash
bash scripts/verify.sh
```

只有测试与全部场景都通过，才算完成一次可回归的搜索改动。

## 10. 常见问题

### 找不到 Python

安装 Python 3.10+，确保 `python3 --version` 可用，再运行 `bash scripts/bootstrap.sh`。

### `.venv` 来自其他操作系统

Linux 虚拟环境必须包含 `.venv/bin/python`。如果目录中已有其他操作系统创建的 `.venv`，先将它移出课程目录；也可以指定独立路径：

```bash
export SHOPREC_VENV="$HOME/.cache/moyuan-shoprec-venv"
bash scripts/bootstrap.sh
```

### 端口 18080 被占用

换用其他端口：

```bash
.venv/bin/shoprec-server --host 127.0.0.1 --port 18081
```

### 输出太长

先使用 `summary`，再用 `trace` 定位阶段，最后对目标请求使用 `explain`。`full` 主要用于程序消费和调试。

## 11. 墨圆拟真业务专题

完成通用搜索导览后，运行：

```bash
bash scripts/quick-moyuan-case.sh
```

它会把搜索扩展到墨圆质选、质检等级、电池健康、质保、回收预估价和寄卖供给。主线、模拟扩展与生产事实的边界见[第 13 章](./chapters/13-moyuan-secondhand-business-case.md)。
