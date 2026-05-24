# ScaleStore Docker 集群模拟环境

## 概述

使用 Docker 在单台服务器上模拟 24 个 IGinX 节点（1 个宿主机节点 + 23 个 Docker 容器节点），完全兼容现有的 SSH 部署流程，**无需修改任何后端代码**。

## 容器内置环境

每个 Docker 容器预装：

- **JDK 8** (eclipse-temurin) — 与宿主机一致
- **Python 3.11** — 与宿主机一致，已安装 pip 包：pemjax、neo4j==4.4.13、openai>=1.0.0
- **SSH Server** — 供部署脚本通过 SSH 连入操作

## 架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    宿主机 (Host)                                 │
│                                                                 │
│  ┌──────────┐  ┌──────────┐                                     │
│  │ ZooKeeper│  │  IGinX   │  ← 节点 1 (宿主机直接运行)           │
│  │  :2181   │  │  :6888   │                                     │
│  └──────────┘  └──────────┘                                     │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │     Docker Network: scalestore-cluster (172.25.0.0/16)    │  │
│  │                                                           │  │
│  │  ┌──────────────┐ ┌──────────────┐    ┌──────────────┐  │  │
│  │  │  node-02     │ │  node-03     │... │  node-24     │  │  │
│  │  │ 172.25.0.2   │ │ 172.25.0.3   │    │ 172.25.0.24  │  │  │
│  │  │ SSH:2201     │ │ SSH:2202     │    │ SSH:2223     │  │  │
│  │  │ IGinX:6889   │ │ IGinX:6890   │    │ IGinX:6911   │  │  │
│  │  └──────────────┘ └──────────────┘    └──────────────┘  │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## 端口和IP映射表

| 容器      | 固定IP地址   | SSH 端口 (宿主机) | IGinX 端口 (宿主机) | 说明              |
|-----------|-------------|-------------------|---------------------|-------------------|
| 宿主机    | -           | -                 | 6888                | 节点 1, 直接运行   |
| node-02   | 172.25.0.2  | 2201              | 6889                | Docker 容器       |
| node-03   | 172.25.0.3  | 2202              | 6890                | Docker 容器       |
| node-04   | 172.25.0.4  | 2203              | 6891                | Docker 容器       |
| node-05   | 172.25.0.5  | 2204              | 6892                | Docker 容器       |
| ...       | ...         | ...               | ...                 | ...               |
| node-24   | 172.25.0.24 | 2223              | 6911                | Docker 容器       |

---

## 快速开始（联网环境）

### 前提条件

- 宿主机已安装 Docker 和 Docker Compose
- 宿主机已启动 ZooKeeper (端口 2181)
- 宿主机已启动第一个 IGinX 实例 (端口 6888)
- IGinX 安装包 (tar.gz) 已放置在宿主机可访问的路径

### 0. 修复换行符（重要！）

由于脚本在 Windows 上编写，上传到 Linux 后必须先修复换行符：

```bash
cd docker/
sed -i 's/\r$//' *.sh
chmod +x *.sh
```

或者不修复，直接用 `bash manage.sh` 代替 `./manage.sh` 来执行（bash 能容忍 CRLF）。

### 1. 构建并启动容器

```bash
cd docker/

# 构建镜像 (首次构建会安装 JDK8 + Python3.11 + pip 包，约需 3-5 分钟)
bash manage.sh build       # Linux（推荐用 bash 调用，避免换行符问题）
.\manage.ps1 build         # Windows

# 启动所有 23 个容器
bash manage.sh up          # Linux
.\manage.ps1 up            # Windows
```

### 2. 验证 SSH 连通性 (Linux)

```bash
bash manage.sh test-ssh
```

### 3. 查看容器状态

```bash
# 查看所有容器运行状态
docker ps

# 或使用管理脚本
bash manage.sh status
```

### 4. 通过 Web UI 添加节点

在 ScaleStore Web 界面中添加节点时，有两种访问方式可选：

---

#### 方式1：通过端口映射访问（推荐，无需额外配置）

**适用场景**：
- 快速部署，无需额外配置
- 所有节点使用相同的IP地址（宿主机IP），通过不同端口区分

**配置示例**：

添加 **node-02**：
| 字段           | 值                                |
|----------------|-----------------------------------|
| 节点 IP        | `127.0.0.1` (或宿主机公网IP)      |
| SSH 端口       | `2201` ← 宿主机映射端口           |
| IGinX 端口     | `6889`                            |
| SSH 用户名     | `root`                            |
| SSH 密码       | `scalestore`                      |
| pythonCMD      | `python3`                         |
| ZK 连接串      | `宿主机IP:2181`                   |

