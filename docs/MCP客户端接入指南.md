# MCP 客户端接入指南

> 面向：Codex CLI / WorkBuddy / Cursor / MCP Inspector 等 MCP 客户端
> 适用范围：`/mcp/v1/{serverPath}` 数据面端点、`/mcp/discovery/**` 发现端点

---

## 1. 端点与传输

| 项 | 值 |
|---|---|
| 传输协议 | **Streamable HTTP**（MCP 2025-03-26） |
| 端点（Endpoint） | `http://<host>:8081/mcp/e/<endpointKey>` |
| 端点（单 Server，直连/排障） | `http://<host>:8081/mcp/<serverKey>/v1` |
| 会话 | `initialize` 响应头返回 `Mcp-Session-Id`，后续请求需带回（SDK 自动处理） |
| stdio | ❌ 不提供（如客户端只支持 stdio，需要 stdio↔HTTP 桥接） |

> 客户端只通过 Endpoint 接入。发现接口返回当前应用已授权的 Endpoint，
> 每个 Endpoint 对应 `/mcp/e/<endpointKey>`；工具名格式
> `{namespacePrefix}__{tool}`。没有 Endpoint 授权时不会返回任何地址。
> 详见 [Endpoint统一接入.md](Endpoint统一接入.md)。

---

## 2. 鉴权（2026-10 起）

> **只认 `x-dsh-auth-token`**。`access-token`、`Authorization: Bearer <app JWT>` 已移除；
> 应用级授权（`app_server_rel` / `app_tool_rel` / `app_endpoint_rel`）已移除。

| 头 | 说明 |
|---|---|
| `x-dsh-auth-token: <dsh_/PAT>` | **唯一认证方式**；缺失/无效/过期返回 401 |

- 必带该头的方法：`initialize`、`tools/*`、`prompts/*`、`resources/*`、`/mcp/discovery/**`、
  `/mcp/e/{key}`（含 SSE / DELETE）。
- `ping`、`notifications/*` 免认证。
- 认证错误语义：
  - `401`：缺 token / token 无效或过期（`WWW-Authenticate: Bearer realm="mcp"`）
  - `503`：user-center 校验服务不可用，**不是** token 失效；客户端应退避重试，不要删 token
  - `403`：Endpoint 或资产不存在/已停用（不再有"应用无权"的 403）
- **不再断言 tenant**：网关只产出 `userId/customerId`；数据范围由下游按用户/客编处理。
- 网关调用下游 MCP Server 时仍会带内部 JWS 断言（`sub=userId`、`cust`、`exp`、`jti`），
  不再包含 `app` / `tid`。
- 客户端无法自定义 header 时，用 stdio 桥接（如 `tools/codex-pcb-bridge.sh`）注入该头。

### 2.1 `x-dsh-auth-token` 从哪来

| 客户端 | 凭证 |
|---|---|
| DSH 桌面端 | user-center 登录后签发的 `dsh_` token |
| Codex / Cursor / CLI 脚本 | 用户在 user-center 生成的 PAT（个人访问令牌） |

两者都由 user-center 签发/吊销；网关只负责校验，不再自签 app JWT。

---

## 3. Codex CLI 接入

`~/.codex/config.toml`：

```toml
experimental_use_rmcp_client = true      # 必须，否则 streamable-http 不生效

[mcp_servers.mcp-gateway]
type = "streamable-http"
url  = "http://your-host:8081/mcp/v1/pcb-server"
bearer_token_env_var = "MCP_TOKEN"       # 启动 codex 前 export MCP_TOKEN=<JWT>
```

```bash
export MCP_TOKEN=<上面生成的 JWT>
codex
```

> Codex 会用 `Authorization: Bearer $MCP_TOKEN` 发请求，网关已兼容。
> 若 `bearer_token_env_var` 不可用，可改用 `http_headers` 手动指定头。
> 身份下传开启时还需要 `x-dsh-auth-token`，可用 `http_headers` 同时指定；或走
> `tools/codex-pcb-bridge.sh`，在 env 文件中配置 `MCP_USER_TOKEN`。

---

## 4. WorkBuddy 接入

