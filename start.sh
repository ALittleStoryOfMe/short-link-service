#!/usr/bin/env bash
# =============================================================================
# 短链微服务启动脚本
# 适用环境：Linux + JDK 8 + Maven 3.x
# 用法：
#   ./start.sh            # 构建并前台运行
#   ./start.sh --daemon   # 构建并后台运行（日志写入 logs/app.log）
#   ./start.sh --skip-build --daemon  # 跳过构建，直接后台启动
# =============================================================================

set -euo pipefail

# -------------------------------------------------------------------
# 可配置变量
# -------------------------------------------------------------------
APP_NAME="short-link-service"
JAR_NAME="short-link-service-1.0.0-SNAPSHOT.jar"
JAR_PATH="target/${JAR_NAME}"
LOG_DIR="logs"
LOG_FILE="${LOG_DIR}/app.log"
PID_FILE="${LOG_DIR}/app.pid"
SPRING_PORT="${SERVER_PORT:-8080}"

# JVM 参数（可按需调整堆大小）
JVM_OPTS="-server \
  -Xms256m \
  -Xmx512m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8"

# -------------------------------------------------------------------
# 颜色输出
# -------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# -------------------------------------------------------------------
# 解析命令行参数
# -------------------------------------------------------------------
DAEMON=false
SKIP_BUILD=false

for arg in "$@"; do
  case "$arg" in
    --daemon)      DAEMON=true ;;
    --skip-build)  SKIP_BUILD=true ;;
    --help|-h)
      echo "用法: $0 [--daemon] [--skip-build]"
      echo "  --daemon      后台运行，日志写入 ${LOG_FILE}"
      echo "  --skip-build  跳过 Maven 构建，直接使用已有 JAR"
      exit 0
      ;;
    *)
      error "未知参数: $arg  （使用 --help 查看帮助）"
      exit 1
      ;;
  esac
done

# -------------------------------------------------------------------
# 环境检查
# -------------------------------------------------------------------
check_env() {
  info "检查运行环境..."

  # 检查 Java
  if ! command -v java &>/dev/null; then
    error "未找到 java 命令，请安装 JDK 8 并配置 PATH"
    exit 1
  fi
  local java_ver
  java_ver=$(java -version 2>&1 | head -1)
  info "Java 版本: ${java_ver}"

  # 检查 Maven（跳过构建时不需要）
  if [[ "$SKIP_BUILD" == false ]]; then
    if ! command -v mvn &>/dev/null; then
      error "未找到 mvn 命令，请安装 Maven 3.x 并配置 PATH"
      exit 1
    fi
    local mvn_ver
    mvn_ver=$(mvn -version 2>&1 | head -1)
    info "Maven 版本: ${mvn_ver}"
  fi
}

# -------------------------------------------------------------------
# Maven 构建
# -------------------------------------------------------------------
build() {
  if [[ "$SKIP_BUILD" == true ]]; then
    warn "已跳过 Maven 构建（--skip-build）"
    return
  fi

  info "开始 Maven 构建（跳过单元测试以加速启动）..."
  if mvn clean package -DskipTests -B -q; then
    success "Maven 构建成功 → ${JAR_PATH}"
  else
    error "Maven 构建失败，请检查编译错误"
    exit 1
  fi
}

# -------------------------------------------------------------------
# 检查 JAR 是否存在
# -------------------------------------------------------------------
check_jar() {
  if [[ ! -f "$JAR_PATH" ]]; then
    error "JAR 文件不存在: ${JAR_PATH}"
    error "请先执行构建或去掉 --skip-build 参数"
    exit 1
  fi
}

# -------------------------------------------------------------------
# 检查端口是否被占用
# -------------------------------------------------------------------
check_port() {
  if command -v ss &>/dev/null; then
    if ss -tlnp 2>/dev/null | grep -q ":${SPRING_PORT} "; then
      warn "端口 ${SPRING_PORT} 已被占用，服务可能启动失败"
      warn "可通过 SERVER_PORT=xxxx ./start.sh 指定其他端口"
    fi
  elif command -v netstat &>/dev/null; then
    if netstat -tlnp 2>/dev/null | grep -q ":${SPRING_PORT} "; then
      warn "端口 ${SPRING_PORT} 已被占用"
    fi
  fi
}

# -------------------------------------------------------------------
# 停止已运行的旧进程
# -------------------------------------------------------------------
stop_old() {
  if [[ -f "$PID_FILE" ]]; then
    local old_pid
    old_pid=$(cat "$PID_FILE")
    if kill -0 "$old_pid" 2>/dev/null; then
      warn "发现旧进程 PID=${old_pid}，正在停止..."
      kill "$old_pid"
      local timeout=10
      while kill -0 "$old_pid" 2>/dev/null && (( timeout-- > 0 )); do
        sleep 1
      done
      if kill -0 "$old_pid" 2>/dev/null; then
        warn "旧进程未在 10s 内退出，强制 kill -9"
        kill -9 "$old_pid" 2>/dev/null || true
      fi
      success "旧进程已停止"
    else
      warn "PID 文件存在但进程已不在运行，清理旧 PID 文件"
    fi
    rm -f "$PID_FILE"
  fi
}

# -------------------------------------------------------------------
# 启动服务
# -------------------------------------------------------------------
start() {
  mkdir -p "$LOG_DIR"

  local java_cmd="java ${JVM_OPTS} \
    -Dserver.port=${SPRING_PORT} \
    -jar ${JAR_PATH}"

  if [[ "$DAEMON" == true ]]; then
    # 后台模式：nohup 运行，stdout/stderr 追加到日志文件
    info "以后台模式启动 ${APP_NAME}，端口: ${SPRING_PORT}"
    info "日志输出: ${LOG_FILE}"
    # shellcheck disable=SC2086
    nohup $java_cmd >> "$LOG_FILE" 2>&1 &
    local pid=$!
    echo "$pid" > "$PID_FILE"
    success "服务已在后台启动，PID=${pid}"
    echo ""
    info "常用运维命令："
    echo "  查看日志:   tail -f ${LOG_FILE}"
    echo "  查看状态:   kill -0 \$(cat ${PID_FILE}) && echo running"
    echo "  停止服务:   kill \$(cat ${PID_FILE})"
    echo "  接口测试:   curl http://localhost:${SPRING_PORT}/api/short-link/query"
  else
    # 前台模式：直接运行，Ctrl+C 退出
    info "以前台模式启动 ${APP_NAME}，端口: ${SPRING_PORT}"
    info "按 Ctrl+C 停止服务"
    echo ""
    # shellcheck disable=SC2086
    exec $java_cmd
  fi
}

# -------------------------------------------------------------------
# 主流程
# -------------------------------------------------------------------
main() {
  echo ""
  echo -e "${CYAN}=================================================${NC}"
  echo -e "${CYAN}   短链微服务启动脚本 - ${APP_NAME}${NC}"
  echo -e "${CYAN}=================================================${NC}"
  echo ""

  check_env
  build
  check_jar
  check_port
  stop_old
  start
}

main
