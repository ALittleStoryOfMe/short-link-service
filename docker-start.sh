#!/usr/bin/env bash
# =============================================================================
# 短链微服务 Docker 管理脚本
# 适用环境：安装了 Docker（>= 20.x）的 Linux 主机
#
# 用法：
#   ./docker-start.sh build          仅构建镜像
#   ./docker-start.sh start          构建镜像并启动容器（后台）
#   ./docker-start.sh stop           停止并删除容器
#   ./docker-start.sh restart        重启容器（重新构建镜像）
#   ./docker-start.sh logs           实时查看容器日志
#   ./docker-start.sh status         查看容器状态
#   ./docker-start.sh clean          停止容器并删除镜像
#   ./docker-start.sh help           显示帮助
# =============================================================================

set -euo pipefail

# -------------------------------------------------------------------
# 可配置变量（可通过环境变量覆盖）
# -------------------------------------------------------------------
IMAGE_NAME="${IMAGE_NAME:-short-link-service}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
CONTAINER_NAME="${CONTAINER_NAME:-short-link-service}"
HOST_PORT="${HOST_PORT:-8080}"
CONTAINER_PORT="8080"

# 容器资源限制
MEM_LIMIT="${MEM_LIMIT:-512m}"
CPU_LIMIT="${CPU_LIMIT:-1.0}"

# 日志目录（宿主机路径，挂载到容器 /app/logs）
LOG_DIR="${LOG_DIR:-$(pwd)/logs}"

FULL_IMAGE="${IMAGE_NAME}:${IMAGE_TAG}"

# -------------------------------------------------------------------
# 颜色输出
# -------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
title()   { echo -e "\n${BOLD}${CYAN}>>> $* ${NC}"; }

# -------------------------------------------------------------------
# 环境检查
# -------------------------------------------------------------------
check_docker() {
  if ! command -v docker &>/dev/null; then
    error "未找到 docker 命令，请先安装 Docker（>= 20.x）"
    error "安装参考: https://docs.docker.com/engine/install/"
    exit 1
  fi
  if ! docker info &>/dev/null; then
    error "Docker 守护进程未运行，请执行: sudo systemctl start docker"
    exit 1
  fi
  info "Docker 版本: $(docker --version)"
}

# -------------------------------------------------------------------
# 检查 Dockerfile 是否存在
# -------------------------------------------------------------------
check_dockerfile() {
  if [[ ! -f "Dockerfile" ]]; then
    error "当前目录下未找到 Dockerfile，请在项目根目录执行本脚本"
    exit 1
  fi
}

# -------------------------------------------------------------------
# 构建镜像（多阶段构建，第一次会下载依赖，后续走缓存）
# -------------------------------------------------------------------
cmd_build() {
  title "构建 Docker 镜像"
  check_dockerfile

  info "镜像名称: ${FULL_IMAGE}"
  info "开始构建（首次构建需下载依赖，约 2~5 分钟）..."

  docker build \
    --tag "${FULL_IMAGE}" \
    --label "build-time=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    .

  success "镜像构建完成: ${FULL_IMAGE}"
  echo ""
  docker images "${IMAGE_NAME}" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}\t{{.CreatedSince}}"
}

