#!/bin/bash

# ================================================================
# IGinX 远程停止脚本（按端口精确停止对应实例）
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

# ────────── 按端口找到该实例的 PID（唯一精确匹配） ──────────
# 优先用 ss，若没有则用 lsof，均失败则尝试从 proc 匹配
info "查找监听端口 $IGINX_PORT 的 IGinX 进程..."

TARGET_PID=$(eval "$SSH_CMD '
  PID=""
  # 方法1: ss
  if command -v ss &>/dev/null; then
    PID=\$(ss -tlnp 2>/dev/null | grep \":${IGINX_PORT} \" | grep -oP \"pid=\\K[0-9]+\" | head -1)
  fi
  # 方法2: lsof
  if [ -z \"\$PID\" ] && command -v lsof &>/dev/null; then
    PID=\$(lsof -ti tcp:${IGINX_PORT} 2>/dev/null | head -1)
  fi
  # 方法3: /proc 扫描
  if [ -z \"\$PID\" ]; then
    PORT_HEX=\$(printf \"%04X\" ${IGINX_PORT})
    for pid in \$(ls /proc | grep -E \"^[0-9]+\$\"); do
      if grep -q \"\$PORT_HEX\" /proc/\$pid/net/tcp6 2>/dev/null || grep -q \"\$PORT_HEX\" /proc/\$pid/net/tcp 2>/dev/null; then
        PID=\$pid; break
      fi
    done
  fi
  echo \$PID
'" 2>/dev/null | tr -d '[:space:]')

if [ -z "$TARGET_PID" ]; then
    warn "未找到监听端口 $IGINX_PORT 的进程，可能已停止"
else
    info "找到进程 PID=$TARGET_PID（端口 $IGINX_PORT），正在发送 SIGTERM..."
    eval "$SSH_CMD 'kill -15 $TARGET_PID'" 2>/dev/null || true

    # ────────── 等待该 PID 退出 ──────────
    WAIT_TIMEOUT=30
    ELAPSED=0
    info "等待进程退出..."
    while [ $ELAPSED -lt $WAIT_TIMEOUT ]; do
        ALIVE=$(eval "$SSH_CMD 'kill -0 $TARGET_PID 2>/dev/null && echo alive || echo dead'" 2>/dev/null | tr -d '[:space:]')
        if [ "$ALIVE" = "dead" ]; then
            info "IGinX 进程 $TARGET_PID 已退出"
            break
        fi
        sleep 2
        ELAPSED=$((ELAPSED + 2))
        echo -n "."
    done
    echo ""

    if [ $ELAPSED -ge $WAIT_TIMEOUT ]; then
        warn "等待超时，强制终止 PID=$TARGET_PID..."
        eval "$SSH_CMD 'kill -9 $TARGET_PID'" 2>/dev/null || true
        sleep 2
        ALIVE=$(eval "$SSH_CMD 'kill -0 $TARGET_PID 2>/dev/null && echo alive || echo dead'" 2>/dev/null | tr -d '[:space:]')
        if [ "$ALIVE" = "alive" ]; then
            error "无法终止 IGinX 进程 $TARGET_PID"
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