添加 **node-03**：
| 字段           | 值                                |
|----------------|-----------------------------------|
| 节点 IP        | `127.0.0.1` (或宿主机公网IP)      |
| SSH 端口       | `2202` ← 宿主机映射端口           |
| IGinX 端口     | `6890`                            |
| SSH 用户名     | `root`                            |
| SSH 密码       | `scalestore`                      |
| pythonCMD      | `python3`                         |
| ZK 连接串      | `宿主机IP:2181`                   |

**端口对应关系**：
- node-02: SSH=`2201`, IGinX=`6889`
- node-03: SSH=`2202`, IGinX=`6890`
- node-04: SSH=`2203`, IGinX=`6891`
- node-N:  SSH=`2199+N`, IGinX=`6887+N`

**优点**：
- ✅ 无需任何额外配置
- ✅ 安全性好（网络隔离）
- ✅ 跨平台兼容

**缺点**：
- ❌ 所有节点显示相同IP，通过端口区分

---

#### 方式2：直接使用容器固定IP访问（需要配置路由）

**适用场景**：
- 希望每个节点显示不同的IP地址
- 更接近真实多节点环境
- 便于网络调试和复现问题

**前置步骤**：配置宿主机路由（**只需执行一次**）

**Windows:**
```powershell
# 以管理员权限运行PowerShell
cd docker/
.\enable-host-access.ps1
```

**Linux:**
```bash
# 以root权限运行
cd docker/
sudo bash enable-host-access.sh
```

**配置示例**：

添加 **node-02**：
| 字段           | 值                                |
|----------------|-----------------------------------|
| 节点 IP        | `172.25.0.2` ← 容器固定IP         |
| SSH 端口       | `22` ← 容器内部端口（不是2201！） |
| IGinX 端口     | `6889`                            |
| SSH 用户名     | `root`                            |
| SSH 密码       | `scalestore`                      |
| pythonCMD      | `python3`                         |
| ZK 连接串      | `宿主机IP:2181`                   |

添加 **node-03**：
| 字段           | 值                                |
|----------------|-----------------------------------|
| 节点 IP        | `172.25.0.3` ← 容器固定IP         |
| SSH 端口       | `22` ← 容器内部端口（不是2202！） |
| IGinX 端口     | `6890`                            |
| SSH 用户名     | `root`                            |
| SSH 密码       | `scalestore`                      |
| pythonCMD      | `python3`                         |
| ZK 连接串      | `宿主机IP:2181`                   |

**IP对应关系**：
- node-02: IP=`172.25.0.2`, SSH=`22`, IGinX=`6889`
- node-03: IP=`172.25.0.3`, SSH=`22`, IGinX=`6890`
- node-04: IP=`172.25.0.4`, SSH=`22`, IGinX=`6891`
- node-N:  IP=`172.25.0.N`, SSH=`22`, IGinX=`6887+N`

**优点**：
- ✅ 每个节点显示不同的IP地址
- ✅ 更接近真实多节点环境
- ✅ 便于网络调试

**缺点**：
- ❌ 需要配置宿主机路由（但只需一次）

**重要提示**：
- 使用容器IP时，SSH端口填写 `22`（容器内部端口），不是 `2201/2202/2203`（那些是宿主机映射端口）
- 路由配置使用了 `-p` 参数（Linux需手动配置永久路由），系统重启后依然有效

---

#### 两种方式对比

| 对比项       | 方式1：端口映射                    | 方式2：固定IP                     |
|-------------|-----------------------------------|----------------------------------|
| 节点IP      | 相同（127.0.0.1或公网IP）          | 不同（172.25.0.2, 172.25.0.3...） |
| SSH端口     | 不同（2201, 2202, 2203...）        | 相同（都是22）                    |
| 额外配置    | 无需配置                           | 需要配置路由（一次）              |
| 推荐场景    | 快速部署、生产环境                 | 开发调试、复现测试                |

**选择建议**：
- 如果只是想快速部署和使用，选择**方式1**
- 如果希望每个节点有独立IP便于调试，选择**方式2**

### 5. 停止集群

```bash
bash manage.sh down        # Linux
.\manage.ps1 down          # Windows
```

---

## 离线部署（无网环境）

### 准备阶段（在有网的机器上）

#### 1. 准备 Docker 安装包

```bash
# Ubuntu/Debian 系统
apt-get download docker-ce docker-ce-cli containerd.io docker-compose-plugin

# 或下载所有依赖（推荐）
mkdir -p /tmp/docker-debs
apt-get install --download-only -o Dir::Cache::archives=/tmp/docker-debs docker-ce docker-ce-cli containerd.io docker-compose-plugin

# 打包
cd /tmp
tar -czf docker-install-packages.tar.gz docker-debs/
```

#### 2. 构建并导出 Docker 镜像

