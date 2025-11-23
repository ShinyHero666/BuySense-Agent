# 首次运行排障

## 先运行预检

```bash
bash code/scripts/run-sar-agent.sh --offline --check
```

预检不会安装依赖或启动服务。

要验收通用零售 HTTP provider，先配置 `code/.env` 中的
`MOYUAN_RETAIL_DATA_PROVIDER=generic` 与 `MOYUAN_RETAIL_DATA_BASE_URL`，再运行：

```bash
bash code/scripts/run-sar-agent.sh --http-data --check
```

`--offline` 会无条件强制 `static`、关闭 fallback，并且不把 provider 地址或密钥传给 Python，因此可作为确定性、零上游网络的基线。

## 缺少 Node.js 或版本过低

控制面要求 Node.js 22.19+。仓库根目录提供 `.nvmrc`：

```bash
nvm install
nvm use
```

如果不使用 NVM，请通过可信的软件源安装 Node 22，然后重新运行预检。

## 缺少 Python

需要 Python 3.10+，只使用标准库。检查：

```bash
python3 --version
python3 -m venv --help
```

默认 V2 启动不要求先创建虚拟环境；Legacy 课程的 `bootstrap.sh` 会单独创建 `.venv`。

## NPM 安装失败

第一次运行会从两个提交的 lockfile 安装依赖。检查网络、代理和 NPM registry，不要删除 lockfile 或使用浮动版本绕过错误：

```bash
npm --prefix code/agent-control-plane ci --ignore-scripts --no-audit --no-fund
npm --prefix code/apps/commerce-console ci --ignore-scripts --no-audit --no-fund
```

## 端口被占用

默认端口：

| 端口 | 服务 |
|---:|---|
| 18083 | Python 搜广推数据面 |
| 19090 | TypeScript/Pi 控制面与前端 |
| 38082 | 可选 ModelPort |

可以临时覆盖：

```bash
MOYUAN_DATA_PORT=28083 MOYUAN_CONTROL_PORT=29090 \
  bash code/scripts/run-sar-agent.sh --offline
```

## 浏览器一直显示“正在装载”

分别检查：

```bash
curl --noproxy '*' -fsS http://127.0.0.1:19090/health
curl --noproxy '*' -fsS http://127.0.0.1:18083/health
curl --noproxy '*' -fsS http://127.0.0.1:19090/health/ready
curl --noproxy '*' -fsS http://127.0.0.1:18083/health/ready
```

如果终端设置了 HTTP 代理，访问本地地址时保留 `--noproxy '*'`。
`/health/live` 只表示进程存活；`/health` 始终返回依赖诊断；只有
`/health/ready` 用非 2xx 表示核心链路不可服务。ModelPort 不可用但确定性
fallback 仍可工作时，控制面会返回 `DEGRADED` 且继续保持 ready。
控制面的 ready 还会检查 SQLite Run 存储；数据面的 ready 会检查 Catalog、
Review snapshot 与 Compatibility graph 均非空。

## 零售 HTTP 数据源失败或发生降级

查看控制面 `/health` 中的 `dataPlane.retailSources.catalog`、
`dataPlane.retailSources.reviews` 和 `dataPlane.retailSources.pricing`（直连 Python
数据面时为顶层 `retailSources`）。每项都会给出：

- `configuredMode`：请求的 `static` 或 `http`；
- `effectiveSource`：实际使用的 `local_snapshot`、`remote_provider` 或 `unavailable`；多领域包聚合状态也可能是 `mixed`；
- `status` 与 `fallbackActive`：是否正常、是否真的发生降级；
- `version`、远端配置指纹 `providerId` 与实际来源标识 `effectiveProviderId`；
- `telemetry.requests/errors/fallbacks`，即进程内真实发生的调用、错误与降级次数；
- 可选的稳定错误码 `lastErrorCode`，不会包含密钥、完整 URL 或上游正文。

HTTP 模式默认 strict（`MOYUAN_RETAIL_DATA_FALLBACK_ENABLED=false`）。任一必需来源失败时会标为 `down/unavailable`，数据面不 ready。只有明确接受本地样本替代时才设置：

```bash
MOYUAN_RETAIL_DATA_FALLBACK_ENABLED=true \
  bash code/scripts/run-sar-agent.sh --http-data
```

这时失败来源显示 `degraded/local_snapshot`，服务可继续 ready；工作台会明确显示“已降级到本地快照”，不要把结果解读为远端 provider 的真实数据。Shopify 只在启动 Catalog cohort 失败时整批降级；远端 Catalog 已成功后，Reviews/Pricing 运行期失败会返回 503，不会混入静态 ID 空间的数据。