WorkBuddy 的 MCP 配置在 `~/.workbuddy/mcp.json`（用户级）或 `<项目>/.workbuddy/mcp.json`（项目级），
其官方文档示例是 **stdio** 形式：

```json
{
  "mcpServers": {
    "mcp-gateway": { "command": "...", "args": ["..."], "env": { "MCP_TOKEN": "<JWT>" } }
  }
}
```

- 若其配置支持远程 HTTP（`url` + `headers`），直接填本文档第 1、2 节的端点与头即可
- 若**只支持 stdio**，需要一个 stdio ↔ Streamable HTTP 桥接进程（社区有 `mcp-proxy` 类工具），
  由桥接进程去连网关并带上 `Authorization: Bearer <JWT>`

---

## 5. 手工验证（不依赖客户端）

```bash
# 1) initialize 拿会话
SID=$(curl -s -D - -o /dev/null -X POST http://localhost:8081/mcp/v1/pcb-server \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $JWT" \
  -H "x-dsh-auth-token: $DSH_USER_TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}' \
  | grep -i mcp-session-id | tr -d '\r' | awk '{print $2}')

# 2) 查看工具
curl -s -X POST http://localhost:8081/mcp/v1/pcb-server \
  -H 'Content-Type: application/json' -H "Mcp-Session-Id: $SID" \
  -H "Authorization: Bearer $JWT" \
  -H "x-dsh-auth-token: $DSH_USER_TOKEN" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 3) 调用 add(a,b)
curl -s -X POST http://localhost:8081/mcp/v1/pcb-server \
  -H 'Content-Type: application/json' -H "Mcp-Session-Id: $SID" \
  -H "Authorization: Bearer $JWT" \
  -H "x-dsh-auth-token: $DSH_USER_TOKEN" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"add","arguments":{"a":3,"b":4}}}'
# → {"result":{"content":[{"type":"text","text":"3.0 + 4.0 = 7.0"}]}}
```

---

## 5.0 统一入口（方案 C）示例

```bash
# initialize
SID=$(curl -s -D - -o /dev/null -X POST http://localhost:8081/mcp/e/<endpointKey> \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $JWT" \
  -H "x-dsh-auth-token: $DSH_USER_TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}' \
  | grep -i mcp-session-id | tr -d '\r' | awk '{print $2}')

# tools/list：name 形如 pcb__orders_get
curl -s -X POST http://localhost:8081/mcp/e/<endpointKey> \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $JWT" \
  -H "x-dsh-auth-token: $DSH_USER_TOKEN" \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# tools/call：按前缀路由回 pcb-server
curl -s -X POST http://localhost:8081/mcp/e/<endpointKey> \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $JWT" \
  -H "x-dsh-auth-token: $DSH_USER_TOKEN" \
  -H "Mcp-Session-Id: $SID" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"pcb__orders_get","arguments":{"orderNo":"A1"}}}'
```

## 5.1 工具较多时的两种返回形态

服务端可以根据 Server 配置返回两种形态，客户端应该都能处理：

### 分页（DIRECT + 超过页大小）

响应会带 `nextCursor`，把它原样放进下一次请求的 `params.cursor`：

```bash
# 第一页
curl ... -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
# → {"result":{"tools":[...200 个...],"nextCursor":"djE6MjAw"}}

# 下一页
curl ... -d '{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{"cursor":"djE6MjAw"}}'
```

> 部分客户端不会自动翻页，只会看到第一页。接入前请确认客户端支持 `nextCursor`；
> 也可以通过管理台把 Server 拆小，或启用 META 模式避免大列表。

### 元工具（META）

`tools/list` 里会出现四个内置工具（通常伴随少量热工具）：

| 工具 | 用途 | 关键参数 |
|---|---|---|
| `search_tools` | 检索或按业务分组列目录，返回 name/description/tags（不含 schema） | `query?`、`domain?`、`tags?`、`server_id?`、`top_k?` |
| `get_tool_schema` | 查看工具的参数定义（默认精简版） | `name?`、`tool_codes?`、`detail_level?` |
| `call_tool` | 按名字执行真实工具 | `name`、`arguments`、`reason?` |
| `read_result_chunk` | 分段读取被截断的超长结果 | `call_id`、`offset`、`limit?` |