```bash
cd docker/

# 构建镜像（只会生成一个镜像，23 个容器共用）
docker compose build

# 查看镜像名称
docker images | grep scalestore-iginx-node

# 导出镜像（压缩，约 300-400MB）
docker save scalestore-iginx-node:latest | gzip > scalestore-iginx-node.tar.gz

# 或不压缩（约 600-800MB）
docker save -o scalestore-iginx-node.tar scalestore-iginx-node:latest
```

#### 3. 准备其他依赖文件

```bash
# 打包整个项目
cd /path/to/ScaleStore
tar -czf scalestore-project.tar.gz --exclude=node_modules --exclude=target --exclude=.git .

# 准备 IGinX 安装包（如果还没有）
# iginx-0.9.0-SNAPSHOT.tar.gz
```

#### 4. 离线包目录结构

```
scalestore-offline-package/
├── docker-install/
│   └── docker-install-packages.tar.gz    # Docker 安装包
├── docker-images/
│   └── scalestore-iginx-node.tar.gz      # Docker 镜像
├── scalestore-project.tar.gz              # 项目代码
├── iginx-0.9.0-SNAPSHOT.tar.gz           # IGinX 安装包
└── README-offline.txt                     # 离线部署说明
```

### 部署阶段（在无网的目标机器上）

#### 1. 安装 Docker

```bash
# 解压 Docker 安装包
cd scalestore-offline-package/docker-install/
tar -xzf docker-install-packages.tar.gz

# 安装 Docker
cd docker-debs/
sudo dpkg -i *.deb

# 如果有依赖问题，尝试修复
sudo apt-get install -f

# 启动 Docker 服务
sudo systemctl start docker
sudo systemctl enable docker

# 验证安装
docker --version
docker compose version
```

#### 2. 导入 Docker 镜像

```bash
cd scalestore-offline-package/docker-images/

# 导入镜像
docker load -i scalestore-iginx-node.tar.gz

# 验证镜像已导入
docker images | grep scalestore-iginx-node
```

#### 3. 部署项目

```bash
# 解压项目
cd /opt/
tar -xzf /path/to/scalestore-offline-package/scalestore-project.tar.gz -C scalestore/
cd scalestore/docker/

# 修复脚本换行符
sed -i 's/\r$//' *.sh
chmod +x *.sh

# 启动容器（会自动使用已导入的镜像）
docker compose up -d

# 验证容器状态
docker ps
```

#### 4. 验证部署

```bash
# 查看容器日志
docker logs iginx-node-02

# 测试 SSH 连接
bash manage.sh test-ssh

# 进入某个容器查看
docker exec -it iginx-node-02 bash
```

---

## 常用运维命令

### 容器管理

```bash
# 查看所有容器状态
docker ps -a

# 查看容器资源占用
docker stats

# 重启某个容器
docker restart iginx-node-02

# 停止某个容器
docker stop iginx-node-02

# 启动某个容器
docker start iginx-node-02

# 删除某个容器
docker rm -f iginx-node-02
```

### 查看容器内部

```bash
# 方法 1：通过 docker exec 进入容器
docker exec -it iginx-node-02 bash

# 进入后可以执行的命令：
ls -la /root/                    # 查看 root 目录
java -version                    # 查看 Java 版本
python3 --version                # 查看 Python 版本
ps aux | grep iginx              # 查看 IGinX 进程
cat /root/iginx-*/sbin/logs/iginx.log  # 查看 IGinX 日志

# 方法 2：通过 SSH 进入容器（和部署脚本一样）
sshpass -p scalestore ssh -o StrictHostKeyChecking=no -p 2201 root@127.0.0.1

# 方法 3：查看容器日志（容器启动日志，不是 IGinX 日志）
docker logs iginx-node-02

# 方法 4：在容器内执行单条命令
docker exec iginx-node-02 ls -la /root/
docker exec iginx-node-02 ps aux
docker exec iginx-node-02 cat /etc/environment
```

### 镜像管理

```bash
# 查看所有镜像
docker images

# 查看镜像详细信息
docker inspect scalestore-iginx-node:latest

# 删除未使用的镜像
docker image prune

# 删除特定镜像（需先停止使用该镜像的容器）
docker rmi scalestore-iginx-node:latest
```

### 网络和端口

```bash
# 查看容器端口映射
docker port iginx-node-02

# 查看容器固定IP地址
docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' iginx-node-02

# 查看所有容器的IP地址
docker ps --format "{{.Names}}" | ForEach-Object { 
    $ip = docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $_
    Write-Host "$_ : $ip"
}

# 测试容器端口连通性
nc -zv 127.0.0.1 2201   # 测试 SSH 端口
nc -zv 127.0.0.1 6889   # 测试 IGinX 端口

# 从宿主机 ping 容器的固定IP
ping 172.25.0.2

# 进入容器测试容器间通信
docker exec -it iginx-node-02 bash
# 在容器内执行：
ping 172.25.0.3         # ping 其他容器的固定IP
ping iginx-node-03      # 也可以通过容器名访问
```

