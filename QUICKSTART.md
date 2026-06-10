# Legacy 搜推课程：30 分钟快速上手

> **Legacy 教材**：本文用于运行早期搜索推荐课程与二手案例。墨圆智选当前产品主线和本地千问演示见 [Moyuan SAR Agent V2](./code/agent-control-plane/README.md)。

这条路线不要求先读完整报告。目标是先跑通、看懂一条请求，再回到章节补原理。

## 0 到 5 分钟：安装和自检

```bash
cd moyuan-sar-agent/code
bash scripts/bootstrap.sh
```

脚本会创建 Linux 虚拟环境 `.venv`、链接本地源码，并执行编译、完整测试套件和自动发现到的全部场景。项目没有第三方运行依赖，初始化不需要访问 PyPI。看到 `All checks passed.` 才进入下一步。

脚本不依赖当前工作目录：它会根据 `scripts/bootstrap.sh` 的真实位置定位代码根目录。课程命令默认在 Linux Bash 5+ 下执行。

## 5 到 10 分钟：运行搜索

第一次运行推荐使用一键讲解：

```bash
bash scripts/quick-search.sh --section rewrite
```

它会同时展示 Query 决策、候选漏斗、过滤理由、Facets、商品特征、分数和召回来源。完整六段导览执行：

```bash
bash scripts/quick-search.sh
```

最简摘要命令仍然可用：

```bash
.venv/bin/shoprec search "苹果手机" --user u001 --view summary
```

你应该看到：

- Query 从“苹果手机”改写为 `iphone`。
- `p001`、`p002` 排在前列。
- 第一名同时命中 lexical、category_intent、brand_intent。
- 已售的 `p016` 不出现在结果中。

再看阶段：

```bash
.venv/bin/shoprec search "手机" --user u001 --view trace
```

搜索阶段应为：

```text
QUERY -> RECALL -> FILTER -> FEATURE
-> ROUGHRANK -> RANK -> RERANK -> RESULT
```

重点看每阶段 `input/output`，这是排障时最有用的数量漏斗。

需要逐商品讲解时使用：

```bash
.venv/bin/shoprec search "手机" --user u001 --size 5 --view explain
```

筛选、分页和排序也可以直接从 CLI 组合：

```bash
.venv/bin/shoprec search "手机" \
  --category "手机" --max-price 5000 \
  --sort price_asc --page 1 --size 5 --view explain
```

## 10 到 15 分钟：运行推荐

```bash
.venv/bin/shoprec recommend --user u001 --size 8 --view summary
```

推荐阶段应为：

```text
QUERY -> RECALL -> FILTER -> FEATURE -> ROUGHRANK
-> FLOWPOOL -> RANK -> MODEL_RERANK -> RULE_RERANK -> RESULT
```

摘要会同时显示 `pool targets` 和 `pool actual`。目标是实验策略，实际值还会受到池候选不足的影响。

## 15 到 20 分钟：比较实验

```bash
.venv/bin/shoprec compare-search "手机"
.venv/bin/shoprec compare-flowpool --size 8
```

第一个命令比较搜索控制组与新鲜度增强组；第二个比较推荐 `control` 与 `explore_more`。两组结果必须能观察到顺序、商品集合或池目标变化。

配置文件是真实运行配置：

```text
code/config/experiments.json
```

修改后重新执行比较命令即可生效。非法流量比例、负权重或权重和不等于 1 会在启动时直接报错。

## 20 到 25 分钟：场景回放

```bash
.venv/bin/shoprec scenario scenarios/search-basic.json
.venv/bin/shoprec scenario scenarios/search-learning-path.json
.venv/bin/shoprec scenario scenarios/search-sorts-and-filters.json
.venv/bin/shoprec scenario scenarios/recommend-pipeline.json
.venv/bin/shoprec scenario scenarios/feedback-loop.json
.venv/bin/shoprec scenario scenarios/moyuan-secondhand-business-case.json
```

场景文件同时保存请求和期望，可作为回归测试和学习检查点。搜索场景会检查 Query 改写、泛词、排序方向、字段筛选、召回源、过滤理由、重排理由和风险拦截。把某个期望商品 ID 改错，观察场景怎样失败。

## 25 到 30 分钟：启动 HTTP

本机端口 8080 被占用时直接换端口：

```bash
.venv/bin/shoprec-server --host 127.0.0.1 --port 18080
```

另开一个终端：

```bash
curl -fsS http://127.0.0.1:18080/health/ready
```

Docker 使用：

```bash
docker compose up --build
curl -fsS http://127.0.0.1:18080/health/ready
```

## 加餐：购买决策搜推 Agent

默认离线模式不访问任何模型服务：

```bash
.venv/bin/moyuan agent \
  --message "预算不超过3500元，128G以上，电池至少86，必须质保，不接受维修" \
  --message "比较前三个" \
  --message "保存前两个" \
  --message "确认保存"
```

同一 HTTP 服务还提供 `POST /api/agent` 和教学页面 `http://127.0.0.1:18080/agent-lab`。完整学习顺序从[第 14 章](./chapters/14-agent-boundaries-tools-and-workflow.md)开始。

## 下一步

按顺序阅读：

1. [搜索快速演示手册](./SEARCH_DEMO.md)
2. [领域模型与架构](./chapters/01-domain-and-architecture.md)
3. [搜索 Pipeline](./chapters/03-search-pipeline.md)
4. [推荐 Pipeline](./chapters/04-recommendation-pipeline.md)
5. [墨圆拟真业务案例](./chapters/13-moyuan-secondhand-business-case.md)
6. [实验课](./chapters/10-hands-on-labs.md)
7. [搜推 Agent 边界与工作流](./chapters/14-agent-boundaries-tools-and-workflow.md)

墨圆拟真业务专题可一键运行：

```bash
bash scripts/quick-moyuan-case.sh
```
