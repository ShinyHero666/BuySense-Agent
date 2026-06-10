# 实验索引

> 本目录是 Legacy 课程实验。当前产品主线请使用 [V2 六步实验](../../docs/v2-labs/README.md)。

每个实验对应一个可直接运行的检查点：

```bash
bash scripts/check-lab.sh 1
```

| Lab | 主题 | 检查点 |
|---:|---|---|
| 1 | 跟踪搜索请求 | Query 改写和召回来源 |
| 2 | Query 理解 | 泛 Query 和类目意图 |
| 3 | 多路召回 | 同一商品保留多个 source |
| 4 | 过滤理由 | 数量守恒 |
| 5 | 搜索实验 | 两个版本顺序不同 |
| 6 | 重排解释 | 前后位置与理由 |
| 7 | 推荐召回 | 每个结果有来源 |
| 8 | Flow Pool | 实验目标不同 |
| 9 | 曝光 TTL | 过期后可再次推荐 |
| 10 | 事件闭环 | 点击进入用户快照 |
| 11 | 双层重排 | 模型与规则阶段分离 |
| 12 | HTTP Adapter | 临时端口健康检查 |
| 13 | Agent 边界 | 工具 schema 与确认写保护 |
| 14 | iPhone 购买闭环 | 搜索、比较、确认、保存 |
| 15 | Grounding 与安全 | 引用、注入、PII、Trace |
| 16 | Agent 评测 | 100 条离线门禁 |
| 17 | ModelPort 优化 | 真实路由证据与参数归一化 |
| 18 | 诊断 Agent | 只读证据到实验建议 |

动手前运行 `bash scripts/start-lab.sh <Lab>` 备份，卡住时查看 [solutions.md](./solutions.md)，完成后运行 `bash scripts/reset-lab.sh` 恢复。Agent 实验的详细任务见 `lab13-*.md` 到 `lab18-*.md`。
