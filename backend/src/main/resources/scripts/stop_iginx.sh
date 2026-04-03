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

SSH_OPTS=(-o StrictHostKeyChecking=no -o ConnectTimeout=10)

ssh_exec() {
    sshpass -p "$REMOTE_PASS" ssh "${SSH_OPTS[@]}" "$REMOTE_USER@$REMOTE_IP" "$@"
}

REMOTE_PRELUDE='. /etc/profile >/dev/null 2>&1 || true; . ~/.bash_profile >/dev/null 2>&1 || true; PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH;'

check_port_open() {
        ssh_exec "$REMOTE_PRELUDE
        if command -v ss >/dev/null 2>&1; then
            if command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
                sudo -n ss -lnt 2>/dev/null | grep -E ':${IGINX_PORT}[[:space:]]' >/dev/null && echo yes || echo no
            else
                ss -lnt 2>/dev/null | grep -E ':${IGINX_PORT}[[:space:]]' >/dev/null && echo yes || echo no
            fi
        elif command -v netstat >/dev/null 2>&1; then
            if command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
                sudo -n netstat -lnt 2>/dev/null | grep -E ':${IGINX_PORT}[[:space:]]' >/dev/null && echo yes || echo no
            else
                netstat -lnt 2>/dev/null | grep -E ':${IGINX_PORT}[[:space:]]' >/dev/null && echo yes || echo no
            fi
        else
            echo unknown
        fi" 2>/dev/null | tr -d '[:space:]'
}

info "测试与 $REMOTE_IP 的 SSH 连接..."
ssh_exec "echo ok" &> /dev/null \
    || error "无法连接到 $REMOTE_IP，请检查 IP、用户名、密码或网络"
info "SSH 连接正常"

# 解析远端部署目录（支持用户传入 ~）
if [[ "$REMOTE_TARGET_DIR" == ~* ]]; then
    REMOTE_HOME=$(ssh_exec "$REMOTE_PRELUDE echo \$HOME" 2>/dev/null | tr -d '\r')
    if [ -n "$REMOTE_HOME" ]; then
        REMOTE_TARGET_DIR="${REMOTE_HOME}${REMOTE_TARGET_DIR:1}"
    fi
fi
info "解析后的停止目录: $REMOTE_TARGET_DIR"

# ────────── 按端口/目录找到该实例的 PID（支持多进程） ──────────
info "查找监听端口 $IGINX_PORT 的 IGinX 进程..."

PORT_SCAN_OUTPUT=$(ssh_exec "$REMOTE_PRELUDE
    if command -v ss >/dev/null 2>&1; then
        if command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
            sudo -n ss -lntp 2>/dev/null | grep -E ':${IGINX_PORT}[[:space:]]'
        else
            ss -lntp 2>/dev/null | grep -E ':${IGINX_PORT}[[:space:]]'
        fi
    elif command -v lsof >/dev/null 2>&1; then
        if command -v sudo >/dev/null 2>&1 && sudo -n true >/dev/null 2>&1; then
            sudo -n lsof -nP -iTCP:${IGINX_PORT} -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print "pid=" $2}'
        else
            lsof -nP -iTCP:${IGINX_PORT} -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print "pid=" $2}'
        fi
    fi" 2>/dev/null)

PORT_PIDS=$(echo "$PORT_SCAN_OUTPUT" \
    | grep -o 'pid=[0-9]*' \
    | cut -d= -f2 \
    | sort -u \
    | xargs)

