#!/usr/bin/env bash
#
# Codex CLI 统一启动脚本（本地 dev）
#
# 解决三个常见问题：
#   1. codex exec 默认审批策略会拒绝 MCP 工具调用 -> --approve-for-me
#   2. CLI 不自动读取系统代理 -> 显式 HTTP(S)_PROXY
#   3. 静态 token 过期 -> 优先从 token 文件读取，可配合定时刷新
#
# 用法：
#   MCP_CODEX_TOKEN_FILE=~/.codex/mcp-gateway.token ./tools/codex-run.sh "用 demo_pcb_server 的 add 计算 1+2"
#   MCP_CODEX_TOKEN_FILE=... ./tools/codex-run.sh --json "..."
#
# token 文件内容：一行 Bearer token（可由 tools/gen-mcp-app-token.py 生成）
# 代理默认 127.0.0.1:7897，可用 MCP_CODEX_PROXY 覆盖；设为空字符串可关闭。
set -euo pipefail

CODEX_BIN="${CODEX_BIN:-/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex}"
if [[ ! -x "$CODEX_BIN" ]]; then
  CODEX_BIN="$(command -v codex || true)"
fi
if [[ -z "$CODEX_BIN" || ! -x "$CODEX_BIN" ]]; then
  echo "未找到 codex CLI，请设置 CODEX_BIN 或把 codex 加入 PATH" >&2
  exit 1
fi

PROXY="${MCP_CODEX_PROXY-http://127.0.0.1:7897}"
if [[ -n "$PROXY" ]]; then
  export HTTP_PROXY="$PROXY"
  export HTTPS_PROXY="$PROXY"
  export ALL_PROXY="$PROXY"
fi
export NO_PROXY="${NO_PROXY:-127.0.0.1,localhost}"

if [[ -n "${MCP_CODEX_TOKEN_FILE:-}" ]]; then
  if [[ ! -f "$MCP_CODEX_TOKEN_FILE" ]]; then
    echo "token 文件不存在: $MCP_CODEX_TOKEN_FILE" >&2
    exit 1
  fi
  export MCP_TOKEN="$(tr -d '\r\n' < "$MCP_CODEX_TOKEN_FILE")"
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$CODEX_BIN" exec \
  --approve-for-me \
  --skip-git-repo-check \
  -C "$REPO_ROOT" \
  "$@"
