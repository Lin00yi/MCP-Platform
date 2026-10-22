#!/usr/bin/env bash
#
# Codex / 任意 stdio MCP 客户端 → MCP Gateway Streamable HTTP 桥接
#
# 用法：
#   1. 配置 $HOME/.config/pcb-codex/env（或 PCB_CODEX_ENV 指向的文件）：
#        MCP_USER_TOKEN=<dsh_ 或 PAT>              # 唯一认证凭证
#        MCP_SERVER_URL=http://127.0.0.1:8081/mcp/v1/pcb-server
#   2. Codex 自定义 MCP：
#        类型：STDIO
#        启动命令：/bin/bash
#        参数：-lc /Users/lin/Downloads/MCP-Gateway/tools/codex-pcb-bridge.sh
#        工作目录：/Users/lin/Downloads/MCP-Gateway
#
# 网关只认 x-dsh-auth-token；不再使用 appId/app_secret 签 JWT。
set -euo pipefail

# GUI 客户端（Codex 桌面版）可能拿不到 nvm/Homebrew 的 PATH，这里补齐。
# 优先 nvm 的 Node（版本较新），再保留原 PATH，最后补常见安装目录。
if [[ -d "$HOME/.nvm/versions/node" ]]; then
  NODE_BIN="$(ls -1d "$HOME"/.nvm/versions/node/*/bin 2>/dev/null | tail -1 || true)"
  [[ -n "$NODE_BIN" ]] && export PATH="$NODE_BIN:$PATH"
fi
export PATH="$PATH:/opt/homebrew/bin:/usr/local/bin"
if ! command -v node >/dev/null 2>&1 || ! command -v npx >/dev/null 2>&1; then
  echo "未找到 npx，请安装 Node.js 或把 Node 加入 PATH" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# 优先级：显式 PCB_CODEX_ENV → 仓库内 .codex → 用户目录 → /tmp
ENV_FILE="${PCB_CODEX_ENV:-$REPO_ROOT/mcp-gateway/.codex/pcb-codex.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  ENV_FILE="$HOME/.config/pcb-codex/env"
fi
if [[ ! -f "$ENV_FILE" ]]; then
  ENV_FILE="/tmp/pcb-codex.env"
fi
if [[ ! -f "$ENV_FILE" ]]; then
  echo "缺少应用配置：$ENV_FILE" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$ENV_FILE"

: "${MCP_USER_TOKEN:?MCP_USER_TOKEN 未配置（dsh_ 或 PAT）}"
MCP_SERVER_URL="${MCP_SERVER_URL:-http://127.0.0.1:8081/mcp/v1/pcb-server}"

# 某些机器的 ~/.npm 存在 root 权限问题，使用可写缓存避免 EPERM
export npm_config_cache="${npm_config_cache:-/tmp/mcp-npm-cache}"

# stdout 必须只输出 MCP 协议帧；supergateway 的日志写 stderr
HEADER_ARGS=(--header "x-dsh-auth-token: $MCP_USER_TOKEN")

exec npx -y supergateway \
  --streamableHttp "$MCP_SERVER_URL" \
  "${HEADER_ARGS[@]}" \
  --logLevel none