# -------------------------------------------------------------------
# 启动容器（后台运行）
# -------------------------------------------------------------------
cmd_start() {
  title "启动容器"

  # 镜像不存在时自动构建
  if ! docker image inspect "${FULL_IMAGE}" &>/dev/null; then
    warn "镜像 ${FULL_IMAGE} 不存在，先执行构建..."
    cmd_build
  fi

  # 若容器已在运行，提示先停止
  if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    warn "容器 ${CONTAINER_NAME} 已在运行"
    warn "如需重启请执行: $0 restart"
    return
  fi

  # 若容器已停止（未删除），先删除
  if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    info "删除已停止的旧容器..."
    docker rm "${CONTAINER_NAME}"
  fi

  # 创建宿主机日志目录
  mkdir -p "${LOG_DIR}"

  info "启动容器: ${CONTAINER_NAME}"
  info "端口映射: 宿主机 ${HOST_PORT} → 容器 ${CONTAINER_PORT}"
  info "内存限制: ${MEM_LIMIT}   CPU 限制: ${CPU_LIMIT}"
  info "日志挂载: ${LOG_DIR} → /app/logs"

  docker run \
    --detach \
    --name "${CONTAINER_NAME}" \
    --publish "${HOST_PORT}:${CONTAINER_PORT}" \
    --memory "${MEM_LIMIT}" \
    --cpus "${CPU_LIMIT}" \
    --volume "${LOG_DIR}:/app/logs" \
    --env "SERVER_PORT=${CONTAINER_PORT}" \
    --restart unless-stopped \
    "${FULL_IMAGE}"

  success "容器已启动，等待服务就绪..."
  echo ""

  # 等待健康检查通过（最多 60s）
  local waited=0
  local max_wait=60
  while (( waited < max_wait )); do
    local health
    health=$(docker inspect --format='{{.State.Health.Status}}' "${CONTAINER_NAME}" 2>/dev/null || echo "none")
    if [[ "$health" == "healthy" ]]; then
      success "服务已就绪（健康检查通过）"
      break
    elif [[ "$health" == "unhealthy" ]]; then
      warn "健康检查失败，请查看日志: $0 logs"
      break
    fi
    printf "."
    sleep 3
    waited=$(( waited + 3 ))
  done
  echo ""

  echo ""
  info "常用命令："
  echo "  查看日志:   $0 logs"
  echo "  查看状态:   $0 status"
  echo "  停止服务:   $0 stop"
  echo "  接口测试:   curl http://localhost:${HOST_PORT}/api/short-link/query"
}

# -------------------------------------------------------------------
# 停止并删除容器
# -------------------------------------------------------------------
cmd_stop() {
  title "停止容器"

  if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    warn "容器 ${CONTAINER_NAME} 不存在或已删除"
    return
  fi

  if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    info "正在停止容器 ${CONTAINER_NAME}..."
    docker stop "${CONTAINER_NAME}"
  fi

  info "删除容器 ${CONTAINER_NAME}..."
  docker rm "${CONTAINER_NAME}"
  success "容器已停止并删除"
}

# -------------------------------------------------------------------
# 重启（重新构建 + 启动）
# -------------------------------------------------------------------
cmd_restart() {
  title "重启容器"
  cmd_stop  || true
  cmd_build
  cmd_start
}

# -------------------------------------------------------------------
# 实时查看日志
# -------------------------------------------------------------------
cmd_logs() {
  if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    error "容器 ${CONTAINER_NAME} 未运行"
    exit 1
  fi
  info "实时日志（Ctrl+C 退出）..."
  docker logs --follow --tail 100 "${CONTAINER_NAME}"
}

# -------------------------------------------------------------------
# 查看容器状态
# -------------------------------------------------------------------
cmd_status() {
  title "容器状态"

  if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    warn "容器 ${CONTAINER_NAME} 不存在"
    return
  fi

  echo ""
  docker ps -a \
    --filter "name=^${CONTAINER_NAME}$" \
    --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}\t{{.Image}}"

  echo ""
  # 显示资源占用（仅运行中容器）
  if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    info "资源占用（实时）："
    docker stats "${CONTAINER_NAME}" --no-stream \
      --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"

    echo ""
    local health
    health=$(docker inspect --format='{{.State.Health.Status}}' "${CONTAINER_NAME}" 2>/dev/null || echo "N/A")
    info "健康状态: ${health}"

    echo ""
    info "快速接口验证："
    curl -sf "http://localhost:${HOST_PORT}/api/short-link/query" | python3 -m json.tool 2>/dev/null \
      || curl -sf "http://localhost:${HOST_PORT}/api/short-link/query" \
      || warn "接口暂未响应（服务可能仍在启动中）"
  fi
}

