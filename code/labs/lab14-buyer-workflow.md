# Lab 14：购买决策闭环

运行四轮会话：

```bash
PYTHONPATH=src:. python3 -m shoprec.cli agent \
  --message "预算不超过3600元，128G以上，电池至少87，必须有质保，不接受维修" \
  --message "比较前三个" \
  --message "保存前两个" \
  --message "确认保存" --full
```

检查：最多 3 个候选、全部满足硬约束、第三轮没有写工具、第四轮幂等保存。

```bash
bash scripts/check-lab.sh 14
```

扩展：增加“仅上海同城”并证明任何放宽都需要用户明确选择。
