# Lab 1：Run 与 SSE

## 目标

理解为什么购买决策不是一个长时间阻塞的 HTTP 请求，以及 Run 如何支持持久化、事件重放和取消。

## 观察

1. 用离线模式启动工作台并提交一次“套装决策”。
2. 在协作拓扑中观察 `run_created → run_started → task/artifact → result`。
3. 阅读 `v2-runs.ts` 和 `v2-server.test.ts`。

## 动手

在 `v2-server.test.ts` 增加一个测试：用 `Last-Event-ID` 重连后，不允许重复收到更早事件。先让测试失败，再修改实现。

## 检查

```bash
bash scripts/check-v2-lab.sh 1
```

完成证据：解释 Run ID、Event sequence、幂等键和取消信号各自解决什么问题。
