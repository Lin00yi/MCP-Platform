# MCP 客户端接入指南

> 面向：Codex CLI / WorkBuddy / Cursor / MCP Inspector 等 MCP 客户端
> 适用范围：Endpoint 数据面 `/mcp/e/{endpointKey}`、发现接口 `/mcp/discovery/**`
> 认证基线：只认 `x-dsh-auth-token`（`dsh_` / PAT）；**不再有应用 token / access-token**。

---

## 1. 端点与传输

| 项 | 值 |
|---|---|
| 传输协议 | **Streamable HTTP**（MCP 2025-03-26） |
| 唯一对外端点 | `http://<host>:8081/mcp/e/<endpointKey>` |
| 会话 | `initialize` 响应头返回 `Mcp-Session-Id`，后续请求需带回（SDK 自动处理） |
| stdio | ❌ 网关不提供；只支持 stdio 的客户端需自行加 stdio↔HTTP 桥接 |

> `/mcp/v1/{serverPath}` 已下线，未注册路径返回 404。
> 客户端只通过 Endpoint 接入；发现接口返回当前用户可见的 Endpoint，
> 工具名格式 `{namespacePrefix}__{tool}`。
> 详见 [Endpoint统一接入.md](Endpoint统一接入.md)。

---

## 2. 鉴权

| 头 | 说明 |
|---|---|
| `x-dsh-auth-token: <dsh_/PAT>` | **唯一认证方式**；缺失/无效/过期返回 401 |

- 必带该头的方法：`initialize`、`tools/*`、`prompts/*`、`resources/*`、`/mcp/discovery/**`、
  `/mcp/e/{key}`（含 SSE / DELETE）。
- `ping`、`notifications/*` 免认证。
- 认证错误语义：
  - `401`：缺 token / token 无效或过期（`WWW-Authenticate: Bearer realm="mcp"`）
  - `503`：用户 token 校验存储不可用（user-center 未接入或故障），**不是** token 失效；客户端应退避重试
  - `403`：Endpoint 不存在/已停用
- 不再断言 tenant；网关只产出 `userId/customerId`，数据范围由下游按用户/客编处理。
- 网关调用下游 MCP Server 时，按需带内部 JWS/JWE 断言（`sub`、`cust`、`exp`、`jti`），
  不再包含 `app` / `tid`。

### 2.1 token 从哪来

| 客户端 | 凭证 |
|---|---|
| DSH 桌面端 | user-center 登录后签发的 `dsh_` token |
| Codex / Cursor / CLI 脚本 | 用户在 user-center 生成的 PAT（个人访问令牌） |

两者都由 user-center 签发/吊销；网关只负责校验。

---

## 3. Codex CLI 接入

`~/.codex/config.toml`：

```toml
experimental_use_rmcp_client = true

[mcp_servers.mcp-gateway]
type = "streamable-http"
url  = "http://your-host:8081/mcp/e/<endpointKey>"

[mcp_servers.mcp-gateway.http_headers]
x-dsh-auth-token = "<dsh_/PAT>"
```

> 不要再用 `Authorization: Bearer <JWT>` 或 `bearer_token_env_var`；网关只读 `x-dsh-auth-token`。
> 若客户端无法自定义 header，用 stdio 桥接注入该头（参见 `tools/codex-pcb-bridge.sh`，
> 脚本内 `MCP_SERVER_URL` 指向 `/mcp/e/<endpointKey>`）。

---

## 4. WorkBuddy 接入

WorkBuddy 的 MCP 配置在 `~/.workbuddy/mcp.json`（用户级）或 `<项目>/.workbuddy/mcp.json`（项目级）：

- 若支持远程 HTTP（`url` + `headers`），直接填 `/mcp/e/<endpointKey>` 与 `x-dsh-auth-token`
- 若只支持 stdio，需要用 stdio ↔ Streamable HTTP 桥接进程，由桥接进程发送
  `x-dsh-auth-token`（社区 `mcp-proxy` / `supergateway` 均可）

---

## 5. 手工验证（不依赖客户端）

```bash
ENDPOINT_KEY=<endpointKey>
TOKEN=<dsh_/PAT>

# 1) initialize 拿会话
SID=$(curl -s -D - -o /dev/null -X POST "http://localhost:8081/mcp/e/$ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H "x-dsh-auth-token: $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}' \
  | grep -i mcp-session-id | tr -d '\r' | awk '{print $2}')

# 2) 查看工具
curl -s -X POST "http://localhost:8081/mcp/e/$ENDPOINT_KEY" \
  -H 'Content-Type: application/json' -H "Mcp-Session-Id: $SID" \
  -H "x-dsh-auth-token: $TOKEN" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 3) 调用工具（name 用 tools/list 返回的暴露名，形如 pcb__orders_get）
curl -s -X POST "http://localhost:8081/mcp/e/$ENDPOINT_KEY" \
  -H 'Content-Type: application/json' -H "Mcp-Session-Id: $SID" \
  -H "x-dsh-auth-token: $TOKEN" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"pcb__orders_get","arguments":{"orderNo":"A1"}}}'
```