# -------------------------------------------------------------------
# 停止容器并删除镜像（清理）
# -------------------------------------------------------------------
cmd_clean() {
  title "清理容器与镜像"
  cmd_stop || true

  if docker image inspect "${FULL_IMAGE}" &>/dev/null; then
    info "删除镜像 ${FULL_IMAGE}..."
    docker rmi "${FULL_IMAGE}"
    success "镜像已删除"
  else
    warn "镜像 ${FULL_IMAGE} 不存在，无需清理"
  fi
}

# -------------------------------------------------------------------
# 帮助信息
# -------------------------------------------------------------------
cmd_help() {
  echo ""
  echo -e "${BOLD}短链微服务 Docker 管理脚本${NC}"
  echo ""
  echo -e "${BOLD}用法:${NC} $0 <命令> [环境变量]"
  echo ""
  echo -e "${BOLD}命令:${NC}"
  printf "  %-12s %s\n" "build"   "仅构建 Docker 镜像"
  printf "  %-12s %s\n" "start"   "构建镜像（若不存在）并启动容器"
  printf "  %-12s %s\n" "stop"    "停止并删除容器"
  printf "  %-12s %s\n" "restart" "重新构建镜像并重启容器"
  printf "  %-12s %s\n" "logs"    "实时跟踪容器日志"
  printf "  %-12s %s\n" "status"  "查看容器状态与资源占用"
  printf "  %-12s %s\n" "clean"   "停止容器并删除镜像"
  printf "  %-12s %s\n" "help"    "显示本帮助"
  echo ""
  echo -e "${BOLD}环境变量（均有默认值）:${NC}"
  printf "  %-20s %s  (当前: %s)\n" "IMAGE_NAME"      "镜像名称"    "${IMAGE_NAME}"
  printf "  %-20s %s  (当前: %s)\n" "IMAGE_TAG"       "镜像标签"    "${IMAGE_TAG}"
  printf "  %-20s %s  (当前: %s)\n" "CONTAINER_NAME"  "容器名称"    "${CONTAINER_NAME}"
  printf "  %-20s %s  (当前: %s)\n" "HOST_PORT"       "宿主机端口"  "${HOST_PORT}"
  printf "  %-20s %s  (当前: %s)\n" "MEM_LIMIT"       "内存限制"    "${MEM_LIMIT}"
  printf "  %-20s %s  (当前: %s)\n" "CPU_LIMIT"       "CPU 限制"    "${CPU_LIMIT}"
  printf "  %-20s %s  (当前: %s)\n" "LOG_DIR"         "日志目录"    "${LOG_DIR}"
  echo ""
  echo -e "${BOLD}示例:${NC}"
  echo "  $0 start                              # 默认配置启动"
  echo "  HOST_PORT=9090 $0 start               # 映射到宿主机 9090 端口"
  echo "  MEM_LIMIT=1g CPU_LIMIT=2 $0 restart   # 调整资源限制后重启"
  echo "  IMAGE_TAG=v1.1 $0 build               # 构建指定版本镜像"
  echo ""
}

# -------------------------------------------------------------------
# 主入口
# -------------------------------------------------------------------
main() {
  echo ""
  echo -e "${BOLD}${CYAN}=================================================${NC}"
  echo -e "${BOLD}${CYAN}   短链微服务 Docker 管理脚本${NC}"
  echo -e "${BOLD}${CYAN}=================================================${NC}"

  check_docker

  local cmd="${1:-help}"
  case "$cmd" in
    build)   cmd_build   ;;
    start)   cmd_start   ;;
    stop)    cmd_stop    ;;
    restart) cmd_restart ;;
    logs)    cmd_logs    ;;
    status)  cmd_status  ;;
    clean)   cmd_clean   ;;
    help|--help|-h) cmd_help ;;
    *)
      error "未知命令: $cmd"
      cmd_help
      exit 1
      ;;
  esac
}

main "$@"
