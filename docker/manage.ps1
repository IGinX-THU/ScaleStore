# ================================================================
# ScaleStore Docker Cluster Management (Windows PowerShell)
# Usage: .\manage.ps1 [build|up|down|status]
# ================================================================

param(
    [Parameter(Position=0)]
    [ValidateSet("build", "up", "down", "status", "help")]
    [string]$Action = "help"
)

$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot

$SSH_PASSWORD = "scalestore"
$SSH_USER = "root"
$BASE_SSH_PORT = 2201
$BASE_IGINX_PORT = 6889
$NODE_COUNT = 23

function Write-Header($msg) {
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host " $msg" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
}

switch ($Action) {
    "build" {
        Write-Header "Building Docker Image"
        docker compose build
        Write-Host "[INFO] Image built successfully." -ForegroundColor Green
    }
    "up" {
        Write-Header "Starting All 23 Docker Nodes"
        docker compose up -d
        Write-Host "[INFO] All containers started." -ForegroundColor Green
        Write-Host ""
        Write-Host "[INFO] Node mapping:" -ForegroundColor Green
        Write-Host ("{0,-14} {1,-10} {2,-12}" -f "Container", "SSH Port", "IGinX Port")
        Write-Host ("{0,-14} {1,-10} {2,-12}" -f "---------", "--------", "----------")
        for ($i = 1; $i -le $NODE_COUNT; $i++) {
            $nodeNum = $i + 1
            $sshPort = $BASE_SSH_PORT + $i - 1
            $iginxPort = $BASE_IGINX_PORT + $i - 1
            $name = "node-{0:D2}" -f $nodeNum
            Write-Host ("{0,-14} {1,-10} {2,-12}" -f $name, $sshPort, $iginxPort)
        }
        Write-Host ""
        Write-Host "[INFO] SSH credentials: user=$SSH_USER password=$SSH_PASSWORD" -ForegroundColor Green
        Write-Host "[INFO] ZooKeeper expected at host: 127.0.0.1:2181" -ForegroundColor Green
    }
    "down" {
        Write-Header "Stopping All Docker Nodes"
        docker compose down
        Write-Host "[INFO] All containers stopped." -ForegroundColor Green
    }
    "status" {
        Write-Header "Container Status"
        docker compose ps
    }
    "help" {
        Write-Host "Usage: .\manage.ps1 {build|up|down|status}"
        Write-Host ""
        Write-Host "  build   - Build the Docker image"
        Write-Host "  up      - Start all 23 node containers"
        Write-Host "  down    - Stop and remove all containers"
        Write-Host "  status  - Show container status"
    }
}

Pop-Location