推荐调用顺序：`search_tools` → `get_tool_schema`（不确定参数时）→ `call_tool`；
结果被截断时再用 `read_result_chunk` 继续读取。
如果已经知道工具名，可以直接 `get_tool_schema` / `call_tool`，不必先搜索。

```json
// 搜索（query 留空则按业务分组列目录）
{"jsonrpc":"2.0","id":4,"method":"tools/call",
 "params":{"name":"search_tools","arguments":{"query":"订单 查询","domain":"crm","top_k":10}}}

// 执行
{"jsonrpc":"2.0","id":5,"method":"tools/call",
 "params":{"name":"call_tool","arguments":{"name":"order_query","arguments":{"orderNo":"A1"}}}}
```

- `search_tools` 只返回当前应用**可见**的工具（白名单生效），不会越权。
- 工具级错误（目标不存在、未授权、参数不合法）以 `result.isError=true` + 文本返回，模型可直接读取。
- 元工具只在 Server 配置为 `META` 时出现；`DIRECT` 模式下调用会得到“工具不存在”。

### 白名单行为

- `WHITELIST` 模式的 Server：应用只能看到/调用 `app_tool_rel` 授权的工具；没有授权时 `tools/list` 为空数组。
- `ALL` 模式：忽略细粒度授权，返回 Server 下全部启用工具。
- `tools/call` 与 `tools/list` 的鉴权规则一致：即使知道工具名，未授权也会被拒绝。

---

## 6. 发现接口（可选）

| 接口 | 说明 |
|---|---|
| `GET /mcp/discovery/servers` | 该应用可见的 Server 目录 |
| `GET /mcp/discovery/tools` | 可见 Tool 目录 |
| `GET /mcp/discovery/prompts` | 可见 Prompt 目录 |
| `GET /mcp/discovery/resources` | 可见 Resource 目录 |
| `GET /mcp/discovery/servers/{id}/health` | 健康探测 |

- 必须带 `Authorization: Bearer <JWT>`（或 `access-token`）；无 token / token 无效一律 401
- 身份下传开启时，discovery 仍只校验应用 token；用户身份在具体 MCP 端点校验

---

## 7. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `tools/call` 返回 `-32001 access-token缺失或验签失败` | 没带凭证 / JWT 过期 / `iss` 非 appId / 签名密钥不匹配 | 检查头与 `app_secret` |
| `-32002 请求的MCP资源不存在` | 工具名错误，或该应用未授权该 server | 控制台「应用与授权」补授权 |
| `-32003 该应用无权限` | 有 server 授权但工具不在白名单内 | 检查 Tool 白名单 |
| `-32001 缺少用户身份 token` / 401 | 身份下传已开启，但没带 `x-dsh-auth-token` | 客户端补用户 token；检查 `MCP_IDENTITY_USER_TOKEN_HEADER` 是否为默认值 |
| 请求 `tools/list` 返回 `INVALID_REQUEST` | 缺 `Mcp-Session-Id` | 先 `initialize` 再携带会话 |
| Codex 握手报 `data did not match any variant of untagged enum JsonRpcMessage` | 旧写法 `[mcp_servers.X.headers]` 被静默忽略 → 401 body 被当 JSON-RPC 解析 | 改用 `http_headers.Authorization = "Bearer <JWT>"`，并删除无效的 `bearer_token_env_var` |
| Codex 显示“已连接”但看不到工具 | `initialize` 成功、`tools/list` 401/403；工具未注册进本轮；Server 为 WHITELIST 且应用未授权任何工具 | 检查 token 与网关日志；在「应用与授权」中授权 Server（会自动写入当前全部工具） |
| `tools/list` 返回 `_meta.overWarn=true` | 该应用可见工具数超过告警阈值 | 启用 META 模式、收紧白名单，或按业务域拆分 Server |
| 只看到部分工具，缺少后续工具 | 客户端未跟随 `nextCursor` 分页 | 确认客户端支持分页；或把 `MCP_TOOLS_PAGE_SIZE` 调大 / 拆分 Server |
| Codex 反复提示 token 过期 | HTTP 模式使用静态 JWT | 更新 `http_headers.Authorization`，或改用 STDIO 桥接自动续签 |
