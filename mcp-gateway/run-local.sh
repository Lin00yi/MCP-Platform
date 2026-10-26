#!/usr/bin/env bash
# 本地开发启动：复用 .env 中的密钥，数据库默认指向本机 MySQL 的 3306。
#
# 拆分后有两个服务，必须同时运行：
#   ./run-local.sh dataplane    # S1 数据面 :8081（/mcp/e、/mcp/discovery）
#   ./run-local.sh admin        # S2 控制面 :8082（/mcp/admin、/mcp/log）
#   ./run-local.sh both         # 两个都起（前台并行，Ctrl-C 一起退出）
set -euo pipefail
cd "$(dirname "$0")"
if [[ -f .env ]]; then
  set -a
  . ./.env
  set +a
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export MCP_DB_URL="${MCP_DB_URL:-jdbc:mysql://127.0.0.1:3306/mcp_gateway?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}"
export MCP_DB_USERNAME="${MCP_DB_USERNAME:-${MYSQL_USER:?MYSQL_USER 未配置}}"
export MCP_DB_PASSWORD="${MCP_DB_PASSWORD:-${MYSQL_PASSWORD:?MYSQL_PASSWORD 未配置}}"

# 控制面 → 数据面的内部调用：本地默认回环，未设 token 时数据面只接受回环访问
export MCP_INTERNAL_TOKEN="${MCP_INTERNAL_TOKEN:-local-dev-internal-token}"
export MCP_DATAPLANE_URL="${MCP_DATAPLANE_URL:-http://127.0.0.1:8081}"

ROOT="$(cd "$(dirname "$0")" && pwd)"
DATAPLANE_JAR="$ROOT/mcp-dataplane/target/mcp-dataplane.jar"
ADMIN_JAR="$ROOT/mcp-admin/target/mcp-admin.jar"

run_dataplane() {
  SERVER_PORT="${MCP_DATAPLANE_PORT:-8081}" exec java ${JAVA_OPTS:-} -jar "$DATAPLANE_JAR" "$@"
}

run_admin() {
  SERVER_PORT="${MCP_ADMIN_PORT:-8082}" exec java ${JAVA_OPTS:-} -jar "$ADMIN_JAR" "$@"
}

case "${1:-both}" in
  dataplane)
    shift || true
    run_dataplane "$@"
    ;;
  admin)
    shift || true
    run_admin "$@"
    ;;
  both)
    shift || true
    SERVER_PORT="${MCP_DATAPLANE_PORT:-8081}" java ${JAVA_OPTS:-} -jar "$DATAPLANE_JAR" "$@" &
    DP_PID=$!
    SERVER_PORT="${MCP_ADMIN_PORT:-8082}" java ${JAVA_OPTS:-} -jar "$ADMIN_JAR" "$@" &
    ADMIN_PID=$!
    trap 'kill $DP_PID $ADMIN_PID 2>/dev/null || true' INT TERM
    wait
    ;;
  *)
    echo "用法: $0 [dataplane|admin|both]" >&2
    exit 1
    ;;
esac
