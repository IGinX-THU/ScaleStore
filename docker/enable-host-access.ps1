# 配置宿主机访问Docker容器内部IP的路由（永久生效版本）
# 适用于 Windows + Docker Desktop

Write-Host "=== 配置宿主机访问Docker容器内部IP（永久路由）===" -ForegroundColor Green

# 获取Docker网络的网关IP
# Docker Compose会自动添加项目名前缀，所以实际网络名是 docker_scalestore-cluster
$networkName = "docker_scalestore-cluster"
$gatewayIP = docker network inspect $networkName --format '{{range .IPAM.Config}}{{.Gateway}}{{end}}'

if (-not $gatewayIP) {
    Write-Host "[ERROR] 无法获取网络 $networkName 的网关IP" -ForegroundColor Red
    Write-Host "[INFO] 请先启动容器: docker compose up -d" -ForegroundColor Yellow
    exit 1
}

Write-Host "[INFO] Docker网络网关: $gatewayIP" -ForegroundColor Cyan

# 添加路由规则
$subnet = "172.25.0.0/16"
Write-Host "[INFO] 添加永久路由: $subnet -> $gatewayIP" -ForegroundColor Cyan

try {
    # 删除旧路由（如果存在）
    route delete 172.25.0.0 2>$null
    
    # 添加永久路由（-p 参数表示永久，重启后依然有效）
    route -p add 172.25.0.0 mask 255.255.0.0 $gatewayIP metric 1
    
    Write-Host "[SUCCESS] 永久路由配置成功（重启后依然有效）！" -ForegroundColor Green
    Write-Host ""
    Write-Host "现在可以直接访问容器IP了：" -ForegroundColor Green
    Write-Host "  - SSH: ssh -p 22 root@172.25.0.2" -ForegroundColor White
    Write-Host "  - Ping: ping 172.25.0.2" -ForegroundColor White
    Write-Host ""
    Write-Host "在Web UI中添加节点时填写：" -ForegroundColor Cyan
    Write-Host "  节点IP: 172.25.0.2 (node-02)" -ForegroundColor White
    Write-Host "  SSH端口: 22 (容器内部SSH端口)" -ForegroundColor White
    Write-Host "  IGinX端口: 6889" -ForegroundColor White
    Write-Host ""
    Write-Host "测试连接：" -ForegroundColor Yellow
    $result = Test-NetConnection -ComputerName 172.25.0.2 -Port 22 -InformationLevel Quiet
    if ($result) {
        Write-Host "[SUCCESS] 可以访问 172.25.0.2:22" -ForegroundColor Green
    } else {
        Write-Host "[WARN] 无法访问 172.25.0.2:22，请确保容器已启动" -ForegroundColor Yellow
    }
    
} catch {
    Write-Host "[ERROR] 路由配置失败: $_" -ForegroundColor Red
    Write-Host "[INFO] 请以管理员权限运行此脚本" -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "✅ 路由已配置为永久生效（使用 -p 参数）" -ForegroundColor Green
Write-Host "   系统重启后无需重新配置" -ForegroundColor Green
Write-Host ""
Write-Host "如需删除永久路由，请运行: route delete 172.25.0.0" -ForegroundColor Yellow
