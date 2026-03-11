#!/bin/bash

# ================================================================
# IGinX 远程部署脚本
# 用法: ./deploy_iginx.sh <目标IP> <用户名> <密码> <本机安装包路径> <远程安装目录> <ZK地址> <IGinX端口> <REST端口>
# 示例: ./deploy_iginx.sh 10.0.21.44 ubuntu Yingchihua@123 ~/IGinX-FastDeploy-0.8.0.tar.gz ~ 10.0.20.108:2181 6888 7888
# ================================================================

# ────────── 参数 ──────────
REMOTE_IP=$1
REMOTE_USER=$2
REMOTE_PASS=$3
LOCAL_PACKAGE=$4
REMOTE_INSTALL_DIR=$5
ZK_ADDRESS=$6
IGINX_PORT=${7:-6888}
REST_PORT=${8:-7888}

# ────────── 颜色输出 ──────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ────────── 参数检查 ──────────
if [ $# -lt 6 ]; then
    error "参数不足。用法: $0 <目标IP> <用户名> <密码> <本机安装包路径> <远程安装目录> <ZK地址> [IGinX端口] [REST端口]"
fi

if [ ! -f "$LOCAL_PACKAGE" ]; then
    error "安装包不存在: $LOCAL_PACKAGE"
fi

PACKAGE_FILENAME=$(basename "$LOCAL_PACKAGE")
PACKAGE_DIRNAME="${PACKAGE_FILENAME%.tar.gz}"
REMOTE_TARGET_DIR="$REMOTE_INSTALL_DIR/$PACKAGE_DIRNAME"
START_SCRIPT="$REMOTE_TARGET_DIR/sbin/start_iginx.sh"
CONFIG_FILE="$REMOTE_TARGET_DIR/conf/config.properties"
LOG_FILE="$REMOTE_TARGET_DIR/sbin/logs/iginx.log"

info "部署参数: IGinX端口=$IGINX_PORT, REST端口=$REST_PORT"

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

# ────────── 公共 SSH/SCP 参数 ──────────
SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10"
SSH_CMD="sshpass -p '$REMOTE_PASS' ssh $SSH_OPTS $REMOTE_USER@$REMOTE_IP"
SCP_CMD="sshpass -p '$REMOTE_PASS' scp $SSH_OPTS"

# ────────── 测试连通性 ──────────
info "测试与 $REMOTE_IP 的 SSH 连接..."
eval "$SSH_CMD 'echo ok'" &> /dev/null \
    || error "无法连接到 $REMOTE_IP，请检查 IP、用户名、密码或网络"
info "SSH 连接正常"

# ────────── 拷贝安装包 ──────────
info "正在拷贝 $PACKAGE_FILENAME 到 $REMOTE_USER@$REMOTE_IP:$REMOTE_INSTALL_DIR ..."
eval "$SCP_CMD '$LOCAL_PACKAGE' '$REMOTE_USER@$REMOTE_IP:$REMOTE_INSTALL_DIR/'" \
    || error "SCP 拷贝失败"
info "拷贝完成"

# ────────── 远程创建目录并解压 ──────────
info "正在远程解压到 $REMOTE_TARGET_DIR ..."
eval "$SSH_CMD '
    mkdir -p $REMOTE_TARGET_DIR &&
    tar -xzf $REMOTE_INSTALL_DIR/$PACKAGE_FILENAME -C $REMOTE_TARGET_DIR
'" || error "解压失败"
info "解压完成"

# ────────── 修改配置文件 ──────────
info "修改配置文件..."

# 修改 ZooKeeper 连接串
info "修改 ZooKeeper 配置为 $ZK_ADDRESS ..."
eval "$SSH_CMD '
    sed -i \"s|zookeeperConnectionString=.*|zookeeperConnectionString=$ZK_ADDRESS|\" $CONFIG_FILE
'" || error "修改 ZooKeeper 配置失败"

# 修改 IGinX 端口 (config.properties 中的 port=6888)
info "修改 IGinX 端口为 $IGINX_PORT ..."
eval "$SSH_CMD '
    sed -i \"s|^port=.*|port=$IGINX_PORT|\" $CONFIG_FILE
'" || error "修改 IGinX 端口失败"

# 修改 REST 端口 (config.properties 中的 restPort=7888)
info "修改 REST 端口为 $REST_PORT ..."
eval "$SSH_CMD '
    sed -i \"s|^restPort=.*|restPort=$REST_PORT|\" $CONFIG_FILE
'" || error "修改 REST 端口失败"

# 修改 storageEngineList 中的 iginx_port
info "修改 iginx_port 为 $IGINX_PORT ..."
eval "$SSH_CMD '
    sed -i \"s|^storageEngineList=127.0.0.1#[0-9]*#filesystem#iginx_port=[0-9]*#|storageEngineList=127.0.0.1#6668#filesystem#iginx_port=${IGINX_PORT}#|\" $CONFIG_FILE
'" || error "修改数据引擎配置失败"

info "配置修改完成"

# ────────── 赋予执行权限 ──────────
info "赋予执行权限..."
eval "$SSH_CMD 'chmod +x $START_SCRIPT'" \
    || error "赋予执行权限失败"

# ────────── 清理旧日志并启动 ──────────
info "清理旧日志文件..."
eval "$SSH_CMD 'rm -f $LOG_FILE'"
info "正在启动 IGinX..."
eval "$SSH_CMD \"bash -c '
export JAVA_HOME=/usr/lib/jvm/jdk1.8.0_461
export PATH=\$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
mkdir -p $(dirname $LOG_FILE)
setsid nohup $START_SCRIPT > $LOG_FILE 2>&1 < /dev/null &
'\""

# ────────── 轮询检测启动状态 ──────────
SUCCESS_KEYWORD="IGinX is now in service"
FAIL_KEYWORD="Exception"
WAIT_TIMEOUT=60
ELAPSED=0

info "正在等待 IGinX 启动 (超时时间: ${WAIT_TIMEOUT}s)..."

while [ $ELAPSED -lt $WAIT_TIMEOUT ]; do
    if eval "$SSH_CMD 'grep -q \"$SUCCESS_KEYWORD\" $LOG_FILE 2>/dev/null'"; then
        echo ""
        info "============================================"
        info "IGinX 启动成功！"
        info "已在日志中检测到关键语句: \"$SUCCESS_KEYWORD\""
        info "============================================"
        
        PID=$(eval "$SSH_CMD 'pgrep -f iginx | head -1'")
        if [ -n "$PID" ]; then
            info "进程 PID: $PID"
        fi
        info "日志文件: $LOG_FILE"
        break
    fi

    if eval "$SSH_CMD 'grep -q \"$FAIL_KEYWORD\" $LOG_FILE 2>/dev/null'"; then
        echo ""
        error "检测到启动错误 (关键字: $FAIL_KEYWORD)，请检查日志:\n  ssh $REMOTE_USER@$REMOTE_IP 'tail -100 $LOG_FILE'"
    fi

    PID_CHECK=$(eval "$SSH_CMD 'pgrep -f iginx | head -1'")
    if [ -z "$PID_CHECK" ] && [ $ELAPSED -gt 5 ]; then
         echo ""
         error "IGinX 进程已退出，启动失败。请查看日志:\n  ssh $REMOTE_USER@$REMOTE_IP 'tail -100 $LOG_FILE'"
    fi

    sleep 2
    ELAPSED=$((ELAPSED + 2))
    echo -n "."
done
echo ""

# ────────── 超时判断 ──────────
if [ $ELAPSED -ge $WAIT_TIMEOUT ]; then
    error "等待超时 ($WAIT_TIMEOUT 秒)。未检测到启动成功标志，请检查日志:\n  ssh $REMOTE_USER@$REMOTE_IP 'tail -100 $LOG_FILE'"
fi

info "部署完成！"
