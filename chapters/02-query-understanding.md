# 02 Query 理解

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 为什么 Query 理解是搜索的第一杠杆

召回和排序只能处理它们收到的意图。如果“苹果手机”没有识别成 Apple + 手机，后面再复杂的模型也可能错过 iPhone；如果“手机”被当成精确型号处理，就会缺少类目扩展和多样性。

教学实现位于 `code/src/shoprec/text.py`。

## 2. 归一化

```python
value = unicodedata.normalize("NFKC", value).strip().lower()
value = punctuation_pattern.sub(" ", value)
value = " ".join(value.split())
```

NFKC 会统一全角/半角和兼容字符。之后转小写、清理标点、压缩空格。

生产系统还需处理：

- 简繁体和地区词汇。
- emoji、特殊型号字符。
- 单位归一化，如 `256 g`、`256GB`、`256g`。
- 数字口语，如“一千五”。
- 恶意超长 Query 和控制字符。

归一化必须可回溯。展示和日志保留原 Query，召回使用规范 Query。

## 3. 纠错与同义词

教学版按顺序执行：

```text
iphnoe -> iphone
苹果手机 -> iphone
```

纠错处理输入错误，同义词处理正确但不同的表达。两者应分开，因为：

- 纠错需要置信度，低置信度时可同时召回原词和改写词。
- 同义词可能是单向，例如“小米”不能无条件改成“手机”。
- 品牌、型号和类目词需要不同更新频率。

生产常见策略：

```text
高置信纠错:
  只使用纠错词，并保留原词兜底

中置信纠错:
  原词与纠错词双路召回

低置信:
  不改写，展示“你是否想搜”
```

同义词要区分：

- 等价：`笔记本电脑 ↔ 笔记本`
- 上下位：`iPhone 15 -> 手机`
- 品牌别名：`苹果 -> Apple`
- 口语：`耳麦 -> 耳机`
- 业务别名：运营定义的类目映射

## 4. Term 生成

中文没有自然空格。教学版保留：

- 英文/数字词。
- 完整中文连续串。
- 中文二元组。

教学实现有意不生成单字词项，避免“手机”因为共享“机”而误召回耳机、相机和计算机。它仍只是为了标准库 Demo 可运行，生产中应使用：

- 搜索引擎中文 analyzer。
- 自定义词典和品牌/型号词典。
- Query 分词服务。
- 字符、词、短语和 embedding 多通道表示。

不能把教学版二元组直接当作生产分词器；它会产生噪声，只适合说明词法覆盖率。

## 5. 意图识别

教学版通过词典得到：

```python
category_intents = ["手机"]
brand_intents = ["Apple"]
```

生产 Query Understanding 通常还抽取：

- 型号：iPhone 15 Pro。
- 规格：256G、16GB。
- 属性：红色、降噪、全画幅。
- 成色：95 新。
- 价格：3000 以下。
- 地域：上海同城。
- 意图类型：找商品、找品牌店、找教程、售后。

结果可表达为结构化 DSL：

```json
{
  "must": [
    {"field": "category", "value": "手机"},
    {"field": "brand", "value": "Apple"}
  ],
  "should": [
    {"field": "model", "value": "iPhone 15 Pro"}
  ],
  "range": [
    {"field": "price", "lte": 7000}
  ]
}
```

召回服务负责把意图 DSL 翻译成 ES/OpenSearch/向量查询，排序服务把置信度和匹配程度作为特征。

## 6. 泛 Query 三道关

泛 Query 判定最重要的思想是：先找精确信号否决，再找泛词证据确认，证据都不足时按精确处理。

### 第一关：精确信号

包括：

- 品牌或型号。
- 数字规格。
- 强属性。
- 精细类目。
- 已有明确筛选。

“iPhone 15 256G”不是泛 Query，即使它属于“手机”。

### 第二关：泛词证据

包括：

- 泛词词典。
- 运营配置。
- 类目词和库存分布。
- Query 分类模型。

“手机”“包”“书”是典型泛词。

### 第三关：保守默认

无法确定时按精确 Query，避免扩召回导致结果完全偏离用户输入。

教学代码：

```python
has_precise_signal = bool(brands) or bool(re.search(r"\d", rewritten))
is_broad = rewritten in broad_words and not has_precise_signal
```

这段代码清楚但不完整。生产中应使用带置信度的多信号判定。

## 7. 泛 Query 如何影响下游

泛 Query 不是只打一个日志标签，它会改变：

- 召回：扩展更多类目或兴趣类目。
- 配额：兴趣组、非兴趣组、跨类目组分别分配。
- 排序：加入用户类目兴趣和类目质量。
- 重排：前排强相关，滑窗内按类目/品牌/成色打散。
- 筛选：提供有库存的动态筛选项。

如果只识别泛 Query，却不让下游消费这个字段，功能等于没有完成。

## 8. 风控

教学版用词典阻断风险 Query，并在 Trace 记录 `blocked=true`。生产中需要：

- 文本安全模型和规则组合。
- 版本化策略和申诉机制。
- 安全审计日志。
- 不把敏感原文无控制地写入日志。
- Fail-close 与 Fail-open 按风险级别区分。

高风险非法交易通常 Fail-close；低风险个性化服务异常可以 Fail-open 到通用搜索。

## 9. Debug 输出

Query 阶段至少输出：

```json
{
  "raw_query": "苹果手机",
  "normalized_query": "苹果手机",
  "rewritten_query": "iphone",
  "category_intents": ["手机"],
  "brand_intents": ["Apple"],
  "is_broad": false,
  "blocked": false,
  "reasons": [
    "query_rewrite:苹果手机->iphone",
    "precise_query"
  ]
}
```

线上面向普通用户不应返回完整内部 Debug；它应受鉴权、采样和脱敏控制。教学版默认返回是为了学习。

## 10. 练习

1. 增加“水果苹果”的歧义，避免错误识别为 Apple。
2. 支持“3000 以下的上海 iPhone”并生成结构化价格/城市约束。
3. 把低置信纠错改成原词与改写词双路召回。
4. 为 QueryAnalyzer 增加表驱动测试：输入、期望改写、意图、泛词、风险。
