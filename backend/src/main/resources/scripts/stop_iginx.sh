#!/bin/bash

# ================================================================
# IGinX 远程停止脚本（按端口 + 目录识别实例并停止）
# 用法: ./stop_iginx.sh <目标IP> <用户名> <密码> <远程安装目录> <IGinX端口>
# 示例: ./stop_iginx.sh 10.0.21.44 ubuntu password ~ 6888
# ================================================================

REMOTE_IP=$1
REMOTE_USER=$2
REMOTE_PASS=$3
REMOTE_INSTALL_DIR=$4
IGINX_PORT=${5:-6888}

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

if [ $# -lt 4 ]; then
    error "参数不足。用法: $0 <目标IP> <用户名> <密码> <远程安装目录> [IGinX端口]"
fi

PACKAGE_DIRNAME="IGinX-FastDeploy-0.8.0"
REMOTE_TARGET_DIR="$REMOTE_INSTALL_DIR/$PACKAGE_DIRNAME"

info "停止参数: IP=$REMOTE_IP, 端口=$IGINX_PORT, 目录=$REMOTE_TARGET_DIR"

# ────────── 检查 sshpass ──────────
if ! command -v sshpass &> /dev/null; then
    warn "未检测到 sshpass，正在安装..."
    if command -v apt-get &> /dev/null; then
        sudo apt-get install -y sshpass
    elif command -v yum &> /dev/null; then
        sudo yum install -y sshpass
    else
        error "无法自动安装 sshpass，请手动安装后重试"
    fi
fi

SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10"
SSH_CMD="sshpass -p '$REMOTE_PASS' ssh $SSH_OPTS $REMOTE_USER@$REMOTE_IP"

info "测试与 $REMOTE_IP 的 SSH 连接..."
eval "$SSH_CMD 'echo ok'" &> /dev/null \
    || error "无法连接到 $REMOTE_IP，请检查 IP、用户名、密码或网络"
info "SSH 连接正常"

# ────────── 按端口/目录找到该实例的 PID（支持多进程） ──────────
info "查找监听端口 $IGINX_PORT 的 IGinX 进程..."

TARGET_PIDS=$(eval "$SSH_CMD '
  PIDS=""

  # 方法1: ss（优先）
  if command -v ss >/dev/null 2>&1; then
    PIDS=\$(ss -lntp 2>/dev/null \
      | grep -E ":${IGINX_PORT}([[:space:]]|\$)" \
      | grep -o "pid=[0-9]*" \
      | cut -d= -f2 \
      | sort -u \
      | xargs)
  fi

  # 方法2: lsof
  if [ -z "\$PIDS" ] && command -v lsof >/dev/null 2>&1; then
    PIDS=\$(lsof -ti tcp:${IGINX_PORT} -sTCP:LISTEN 2>/dev/null | sort -u | xargs)
  fi

  # 方法3: 目录兜底（按部署目录识别）
  if [ -z "\$PIDS" ]; then
    PIDS=\$(pgrep -f "${REMOTE_TARGET_DIR}" 2>/dev/null | sort -u | xargs)
  fi

  echo "\$PIDS"
'" 2>/dev/null | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//')

if [ -z "$TARGET_PIDS" ]; then
    warn "未找到监听端口 $IGINX_PORT 的进程，可能已停止"
else
    info "找到进程 PID: $TARGET_PIDS，正在发送 SIGTERM..."
    eval "$SSH_CMD 'kill -15 $TARGET_PIDS'" 2>/dev/null || true

    # ────────── 等待进程退出并释放端口 ──────────
    WAIT_TIMEOUT=30
    ELAPSED=0
    info "等待进程退出并释放端口..."
    while [ $ELAPSED -lt $WAIT_TIMEOUT ]; do
        ALIVE_PIDS=$(eval "$SSH_CMD '
          LEFT=""
          for pid in $TARGET_PIDS; do
            if kill -0 \$pid 2>/dev/null; then
              LEFT="\$LEFT \$pid"
            fi
          done
          echo \$LEFT
        '" 2>/dev/null | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//')

        PORT_OPEN=$(eval "$SSH_CMD '
          if command -v ss >/dev/null 2>&1; then
            ss -lnt 2>/dev/null | grep -Eq ":${IGINX_PORT}([[:space:]]|\$)" && echo yes || echo no
          elif command -v netstat >/dev/null 2>&1; then
            netstat -lnt 2>/dev/null | grep -Eq ":${IGINX_PORT}([[:space:]]|\$)" && echo yes || echo no
          else
            echo unknown
          fi
        '" 2>/dev/null | tr -d '[:space:]')

        if [ -z "$ALIVE_PIDS" ] && [ "$PORT_OPEN" != "yes" ]; then
            info "IGinX 进程已退出，端口 $IGINX_PORT 已释放"
            break
        fi

        sleep 2
        ELAPSED=$((ELAPSED + 2))
        echo -n "."
    done
    echo ""

    if [ $ELAPSED -ge $WAIT_TIMEOUT ]; then
        warn "等待超时，强制终止 PID: $TARGET_PIDS ..."
        eval "$SSH_CMD 'kill -9 $TARGET_PIDS'" 2>/dev/null || true
        sleep 2

        ALIVE_PIDS=$(eval "$SSH_CMD '
          LEFT=""
          for pid in $TARGET_PIDS; do
            if kill -0 \$pid 2>/dev/null; then
              LEFT="\$LEFT \$pid"
            fi
          done
          echo \$LEFT
        '" 2>/dev/null | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//')

        if [ -n "$ALIVE_PIDS" ]; then
            error "无法终止 IGinX 进程: $ALIVE_PIDS"
        fi

        info "已强制终止 IGinX"
    fi
fi

# ────────── 清理部署目录（仅当该目录下无其他进程在运行时） ──────────
info "检查目录 $REMOTE_TARGET_DIR 是否还有其他运行中的 IGinX 进程..."
OTHER_PROCS=$(eval "$SSH_CMD 'ps aux | grep java | grep \"$REMOTE_TARGET_DIR\" | grep -v grep | awk \"{print \\\$2}\"'" 2>/dev/null)

if [ -z "$OTHER_PROCS" ]; then
    info "目录下无其他进程，清理部署目录: $REMOTE_TARGET_DIR ..."
    eval "$SSH_CMD 'rm -rf $REMOTE_TARGET_DIR'" 2>/dev/null || true
    info "部署目录已清理"
else
    info "目录下仍有其他 IGinX 进程 (PID: $OTHER_PROCS)，跳过目录清理"
fi

info "节点停止完成！"
