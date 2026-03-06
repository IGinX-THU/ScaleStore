#!/bin/bash

# ================================================================
# IGinX 远程停止脚本
# 用法: ./stop_iginx.sh <目标IP> <用户名> <密码> <远程安装目录>
# 示例: ./stop_iginx.sh 10.0.21.44 ubuntu Yingchihua@123 /opt/iginx
# ================================================================

# ────────── 参数 ──────────
REMOTE_IP=$1
REMOTE_USER=$2
REMOTE_PASS=$3
REMOTE_INSTALL_DIR=$4

# ────────── 颜色输出 ──────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ────────── 参数检查 ──────────
if [ $# -lt 4 ]; then
    error "参数不足。用法: $0 <目标IP> <用户名> <密码> <远程安装目录>"
fi

PACKAGE_DIRNAME="IGinX-FastDeploy-0.8.0"
REMOTE_TARGET_DIR="$REMOTE_INSTALL_DIR/$PACKAGE_DIRNAME"
STOP_SCRIPT="$REMOTE_TARGET_DIR/stopIGinX.sh"

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

# ────────── 公共 SSH 参数 ──────────
SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10"
SSH_CMD="sshpass -p '$REMOTE_PASS' ssh $SSH_OPTS $REMOTE_USER@$REMOTE_IP"

# ────────── 测试连通性 ──────────
info "测试与 $REMOTE_IP 的 SSH 连接..."
eval "$SSH_CMD 'echo ok'" &> /dev/null \
    || error "无法连接到 $REMOTE_IP，请检查 IP、用户名、密码或网络"
info "SSH 连接正常"

# ────────── 检查停止脚本是否存在 ──────────
info "检查停止脚本是否存在..."
eval "$SSH_CMD 'test -f $STOP_SCRIPT'" \
    || error "停止脚本不存在: $STOP_SCRIPT"
info "停止脚本存在"

# ────────── 执行停止脚本 ──────────
info "正在停止 IGinX..."
STOP_OUTPUT=$(eval "$SSH_CMD \"bash -c '
export JAVA_HOME=/usr/lib/jvm/jdk1.8.0_461
export PATH=\$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
chmod +x $STOP_SCRIPT
bash $STOP_SCRIPT
'\"" 2>&1)

echo "$STOP_OUTPUT"

# ────────── 判断是否成功停止 ──────────
if echo "$STOP_OUTPUT" | grep -q "Kill IGinX"; then
    info "检测到 'Kill IGinX'，节点已成功停止"
else
    warn "未检测到 'Kill IGinX'，等待进程退出..."
    WAIT_TIMEOUT=30
    ELAPSED=0

    while [ $ELAPSED -lt $WAIT_TIMEOUT ]; do
        # 使用 [i]ginx 避免 pgrep 匹配自身
        PID_CHECK=$(eval "$SSH_CMD 'ps aux | grep \"[i]ginx\" | grep -v grep | head -1'" 2>/dev/null)
        if [ -z "$PID_CHECK" ]; then
            info "IGinX 进程已退出"
            break
        fi
        sleep 2
        ELAPSED=$((ELAPSED + 2))
        echo -n "."
    done
    echo ""

    # ────────── 超时强制终止 ──────────
    if [ $ELAPSED -ge $WAIT_TIMEOUT ]; then
        warn "等待超时，尝试强制终止..."
        eval "$SSH_CMD 'pkill -9 -f iginx'" 2>/dev/null || true
        sleep 2
        PID_CHECK=$(eval "$SSH_CMD 'ps aux | grep \"[i]ginx\" | grep -v grep | head -1'" 2>/dev/null)
        if [ -n "$PID_CHECK" ]; then
            error "无法终止 IGinX 进程"
        fi
        info "已强制终止 IGinX"
    fi
fi

info "节点停止完成！"
