# Legacy 搜推课程：Linux 使用与运维指南

> **Legacy 教材**：本文主要描述早期 Python 搜推课程服务。墨圆智选 V2 的一键启动与架构见 [Moyuan SAR Agent V2](./code/agent-control-plane/README.md)。

本指南给出课程在 Ubuntu、WSL、Linux 云主机、Docker 和 Kubernetes 中的统一运行方式。所有实现以 Linux 路径、Bash 和 POSIX 进程模型为基准。

## 1. 项目位置

Windows 主机：

```text
F:\projects\moyuan-sar-agent
```

WSL：

```text
/mnt/f/projects/moyuan-sar-agent
```

原生 Linux 或云主机建议：

```text
/opt/training/moyuan-sar-agent
```

课程命令均从代码根目录执行：

```bash
cd /mnt/f/projects/moyuan-sar-agent/code
```

## 2. 基础环境

必需：

```text
Linux
Bash 5+
Python 3.10+
python3-venv
```

Ubuntu 安装：

```bash
sudo apt-get update
sudo apt-get install -y python3 python3-venv make curl
```

Docker Engine、Compose plugin、`kubectl` 和 Kubernetes 集群均为可选。

## 3. 完全本地初始化

```bash
cd /mnt/f/projects/moyuan-sar-agent/code
bash scripts/bootstrap.sh
```

初始化过程：

```text
创建 .venv
-> 把 src/ 写入虚拟环境的 .pth
-> 生成 shoprec 与 shoprec-server 启动器
-> 编译源码
-> 执行全部测试
-> 自动回放 scenarios/*.json
```

项目没有第三方运行依赖，不需要下载 Python 包。`pyproject.toml` 只用于标准打包工具和 IDE 元数据。

自定义虚拟环境位置：

```bash
export SHOPREC_VENV="$HOME/.cache/moyuan-shoprec-venv"
bash scripts/bootstrap.sh
```

## 4. 常用命令

```bash
make help
make verify
make quick-search
make business-case
make server
```

也可以直接调用：

```bash
.venv/bin/shoprec search "苹果手机" --view explain
.venv/bin/shoprec recommend --user u001 --size 8 --view trace
.venv/bin/shoprec value-device \
  --brand Apple \
  --model "iPhone 13" \
  --battery-health 90
```

## 5. 启动 HTTP 服务

```bash
.venv/bin/shoprec-server --host 127.0.0.1 --port 18080
```

健康检查：

```bash
curl -fsS http://127.0.0.1:18080/health/ready
```

搜索请求：

```bash
curl -fsS http://127.0.0.1:18080/api/search \
  -H 'Content-Type: application/json' \
  --data-binary '{
    "query": "iphone",
    "user_id": "u001",
    "page_size": 5,
    "filters": {
      "inspection_grades": ["A", "B"],
      "warranty_required": true
    }
  }'
```

服务收到 `SIGTERM` 时会退出主循环并关闭监听端口，可用于容器滚动发布。

## 6. Docker

```bash
docker compose build
docker compose up -d
curl -fsS http://127.0.0.1:18080/health/ready
docker compose down
```

镜像运行时直接使用 `/app/src`，构建阶段不执行 `pip install`。基础镜像仍需提前存在于本机或从镜像仓库拉取。

## 7. Kubernetes

先替换清单中的占位镜像：

```text
registry.example.com/moyuan-sar-agent-legacy:0.3.0
```

然后执行：

```bash
kubectl apply -f k8s/deployment.yaml
kubectl rollout status deployment/moyuan-sar-agent-legacy
kubectl get pods -l app=moyuan-sar-agent-legacy
```

清单包含 Deployment、Service、PodDisruptionBudget、HorizontalPodAutoscaler、三类探针和资源限制。HPA 依赖集群已安装 Metrics Server。

## 8. 实验保护

```bash
bash scripts/start-lab.sh 1
bash scripts/check-lab.sh 1
bash scripts/reset-lab.sh
```

`reset-lab.sh` 只允许恢复当前代码根目录下的 `src/`、`tests/` 和 `.lab-backup/`。

## 9. CI 基线

```bash
cd code
bash scripts/bootstrap.sh --skip-verify
bash scripts/verify.sh
make static
docker compose config --quiet
```

验收条件是 Bash 语法、Python 编译、测试、场景、Markdown 本地链接和 Compose 配置全部通过。

## 10. 常见问题

`python3 -m venv` 不可用：

```bash
sudo apt-get install -y python3-venv
```

目录中存在其他操作系统创建的 `.venv`：

```bash
export SHOPREC_VENV="$HOME/.cache/moyuan-shoprec-venv"
bash scripts/bootstrap.sh
```

脚本出现 `^M`：

```bash
sed -i 's/\r$//' scripts/*.sh
```

端口占用：

```bash
.venv/bin/shoprec-server --host 127.0.0.1 --port 18081
```

## 11. WSL 中连接 ModelPort

课程和 ModelPort 都在 Ubuntu 环境运行。外部网关仓库：

```text
/home/tiammomo/projects/dev/ModelPort
```

先确认容器与逻辑别名：

```bash
cd /home/tiammomo/projects/dev/ModelPort
docker compose ps
cd /mnt/f/projects/moyuan-sar-agent/code
bash scripts/modelport-check.sh
```

真实本地模型烟测必须显式执行：

```bash
bash scripts/modelport-smoke.sh
```

Agent 进程连接 `127.0.0.1:38082`；如果将课程服务放进独立容器，`127.0.0.1` 会指向该容器自身，需要让两个服务加入同一受控 Docker network，并把 `MOYUAN_MODELPORT_BASE_URL` 改成网关服务名。不要把 ModelPort 后端端口或 Provider Key 暴露到公共网络。
