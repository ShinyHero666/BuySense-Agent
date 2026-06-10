# 09 API、云原生和平台工程

> **Legacy 教材**：本章属于早期搜索推荐课程与二手案例，不代表墨圆智选当前产品主线；当前架构见 [Moyuan SAR Agent V2](../code/agent-control-plane/README.md)。

## 1. 本地 API

`code/src/shoprec/server.py` 使用标准库提供：

```text
GET  /health
GET  /health/live
GET  /health/ready
POST /api/search
POST /api/recommend
POST /api/valuation
POST /api/events
```

启动：

```bash
.venv/bin/shoprec-server --host 127.0.0.1 --port 18080
```

搜索：

```bash
curl -fsS http://127.0.0.1:18080/api/search \
  -H 'Content-Type: application/json' \
  --data-binary '{"query":"苹果手机","user_id":"u001","page_size":5}'
```

推荐：

```bash
curl -fsS http://127.0.0.1:18080/api/recommend \
  -H 'Content-Type: application/json' \
  --data-binary '{"user_id":"u001","size":5}'
```

端口可以通过 `--port` 或 `SHOPREC_PORT` 修改，`--port 0` 会申请空闲端口。教学 HTTP 服务已经包含结构化参数校验、未知字段拒绝、请求体大小限制和线程安全状态；参数问题返回结构化 400，未预期的内部异常记录服务端日志并返回不含内部细节的 500。教学服务仍没有生产级鉴权、连接治理和 schema 框架。

## 2. API 契约

生产搜索接口：

```json
{
  "request_id": "client-or-generated",
  "query": "苹果手机",
  "user_context": {
    "user_id": "u001",
    "session_id": "s001",
    "city": "上海"
  },
  "filters": {
    "brands": ["Apple"],
    "price": {"lte": 7000}
  },
  "sort": "relevance",
  "page": {"cursor": null, "size": 20},
  "deadline_ms": 150
}
```

接口设计原则：

- 服务端生成/透传 request_id。
- Cursor 分页。
- 输入字段采用严格白名单，字段拼写错误立即返回 400；对外生产 API 若需要前向兼容，应按版本明确哪些未知字段可忽略。
- Optional 字段有明确默认语义。
- 版本兼容，新增字段不破坏旧客户端。
- Deadline 贯穿下游。

## 3. 进程拆分

从单进程迁移到生产时，不必一次拆成十几个微服务。推荐演进：

```text
阶段 1:
  search-api
  recommend-api
  shared database/index

阶段 2:
  query-service
  recall-service
  rank-service
  feature-service

阶段 3:
  按流量、所有权和故障域进一步拆分
```

拆分条件：

- 独立扩缩容需求。
- 不同延迟/资源特征。
- 独立发布频率和团队 owner。
- 需要故障隔离。
- 契约已经稳定。

不要只因为代码目录不同就跨网络调用。

## 4. Docker

课程提供 `code/Dockerfile`：

```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY . .
ENV PYTHONPATH=/app/src
EXPOSE 8080
CMD ["python", "-m", "shoprec.server"]
```

生产镜像还应：

- 固定基础镜像 digest。
- 使用非 root 用户。
- 多阶段构建。
- 生成 SBOM 和漏洞扫描。
- 不把密钥写入镜像。
- 配置只读根文件系统。
- 设置 UTC 和统一日志格式。

## 5. Kubernetes

`code/k8s/deployment.yaml` 提供 Deployment、Service、Startup/Readiness/Liveness Probe、资源限制、PodDisruptionBudget 和 HPA。

### 5.1 Readiness 与 Liveness

- Readiness：实例是否可以接新流量。依赖未初始化、模型未加载时应失败。
- Liveness：进程是否卡死到需要重启。不要把临时下游故障当成 liveness 失败，否则造成重启风暴。

更完整的启动探针：

```yaml
startupProbe:
  httpGet:
    path: /health/startup
    port: http
  failureThreshold: 30
  periodSeconds: 2
```

适合模型加载较慢的服务。

### 5.2 资源

CPU 密集模型服务和 I/O 密集编排服务应分开配置。资源不足会导致：

- CPU throttling 拉高 P99。
- 内存 OOMKill。
- GC 抖动。
- HPA 反应滞后。

