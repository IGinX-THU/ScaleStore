#!/bin/bash

# ================================================================
# Filesystem 数据源大小计算脚本
# 用法: ./calculate_filesystem_size.sh <目标IP> <SSH用户名> <SSH密码> <SSH端口> <目标目录>
# 示例: ./calculate_filesystem_size.sh 11.101.17.24 aq password 22 /home/aq/ych/picture
# ================================================================

# ────────── 参数 ──────────
REMOTE_IP=$1
SSH_USER=$2
SSH_PASS=$3
SSH_PORT=${4:-22}
TARGET_DIR=$5

# ────────── 颜色输出 ──────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1" >&2; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1" >&2; }
error() { echo -e "${RED}[ERROR]${NC} $1" >&2; exit 1; }

# ────────── 参数检查 ──────────
if [ $# -lt 5 ]; then
    error "参数不足。用法: $0 <目标IP> <SSH用户名> <SSH密码> <SSH端口> <目标目录>"
fi

if ! [[ "$SSH_PORT" =~ ^[0-9]+$ ]] || [ "$SSH_PORT" -lt 1 ] || [ "$SSH_PORT" -gt 65535 ]; then
    error "SSH端口非法: $SSH_PORT（需为 1-65535 的整数）"
fi

if [ -z "$TARGET_DIR" ]; then
    error "目标目录不能为空"
fi

# ────────── 检查 sshpass ──────────
if ! command -v sshpass &> /dev/null; then
    warn "未检测到 sshpass，正在安装..."
    if command -v apt-get &> /dev/null; then
        sudo apt-get install -y sshpass >&2
    elif command -v yum &> /dev/null; then
        sudo yum install -y sshpass >&2
    else
        error "无法自动安装 sshpass，请手动安装后重试"
    fi
fi

# ────────── 公共 SSH 参数 ──────────
SSH_OPTS="-o StrictHostKeyChecking=no -o ConnectTimeout=10 -p $SSH_PORT"
SSH_CMD="sshpass -p '$SSH_PASS' ssh $SSH_OPTS $SSH_USER@$REMOTE_IP"

# ────────── 测试连通性 ──────────
info "测试与 $REMOTE_IP 的 SSH 连接..."
eval "$SSH_CMD 'echo ok'" &> /dev/null \
    || error "无法连接到 $REMOTE_IP，请检查 IP、用户名、密码或网络"
info "SSH 连接正常"

# ────────── 检查目标目录是否存在 ──────────
info "检查目标目录: $TARGET_DIR ..."
eval "$SSH_CMD '[ -d \"$TARGET_DIR\" ]'" \
    || error "目标目录不存在: $TARGET_DIR"
info "目标目录存在"

# ────────── 执行 du -a 命令 ──────────
info "正在执行 du -a 命令获取文件大小..."
DU_OUTPUT=$(eval "$SSH_CMD 'cd \"$TARGET_DIR\" && du -a'")

if [ $? -ne 0 ]; then
    error "执行 du -a 命令失败"
fi

if [ -z "$DU_OUTPUT" ]; then
    error "du -a 命令返回空结果"
fi

info "成功获取文件大小信息"

# ────────── 输出结果（JSON格式） ──────────
echo "{"
echo "  \"success\": true,"
echo "  \"targetDir\": \"$TARGET_DIR\","
echo "  \"files\": ["

FIRST_LINE=true
while IFS= read -r line; do
    if [ -z "$line" ]; then
        continue
    fi
    
    SIZE=$(echo "$line" | awk '{print $1}')
    FILEPATH=$(echo "$line" | awk '{$1=""; print $0}' | sed 's/^ *//' | sed 's|^\./||')
    
    if [ -z "$FILEPATH" ]; then
        continue
    fi
    
    if [ "$FILEPATH" = "." ]; then
        continue
    fi
    
    if [ "$FIRST_LINE" = true ]; then
        FIRST_LINE=false
    else
        echo ","
    fi
    
    echo -n "    {\"size\": $SIZE, \"path\": \"$FILEPATH\"}"
done <<< "$DU_OUTPUT"

echo ""
echo "  ]"
echo "}"

info "脚本执行完成"