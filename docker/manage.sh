#!/bin/bash
# ================================================================
# Self-fix CRLF: if this script has Windows line endings, re-exec with clean version
# ================================================================
if head -1 "$0" | grep -q $'\r'; then
    TMP=$(mktemp)
    sed 's/\r$//' "$0" > "$TMP"
    chmod +x "$TMP"
    exec bash "$TMP" "$@"
fi

# ================================================================
# ScaleStore Docker Cluster Management Script
# Usage: ./manage.sh [build|up|down|status|test-ssh|ps]
# ================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

SSH_PASSWORD="scalestore"
SSH_USER="root"
BASE_SSH_PORT=2201
BASE_IGINX_PORT=6889
NODE_COUNT=23

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
header(){ echo -e "${CYAN}========================================${NC}"; echo -e "${CYAN} $1${NC}"; echo -e "${CYAN}========================================${NC}"; }

case "${1:-help}" in
    build)
        header "Building Docker Image"
        docker compose build
        info "Image built successfully."
        ;;

    up)
        header "Starting All 23 Docker Nodes"
        docker compose up -d
        info "All containers started."
        echo ""
        info "Node mapping (host perspective):"
        printf "  %-12s %-10s %-12s\n" "Container" "SSH Port" "IGinX Port"
        printf "  %-12s %-10s %-12s\n" "---------" "--------" "----------"
        for i in $(seq 1 $NODE_COUNT); do
            NODE_NUM=$((i + 1))
            SSH_PORT=$((BASE_SSH_PORT + i - 1))
            IGINX_PORT=$((BASE_IGINX_PORT + i - 1))
            printf "  %-12s %-10s %-12s\n" "node-$(printf '%02d' $NODE_NUM)" "$SSH_PORT" "$IGINX_PORT"
        done
        echo ""
        info "SSH credentials: user=$SSH_USER password=$SSH_PASSWORD"
        info "ZooKeeper expected at host: 127.0.0.1:2181"
        ;;

    down)
        header "Stopping All Docker Nodes"
        docker compose down
        info "All containers stopped and removed."
        ;;

    status|ps)
        header "Container Status"
        docker compose ps
        ;;

    test-ssh)
        header "Testing SSH Connectivity"
        if ! command -v sshpass &>/dev/null; then
            error "sshpass not installed. Install with: apt-get install sshpass"
            exit 1
        fi
        PASS=0
        FAIL=0
        for i in $(seq 1 $NODE_COUNT); do
            NODE_NUM=$((i + 1))
            SSH_PORT=$((BASE_SSH_PORT + i - 1))
            if sshpass -p "$SSH_PASSWORD" ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 -p $SSH_PORT $SSH_USER@127.0.0.1 'echo ok' &>/dev/null; then
                info "node-$(printf '%02d' $NODE_NUM) (SSH port $SSH_PORT): OK"
                PASS=$((PASS + 1))
            else
                error "node-$(printf '%02d' $NODE_NUM) (SSH port $SSH_PORT): FAILED"
                FAIL=$((FAIL + 1))
            fi
        done
        echo ""
        info "Results: $PASS passed, $FAIL failed out of $NODE_COUNT"
        ;;

    help|*)
        echo "Usage: $0 {build|up|down|status|test-ssh}"
        echo ""
        echo "  build     - Build the Docker image"
        echo "  up        - Start all 23 node containers"
        echo "  down      - Stop and remove all containers"
        echo "  status    - Show container status"
        echo "  test-ssh  - Test SSH connectivity to all nodes"
        ;;
esac
