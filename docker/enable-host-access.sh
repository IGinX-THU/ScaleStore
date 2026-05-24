# Linux版本：配置宿主机访问Docker容器内部IP的路由

echo "=== 配置宿主机访问Docker容器内部IP ==="

# 获取Docker网络的网关IP
# Docker Compose会自动添加项目名前缀，所以实际网络名是 docker_scalestore-cluster
NETWORK_NAME="docker_scalestore-cluster"
GATEWAY_IP=$(docker network inspect $NETWORK_NAME --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}')

if [ -z "$GATEWAY_IP" ]; then
    echo "[ERROR] 无法获取网络 $NETWORK_NAME 的网关IP"
    echo "[INFO] 请先启动容器: docker compose up -d"
    exit 1
fi

echo "[INFO] Docker网络网关: $GATEWAY_IP"

# 获取Docker网桥接口
BRIDGE_INTERFACE=$(docker network inspect $NETWORK_NAME --format '{{.Id}}' | cut -c1-12)
BRIDGE_NAME="br-$BRIDGE_INTERFACE"

echo "[INFO] Docker网桥接口: $BRIDGE_NAME"

# 添加路由规则
SUBNET="172.25.0.0/16"
echo "[INFO] 添加路由: $SUBNET -> $GATEWAY_IP"

# 删除旧路由（如果存在）
sudo ip route del $SUBNET 2>/dev/null

# 添加新路由
sudo ip route add $SUBNET via $GATEWAY_IP dev $BRIDGE_NAME

if [ $? -eq 0 ]; then
    echo "[SUCCESS] 路由配置成功！"
    echo ""
    echo "现在可以直接访问容器IP了："
    echo "  - SSH: ssh -p 22 root@172.25.0.2"
    echo "  - Ping: ping 172.25.0.2"
    echo ""
    echo "在Web UI中添加节点时填写："
    echo "  节点IP: 172.25.0.2 (node-02)"
    echo "  SSH端口: 22 (容器内部SSH端口)"
    echo "  IGinX端口: 6889"
    echo ""
    echo "测试连接："
    ping -c 3 172.25.0.2
else
    echo "[ERROR] 路由配置失败"
    echo "[INFO] 请确保以root权限运行此脚本"
    exit 1
fi

echo ""
echo "注意：此路由在重启后会失效，需要重新运行此脚本"
echo "如需永久配置，请添加到 /etc/network/interfaces 或使用 systemd-networkd"