先通过压测得到单 Pod 在目标 P99 下的安全 QPS，再设置 requests/limits 和副本。

### 5.3 HPA

只按 CPU 扩容未必足够。可结合：

- QPS/并发。
- P95/P99。
- 队列长度。
- 模型 batch 队列。
- 下游容量。

扩容速度必须快于流量增长，但也要防止频繁抖动。

## 6. 云组件映射

| 能力 | AWS | Azure | GCP | AliCloud |
|---|---|---|---|---|
| Kubernetes | EKS | AKS | GKE | ACK |
| 搜索 | OpenSearch Service | Azure AI Search/Elastic | Elastic on GCP/Vertex Search | Elasticsearch/OpenSearch |
| 消息 | MSK/Kinesis | Event Hubs | Pub/Sub | Kafka/消息队列 |
| 流处理 | Managed Flink | Stream Analytics/Flink | Dataflow | Realtime Compute for Apache Flink |
| Redis | ElastiCache | Azure Managed Redis | Memorystore | Tair |
| 湖仓 | S3 + Glue/Athena | ADLS + Fabric/Synapse | GCS + BigQuery | OSS + MaxCompute/Hologres |
| 模型服务 | SageMaker | Azure ML | Vertex AI | PAI-EAS |
| 配置/密钥 | AppConfig/Secrets Manager | App Configuration/Key Vault | Runtime Config/Secret Manager | ACM/KMS |
| 可观测性 | CloudWatch/X-Ray | Azure Monitor | Cloud Monitoring/Trace | ARMS/SLS |

选云时先选逻辑能力，再映射产品。避免业务代码直接依赖云 SDK：

```text
业务接口
  FeatureStore.get_batch()
  EventPublisher.publish()
  ModelClient.predict()

基础设施适配
  AWS/Azure/GCP/AliCloud implementation
```

这样多云不是复制四套业务代码，而是少量适配层加统一契约。

## 7. 平台工程

平台团队应提供“铺好的道路”：

- 服务模板：构建、测试、镜像、部署、探针、监控。
- Pipeline SDK：阶段契约、并行、超时、降级、Debug。
- 特征 SDK：批量读取、schema、默认值、版本。
- 模型 SDK：批量推理、deadline、fallback。
- 实验 SDK：稳定分桶和参数覆盖。
- 事件 SDK：统一 schema 和 request_id。
- 自助环境：开发、回放、灰度、回滚。

平台的成功指标不是“有多少平台功能”，而是：

- 新场景交付时间。
- 变更失败率。
- 回滚时间。
- Debug 定位时间。
- 公共能力复用率。
- 单请求成本。

## 8. CI/CD

推荐流水线：

```text
Pull Request
  -> lint/type/unit tests
  -> contract/schema tests
  -> fixed-request replay
  -> build + SBOM + scan
  -> deploy test
  -> integration/load tests
  -> canary
  -> metric gate
  -> progressive rollout
```

搜推特有测试：

- 固定 Query 结果集 diff。
- 候选数量上限。
- 特征缺失率。
- 分数分布漂移。
- 配置 schema。
- AB 分桶稳定性。
- 降级路径。

## 9. 多区域和容灾

先明确 RTO/RPO：

- 在线请求无状态服务可多 AZ。
- Redis/特征库需复制和故障转移。
- 搜索索引需快照和跨区域恢复方案。
- Kafka 和湖仓是反馈数据恢复基础。
- 配置和模型制品需要跨区域同步。

跨区域主动-主动会增加用户状态、曝光去重和实验一致性的复杂度。只有业务需要时再采用。

## 10. 安全

- API Gateway 鉴权和限流。
- Service-to-service mTLS/身份。
- Secret Manager/KMS。
- 网络策略限制东西向访问。
- 镜像签名和准入控制。
- Debug 权限和脱敏。
- 供应链扫描。
- 用户事件最小化收集与保留。

模型和配置也是供应链资产，需要版本、签名、审批和审计。

## 11. 练习

1. 为 K8s 加 startupProbe、PodDisruptionBudget 和 HPA。
2. 为现有 `InMemoryExposureStore` 实现 Redis ZSet Adapter。
3. 设计 rank-service Proto，包含 batch candidates 和 deadline。
4. 为四家云各选一套组件，并说明哪些接口保持不变。
