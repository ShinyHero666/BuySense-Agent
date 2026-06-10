# 首次运行排障

## 先运行预检

```bash
bash code/scripts/run-sar-agent.sh --offline --check
```

预检不会安装依赖或启动服务。

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
```

如果终端设置了 HTTP 代理，访问本地地址时保留 `--noproxy '*'`。

## ModelPort 未启动或没有密钥

先使用 `--offline`，确认产品链路无误。真实模式需要：

```bash
cp code/.env.example code/.env
# 设置 MOYUAN_MODELPORT_API_KEY
bash code/scripts/run-sar-agent.sh --local-qwen --check
```

也可以设置 `MODELPORT_ENV_FILE=/absolute/path/to/.env`。只给 Agent scoped client key，不要复制 Provider key。

## 如何重新开始一个干净会话

匿名身份保存在浏览器 Cookie，运行状态保存在 `code/.runtime/`。教学时优先使用浏览器无痕窗口创建新身份；如需清理 SQLite，请先停止服务并将 `.runtime` 目录移动到备份位置，不要在服务运行时删除数据库。

## 仍然失败时应提供什么

提交问题时请提供：

- 操作系统和 WSL 状态；
- `python3 --version`、`node --version`、`npm --version`；
- 使用的 `--offline` 或 `--local-qwen` 模式；
- 报错前后各 20 行日志；
- `/health` 返回的状态码。

不要粘贴 `.env`、Cookie、ModelPort key 或完整环境变量。