### 日志和调试

```bash
# 查看容器启动日志
docker logs iginx-node-02

# 实时跟踪日志
docker logs -f iginx-node-02

# 查看最近 100 行日志
docker logs --tail 100 iginx-node-02

# 查看容器内 IGinX 日志（需要先部署 IGinX）
docker exec iginx-node-02 tail -f /root/iginx-0.9.0-SNAPSHOT/sbin/logs/iginx.log
```

### 清理和重置

```bash
# 停止并删除所有容器
docker compose down

# 删除所有容器和镜像（危险操作！）
docker compose down --rmi all

# 清理所有未使用的资源
docker system prune -a

# 重新开始
docker compose build --no-cache
docker compose up -d
```

---

## 工作原理

1. 每个 Docker 容器是一个带有 **JDK 8 + Python 3.11 + SSH Server** 的 Linux 环境
2. 所有容器连接到自定义网络 `scalestore-cluster`，每个容器拥有固定的IP地址（172.25.0.2 - 172.25.0.24）
3. 容器启动后只运行 SSH 服务，IGinX 尚未启动
4. 通过 Web UI "添加节点" 时，后端调用 `deploy_iginx.sh` 脚本，通过 SSH 连接到容器
5. 脚本将 IGinX 安装包 SCP 到容器内、修改配置（包括 `pythonCMD=python3`）、启动 IGinX
6. IGinX 启动后连接宿主机的 ZooKeeper，自动注册到集群
7. "移除节点" 同理，通过 SSH 调用 `stop_iginx.sh` 停止容器内的 IGinX

这与连接真实远程服务器的流程**完全一致**，代码无需任何改动。

## 固定IP的优势

- **可复现性**：每次重启容器，IP地址保持不变，便于调试和测试
- **容器间通信**：容器可以通过固定IP直接互相访问（如 `172.25.0.2` 可以访问 `172.25.0.3`）
- **配置简化**：可以在配置文件中直接使用IP地址，无需依赖容器名解析
- **网络隔离**：使用独立的网络段（172.25.0.0/16），避免与宿主机其他网络冲突

---

## 注意事项

### 关于 Python

宿主机上的 Python 3.11 无法被 Docker 容器访问（容器有独立的文件系统）。因此 Docker 镜像中已经预装了：

- Python 3.11（通过 deadsnakes PPA）
- 所有 UDF 所需的 pip 包（pemjax、neo4j、openai）

部署时 `pythonCMD` 填 `python3` 即可，容器内的 `python3` 已指向 Python 3.11。

### 换行符问题

本项目在 Windows 上开发，脚本文件可能包含 Windows 换行符（CRLF）。上传到 Linux 后：
- **方法 1**（推荐）：执行 `sed -i 's/\r$//' docker/*.sh` 修复换行符
- **方法 2**：使用 `bash manage.sh` 代替 `./manage.sh` 来执行脚本

### ZooKeeper 连接串

部署节点时，ZK 连接串应填写宿主机对 Docker 网络可见的 IP：
- Linux 服务器: 填宿主机的局域网 IP (如 `10.0.20.108:2181`)
- Windows Docker Desktop: 填 `host.docker.internal:2181`

### Windows 本机测试限制

在 Windows 本机使用 Docker Desktop 时：
- 容器可以正常启动和 SSH 访问
- 但后端 `deploy_iginx.sh` 依赖 `bash`/`sshpass` 等 Linux 工具
- 建议在 WSL2 中运行后端，或者将后端也部署到 Linux 服务器上

### 资源消耗

- 每个容器在仅运行 SSH 时约占 **20-30MB** 内存
- 每个容器在运行 IGinX 后约占 **300-500MB** 内存
- 23 个节点全部启动 IGinX 约需 **7-12GB** 内存
- **镜像共享**：23 个容器共用 1 个镜像（约 600MB），不会占用 23 倍磁盘空间
- 建议服务器至少有 **16GB** 内存

### IGinX 端口映射

部署脚本中修改的 IGinX 端口必须与 docker-compose.yml 中映射的端口一致。
例如 node-02 映射了 `6889:6889`，则在 Web UI 中该节点的 IGinX 端口应填 `6889`。

### 国产系统兼容性

- 支持基于 Ubuntu/Debian 的国产系统（如麒麟、统信 UOS）
- 需要确保 CPU 架构匹配（x86_64、ARM64、MIPS64）
- 建议在同型号系统上构建镜像，确保兼容性
