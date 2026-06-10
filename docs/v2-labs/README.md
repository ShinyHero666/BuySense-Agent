# Moyuan SAR Agent V2 六步实验

这套实验对应当前产品主线。每个实验都包含“目标、观察、动手修改、检查点、完成证据”。建议先创建自己的 Git 分支：

```bash
git switch -c learning/v2-labs
cd code
```

| Lab | 主题 | 你要回答的问题 |
|---:|---|---|
| 1 | Run 与 SSE | 一次请求怎样异步运行、恢复和取消？ |
| 2 | 意图与约束 | 模型为什么不能抹掉用户硬约束？ |
| 3 | 搜推广协作 | 三个通道怎样协作又保持边界？ |
| 4 | 融合与组合 | RRF、广告保护和全局组合如何工作？ |
| 5 | 证据与确认 | 价格、评论、兼容和购物车如何失败关闭？ |
| 6 | 降级与评测 | 组件故障为什么不会变成无边界兜底？ |

逐个阅读：

1. [Lab 1：Run 与 SSE](./01-run-and-sse.md)
2. [Lab 2：意图与约束](./02-intent-and-constraints.md)
3. [Lab 3：搜推广协作](./03-sar-collaboration.md)
4. [Lab 4：融合与组合](./04-fusion-and-bundles.md)
5. [Lab 5：证据与确认](./05-evidence-and-confirmation.md)
6. [Lab 6：降级与评测](./06-degradation-and-evaluation.md)

统一检查命令：

```bash
bash scripts/check-v2-lab.sh 1
```

把最后一个数字换成当前 Lab。检查点验证产品契约；扩展任务需要你先补测试，再改实现。