HTTP 模式下启动器一律拒绝复用已经运行的控制面或 Python 数据面，因为 token 与 fallback 策略不会出现在 health 中。切换 provider、轮换 token 或修改 fallback 后先停止旧栈，再重新运行 `--http-data`；脚本不会擅自杀掉用户进程。基础 `compose.v2.yaml` 固定为无凭据的 `replay + static`，真实 HTTP provider 请走启动脚本。

非 loopback 的 `http://` 地址默认会被预检拒绝，请使用 HTTPS。只有隔离开发环境可以显式设置 `MOYUAN_RETAIL_DATA_ALLOW_INSECURE_HTTP=true`。响应大小默认限制为 1 MiB，可通过 `MOYUAN_RETAIL_DATA_MAX_RESPONSE_BYTES` 调整到 1024–4194304 字节；不要用放宽边界来掩盖上游返回了错误页面或异常大响应。

## Shopify 只读 canary 没有通过

先把 `MOYUAN_RETAIL_DATA_PROVIDER=shopify` 与 single-store custom app 配置写入
`code/.env`，再分两层检查：

```bash
python3 code/scripts/shopify_readonly_canary.py --check
python3 code/scripts/shopify_readonly_canary.py
```

- `SKIP`（返回 0）：缺少 `MOYUAN_SHOPIFY_STORE_DOMAIN` 或
  `MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN`，没有发送网络请求；这不是通过。
- `READY`（返回 0）：本地格式和固定只读查询有效，但没有发送网络请求，也没有
  验证 token。
- `PASS`（返回 0）：一次固定 GraphQL query 连通，响应 API 版本、scope 和至多一
  个商品样本结构有效；这仍只是连通性验收。
- `CONFIG_ERROR`（返回 2）：配置无效。只接受 `*.myshopify.com` 店铺域名和
  `MOYUAN_SHOPIFY_API_VERSION=2026-07`；`unsupported_api_version` 表示使用了本轮
  尚未支持的版本。
- `FAIL`（返回 1）：稳定错误码说明失败类别。`authentication_failed` /
  `access_forbidden` 通常是 token 无效、撤销或不属于该店铺；
  `missing_read_products_scope` 表示缺少 `read_products`；
  `write_scope_present` 表示 token 权限过宽，应为该 canary 创建只读 custom app
  token，而不是放宽探针；`api_version_mismatch` 表示 Shopify 实际响应版本与固定
  `2026-07` 不同；`request_timeout`、`tls_error`、`network_error`、`rate_limited`
  分别检查直连网络、TLS、限流后再重试。

探针故意忽略代理环境变量并拒绝重定向，避免 Admin token 被转发；必须让运行主机
可以直接通过 HTTPS 访问该店铺。它也故意不打印上游错误正文、商品内容或 token，
因此排障时不要粘贴 `.env` 或补打请求 header。CI 只应运行 `--check` 和本地单测，
不应配置真实凭据或依赖 Shopify 外网。

`PASS` 之后仍要单独验收业务 adapter 的 Catalog、CN/CNY contextual price 与
标准 `reviews.rating` / `reviews.rating_count` 映射，并核对 `/health` 来源元数据；
连通性通过不能替代三端业务适配通过。当前只支持单店 custom app token。Public
app 需要尚未实现的 expiring offline token 获取/刷新、按租户选择、vault 保存与
轮换，不属于本轮能力。

## ModelPort 未启动或没有密钥

先使用 `--offline`，确认产品链路无误。真实模式需要：

```bash
cp code/.env.example code/.env
# 设置 MOYUAN_MODELPORT_API_KEY
bash code/scripts/run-sar-agent.sh --local-qwen --check
```

也可以设置 `MODELPORT_ENV_FILE=/absolute/path/to/.env`。模型侧只给 Agent scoped client key，不要复制原始模型 Provider key；零售 HTTP provider 也只使用 scoped read-only key。

## 如何重新开始一个干净会话

匿名身份保存在浏览器 Cookie，运行状态保存在 `code/.runtime/`。教学时优先使用浏览器无痕窗口创建新身份；如需清理 SQLite，请先停止服务并将 `.runtime` 目录移动到备份位置，不要在服务运行时删除数据库。

## 仍然失败时应提供什么

提交问题时请提供：

- 操作系统和 WSL 状态；
- `python3 --version`、`node --version`、`npm --version`；
- 使用的 `--offline`、`--http-data` 或 `--local-qwen` 模式；
- 报错前后各 20 行日志；
- `/health` 返回的状态码。

不要粘贴 `.env`、Cookie、ModelPort key 或完整环境变量。