JPS_IGINX_PIDS=$(ssh_exec "$REMOTE_PRELUDE
    if command -v jps >/dev/null 2>&1; then
        jps -l 2>/dev/null | awk 'BEGIN{IGNORECASE=1} \$2 ~ /iginx/ {print \$1}' | sort -u | xargs
    fi" 2>/dev/null | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//')

info "端口匹配 PID: ${PORT_PIDS:-<none>}"
info "JPS(Iginx) PID: ${JPS_IGINX_PIDS:-<none>}"

TARGET_MODE="none"
TARGET_PIDS=""
MATCH_PIDS=""

if [ -n "$PORT_PIDS" ] && [ -n "$JPS_IGINX_PIDS" ]; then
    for pid in $PORT_PIDS; do
                for jpid in $JPS_IGINX_PIDS; do
                        if [ "$pid" = "$jpid" ]; then
                                MATCH_PIDS="$MATCH_PIDS $pid"
            fi
        done
    done
        MATCH_PIDS=$(echo "$MATCH_PIDS" | xargs)
fi

if [ -n "$MATCH_PIDS" ]; then
        TARGET_MODE="jps+port"
        TARGET_PIDS="$MATCH_PIDS"
elif [ -n "$PORT_PIDS" ] && [ -z "$JPS_IGINX_PIDS" ]; then
        TARGET_MODE="port"
        TARGET_PIDS="$PORT_PIDS"
        warn "jps 未返回 Iginx 进程，回退为端口匹配 PID: $TARGET_PIDS"
elif [ -z "$PORT_PIDS" ] && [ -n "$JPS_IGINX_PIDS" ]; then
        error "jps 检测到 Iginx 进程，但端口 ${IGINX_PORT} 未解析到 PID，拒绝误杀"
elif [ -n "$PORT_PIDS" ] && [ -n "$JPS_IGINX_PIDS" ]; then
        error "端口 PID 与 jps Iginx PID 无交集，拒绝误杀（port=$PORT_PIDS, jps=$JPS_IGINX_PIDS）"
fi

if [ -z "$TARGET_PIDS" ]; then
    PORT_OPEN=$(check_port_open)
    if [ "$PORT_OPEN" = "yes" ]; then
        error "未定位到目标 PID，但端口 $IGINX_PORT 仍在监听"
    fi
    warn "未找到监听端口 $IGINX_PORT 的进程，可能已停止"
else
    if [ "$TARGET_MODE" = "jps+port" ]; then
        info "匹配到 jps+端口交集 PID: $TARGET_PIDS"
    elif [ "$TARGET_MODE" = "port" ]; then
        warn "仅按端口匹配到 PID: $TARGET_PIDS"
    else
        warn "匹配到 PID: $TARGET_PIDS"
    fi

    info "直接发送 SIGKILL 到目标进程: $TARGET_PIDS"
    ssh_exec "kill -9 $TARGET_PIDS" 2>/dev/null || true
    sleep 1

    REMAIN_PIDS=""
    for pid in $TARGET_PIDS; do
        if ssh_exec "kill -0 $pid" 2>/dev/null; then
            REMAIN_PIDS="$REMAIN_PIDS $pid"
        fi
    done
    REMAIN_PIDS=$(echo "$REMAIN_PIDS" | xargs)

    if [ -n "$REMAIN_PIDS" ]; then
        error "SIGKILL 后仍有进程存活: $REMAIN_PIDS"
    fi

    PORT_OPEN=$(check_port_open)

    if [ "$PORT_OPEN" = "yes" ]; then
        warn "目标 PID 已终止，但端口 $IGINX_PORT 仍被占用，可能有其他实例在监听"
    else
        info "目标进程已终止，端口 $IGINX_PORT 已释放"
    fi
fi

# ────────── 清理部署目录（仅当该目录下无其他进程在运行时） ──────────
info "检查目录 $REMOTE_TARGET_DIR 是否还有其他运行中的 IGinX 进程..."
OTHER_PROCS=$(ssh_exec "$REMOTE_PRELUDE pgrep -f '$REMOTE_TARGET_DIR' 2>/dev/null | sort -u | xargs" 2>/dev/null | tr -s '[:space:]' ' ' | sed 's/^ //; s/ $//')

if [ -z "$OTHER_PROCS" ]; then
    info "目录下无其他进程，清理部署目录: $REMOTE_TARGET_DIR ..."
  ssh_exec "rm -rf '$REMOTE_TARGET_DIR'" 2>/dev/null || true
    info "部署目录已清理"
else
    info "目录下仍有其他 IGinX 进程 (PID: $OTHER_PROCS)，跳过目录清理"
fi

info "节点停止完成！"
