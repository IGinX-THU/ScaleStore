#!/bin/bash

# ================================================================
# IGinX 远程部署脚本
# 用法: ./deploy_iginx.sh <目标IP> <用户名> <密码> <SSH端口> <本机安装包路径> <远程安装目录> <ZK地址> <IGinX端口> <pythonCMD> <本机udf_list路径> <本机metadata目录路径>
# 示例: ./deploy_iginx.sh 10.0.21.44 ubuntu password 22 ~/iginx-0.9.0-SNAPSHOT.tar.gz ~ 10.0.20.108:2181 6888 python3 /opt/resources/udf/udf_list /opt/resources/udf/metadata
# ================================================================

# ────────── 参数 ──────────
REMOTE_IP=$1
REMOTE_USER=$2
REMOTE_PASS=$3
SSH_PORT=${4:-22}
LOCAL_PACKAGE=$5
REMOTE_INSTALL_DIR=$6
ZK_ADDRESS=$7
IGINX_PORT=${8:-6888}
PYTHON_CMD=${9:-python3}
LOCAL_UDF_LIST=${10}
LOCAL_METADATA_DIR=${11}

# ────────── 颜色输出 ──────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# ────────── 参数检查 ──────────
if [ $# -lt 11 ]; then
    error "参数不足。请同时提供 pythonCMD、udf_list 路径、metadata 目录路径"
fi

if ! [[ "$SSH_PORT" =~ ^[0-9]+$ ]] || [ "$SSH_PORT" -lt 1 ] || [ "$SSH_PORT" -gt 65535 ]; then
    error "SSH端口非法: $SSH_PORT（需为 1-65535 的整数）"
fi

if [ ! -f "$LOCAL_PACKAGE" ]; then
    error "安装包不存在: $LOCAL_PACKAGE"
fi

if [ ! -f "$LOCAL_UDF_LIST" ]; then
    error "udf_list 不存在: $LOCAL_UDF_LIST"
fi

if [ ! -d "$LOCAL_METADATA_DIR" ]; then
    error "metadata 目录不存在: $LOCAL_METADATA_DIR"
fi

PACKAGE_FILENAME=$(basename "$LOCAL_PACKAGE")
PACKAGE_DIRNAME="${PACKAGE_FILENAME%.tar.gz}"
REMOTE_TARGET_DIR="$REMOTE_INSTALL_DIR/$PACKAGE_DIRNAME"
START_SCRIPT="$REMOTE_TARGET_DIR/sbin/start_iginx.sh"
CONFIG_FILE="$REMOTE_TARGET_DIR/conf/config.properties"
LOG_FILE="$REMOTE_TARGET_DIR/sbin/logs/iginx.log"
REMOTE_UDF_HOME="$REMOTE_TARGET_DIR/udf_funcs"
REMOTE_UDF_PY_DIR="$REMOTE_UDF_HOME/python_scripts"

info "部署参数: SSH端口=$SSH_PORT, IGinX端口=$IGINX_PORT, pythonCMD=$PYTHON_CMD"

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
SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10 -p $SSH_PORT"
SCP_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10 -P $SSH_PORT"
SSH_CMD="sshpass -p '$REMOTE_PASS' ssh $SSH_OPTS $REMOTE_USER@$REMOTE_IP"
SCP_CMD="sshpass -p '$REMOTE_PASS' scp $SCP_OPTS"

# ────────── 测试连通性 ──────────
info "测试与 $REMOTE_IP 的 SSH 连接..."
eval "$SSH_CMD 'echo ok'" &> /dev/null \
    || error "无法连接到 $REMOTE_IP，请检查 IP、用户名、密码或网络"
info "SSH 连接正常"

# ────────── 确保远程目录存在 ──────────
info "检查并创建远程安装目录: $REMOTE_INSTALL_DIR ..."
eval "$SSH_CMD 'mkdir -p $REMOTE_INSTALL_DIR'" \
    || error "创建远程安装目录失败: $REMOTE_INSTALL_DIR"

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

# ────────── 拷贝 UDF 资源 ──────────
info "拷贝 udf_list 到远程 ${REMOTE_UDF_HOME}/ ..."
eval "$SCP_CMD '$LOCAL_UDF_LIST' '$REMOTE_USER@$REMOTE_IP:$REMOTE_UDF_HOME/'" \
    || error "拷贝 udf_list 失败"

info "拷贝 metadata 目录到远程 ${REMOTE_UDF_PY_DIR}/ ..."
eval "$SCP_CMD -r '$LOCAL_METADATA_DIR' '$REMOTE_USER@$REMOTE_IP:$REMOTE_UDF_PY_DIR/'" \
    || error "拷贝 metadata 目录失败"
info "UDF 资源拷贝完成"

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

# 降低每个存储引擎的物理任务线程池大小
info "设置 physicalTaskThreadPoolSizePerStorage=10 ..."
eval "$SSH_CMD '
    sed -i \"s|^physicalTaskThreadPoolSizePerStorage=100|physicalTaskThreadPoolSizePerStorage=10|\" $CONFIG_FILE
'" || error "设置 physicalTaskThreadPoolSizePerStorage 失败"

# 修改 pythonCMD
info "修改 pythonCMD 为 $PYTHON_CMD ..."
eval "$SSH_CMD '
        if grep -q \"^pythonCMD=\" $CONFIG_FILE; then
            sed -i \"s|^pythonCMD=.*|pythonCMD=$PYTHON_CMD|\" $CONFIG_FILE
        else
            echo \"pythonCMD=$PYTHON_CMD\" >> $CONFIG_FILE
        fi
'" || error "修改 pythonCMD 失败"

# 关闭 Rest 服务
info "设置 enableRestService=false ..."
eval "$SSH_CMD '
        if grep -q \"^enableRestService=\" $CONFIG_FILE; then
            sed -i \"s|^enableRestService=.*|enableRestService=false|\" $CONFIG_FILE
        else
            echo \"enableRestService=false\" >> $CONFIG_FILE
        fi
'" || error "设置 enableRestService 失败"

# 打开基础 UDF 初始化
info "设置 needInitBasicUDFFunctions=true ..."
eval "$SSH_CMD '
        if grep -q \"^needInitBasicUDFFunctions=\" $CONFIG_FILE; then
            sed -i \"s|^needInitBasicUDFFunctions=.*|needInitBasicUDFFunctions=true|\" $CONFIG_FILE
        else
            echo \"needInitBasicUDFFunctions=true\" >> $CONFIG_FILE
        fi
'" || error "设置 needInitBasicUDFFunctions 失败"

# 注释 storageEngineList，避免通过该流程添加的数据引擎
info "注释 storageEngineList，跳过数据引擎注册 ..."
eval "$SSH_CMD 'sed -i \"s|^storageEngineList=|#storageEngineList=|\" $CONFIG_FILE'" \
    || error "注释数据引擎配置失败"

info "配置修改完成"

# ────────── 赋予执行权限 ──────────
info "赋予执行权限..."
eval "$SSH_CMD 'chmod +x $START_SCRIPT'" \
    || error "赋予执行权限失败"

# ────────── 清理旧日志并启动 ──────────
info "清理旧日志文件..."
eval "$SSH_CMD 'rm -f $LOG_FILE'"
info "正在启动 IGinX..."
eval "$SSH_CMD 'LOG_FILE=\"$LOG_FILE\" START_SCRIPT=\"$START_SCRIPT\" bash -l -s'" <<'EOF' || error "启动 IGinX 命令执行失败"
set -e

# Login shell usually loads JAVA_HOME; source common profiles again for safety.
[ -f /etc/profile ] && . /etc/profile >/dev/null 2>&1 || true
[ -f ~/.bash_profile ] && . ~/.bash_profile >/dev/null 2>&1 || true
[ -f ~/.profile ] && . ~/.profile >/dev/null 2>&1 || true
[ -f ~/.bashrc ] && . ~/.bashrc >/dev/null 2>&1 || true

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN=$(command -v java 2>/dev/null || true)
fi

# Last resort: scan common install roots for any executable java.
if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
    JAVA_BIN=$(find /usr /opt -type f -path '*/bin/java' -perm -111 2>/dev/null | head -n 1 || true)
fi

if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
    echo "[ERROR] 未找到 java，请先安装 JDK 并配置 JAVA_HOME 或 PATH"
    echo "[ERROR] 诊断信息: PATH=$PATH"
    echo "[ERROR] 诊断信息: JAVA_HOME=${JAVA_HOME:-<empty>}"
    exit 1
fi

JAVA_BIN=$(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")
JAVA_HOME=$(dirname "$(dirname "$JAVA_BIN")")
export JAVA_HOME
export JAVA="$JAVA_BIN"
export JAVA_CMD="$JAVA_BIN"
export JAVACMD="$JAVA_BIN"
export PATH="$(dirname "$JAVA_BIN"):/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

echo "[INFO] Java 环境确认: JAVA_HOME=$JAVA_HOME"
echo "[INFO] Java 环境确认: JAVA_BIN=$JAVA_BIN"
echo "[INFO] Java 环境确认: PATH=$PATH"

mkdir -p "$(dirname "$LOG_FILE")"
sed -i 's/\r$//' "$START_SCRIPT"
setsid nohup env JAVA_HOME="$JAVA_HOME" JAVA="$JAVA_BIN" JAVA_CMD="$JAVA_BIN" JAVACMD="$JAVA_BIN" PATH="$PATH" bash "$START_SCRIPT" > "$LOG_FILE" 2>&1 < /dev/null &
EOF

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