`tools/list` 返回的 `name` 是**暴露名**（带 Namespace 前缀）；`tools/call` 必须使用同一个名字，
不能只传 Tool 的原始 `code`。

---

## 6. 工具较多时的两种返回形态

服务端按 Server 配置返回两种形态，客户端应都能处理：

### 分页（DIRECT + 超过页大小）

响应带 `nextCursor`，把它原样放进下一次请求的 `params.cursor`：

```bash
# 第一页
curl ... -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
# → {"result":{"tools":[...],"nextCursor":"djE6MjAw"}}

# 下一页
curl ... -d '{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{"cursor":"djE6MjAw"}}'
```

> 部分客户端不会自动翻页，只会看到第一页。接入前确认客户端支持 `nextCursor`；
> 也可以拆小 Server，或启用 META 模式避免大列表。

### 元工具（META）

`tools/list` 里会出现四个内置工具（通常伴随少量热工具）：

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `search_tools` | 检索或按业务分组列目录，返回 name/description/tags（不含 schema） | `query?`、`domain?`、`tags?`、`server_id?`、`top_k?` |
| `get_tool_schema` | 查看工具参数定义（默认精简版） | `name?`、`tool_codes?`、`detail_level?` |
| `call_tool` | 按名字执行真实工具 | `name`、`arguments`、`reason?` |
| `read_result_chunk` | 分段读取被截断的超长结果 | `call_id`、`offset`、`limit?` |

推荐顺序：`search_tools` → `get_tool_schema`（不确定参数时）→ `call_tool`；
结果被截断时用 `read_result_chunk` 继续读取。已知工具名可直接 `get_tool_schema` / `call_tool`。

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call",
 "params":{"name":"search_tools","arguments":{"query":"订单 查询","domain":"crm","top_k":10}}}

{"jsonrpc":"2.0","id":5,"method":"tools/call",
 "params":{"name":"call_tool","arguments":{"name":"order_query","arguments":{"orderNo":"A1"}}}}
```

- 可见性：应用授权已移除，Server 下所有启用工具对已认证用户可见。
- 工具级错误（目标不存在、参数不合法）以 `result.isError=true` + 文本返回，模型可直接读取。
- 元工具只在 Server 配置为 `META` 时出现；`DIRECT` 模式下调用会得到“工具不存在”。

---

## 7. 发现接口（可选）

| 接口 | 说明 |
|---|---|
| `GET /mcp/discovery/servers` | 当前用户可见的 **Endpoint** 目录（含 `url=/mcp/e/{key}`、`toolCount`、`revision`） |
| `GET /mcp/discovery/tools` | 可见 Tool 目录（跨 Endpoint 检索） |
| `GET /mcp/discovery/prompts` | 可见 Prompt 目录 |
| `GET /mcp/discovery/resources` | 可见 Resource 目录 |
| `GET /mcp/discovery/servers/{id}/health` | 健康探测 |

- 必须带 `x-dsh-auth-token`；无 token / token 无效一律 401。
- `GET /mcp/discovery/servers` 支持 `ETag` / `If-None-Match`，未变化返回 304。

---

## 8. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `initialize` 返回 401 | 没带/带错 `x-dsh-auth-token` | 检查头名与 token 是否被吊销 |
| 所有受保护请求返回 503 | user-center `UserTokenStore` 未接入或不可用 | 联调 user-center；本地/E2E 可用 `MCP_AUTH_STATIC_TOKENS` 临时验证 |
| `tools/list` 返回 `INVALID_REQUEST` | 缺 `Mcp-Session-Id` | 先 `initialize` 再携带会话 |
| `tools/call` 返回“工具不存在” | 用了原始 `code` 而不是暴露名 | 用 `tools/list` 返回的 `{prefix}__{code}` |
| `tools/list` 返回 `_meta.overWarn=true` | 可见工具数超过告警阈值 | 启用 META 模式，或按业务域拆分 Server |
| 只看到部分工具 | 客户端未跟随 `nextCursor` 分页 | 确认客户端支持分页；或调大 `MCP_TOOLS_PAGE_SIZE` / 拆分 Server |
| 旧地址 `/mcp/v1/...` 返回 404 | 旧链路已下线 | 改用 `/mcp/e/{endpointKey}` |
| 下游收到 401 | 身份下传 REQUIRED 但下游未验签 | 下游接入 `McpIdentityVerifier` / `GatewayOriginVerifier`，或先用 OPTIONAL 灰度 |
