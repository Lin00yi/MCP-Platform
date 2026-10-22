# DSH 动态 MCP 同步插件（方案 B）

插件包：[dsh-mcp-gateway-sync](../dsh-mcp-gateway-sync)

它运行在 DSH 进程内，不再修改 `cordis.patch.yml`。

> 发现接口现在只返回「当前应用已授权的 Endpoint」，每个 Endpoint 一个 `mcp-client`：
> `mcp-client(team-pcb)` → `/mcp/e/team-pcb`，工具名形如 `mcp__team-pcb__pcb__orders_get`。
> 没有 Endpoint 授权时发现结果为空。旧的 `client.access.mode` 开关已下线。

```text
# Endpoint 模式（唯一模式）
DSH 进程
  └─ @mcp-gateway/dsh-mcp-gateway-sync
       ├─ mcp-client(team-pcb)  → /mcp/e/team-pcb
       └─ mcp-client(team-eda)  → /mcp/e/team-eda
```

- 拉取 `/mcp/discovery/servers`；
- 为每个可见 Server 动态 `ctx.plugin()` 一个 `@deepseek-ai/dsh-mcp-client`；
- Server 更新/撤销 → dispose 旧 child；
- Server 新增 → 创建新 child；
- token 文件变化 → 重建 child；
- 不需要外部脚本，不需要改 YAML，不需要重启 DSH。

## 1. 安装插件

### 方式 A：dsh plugin

```bash
dsh plugin --profile <profile> add /Users/lin/Downloads/MCP-Gateway/dsh-mcp-gateway-sync
```

### 方式 B：手动复制（桌面版 profile 被 Electron 管理时）

```bash
DEST="$HOME/.dsh/profiles/node_modules/@mcp-gateway/dsh-mcp-gateway-sync"
mkdir -p "$(dirname "$DEST")"
rsync -a --exclude node_modules \
  /Users/lin/Downloads/MCP-Gateway/dsh-mcp-gateway-sync/ "$DEST/"
```

插件只依赖 DSH 已安装的：

```text
@deepseek-ai/cordis
@deepseek-ai/dsh-mcp-client
@deepseek-ai/schemastery
```

## 2. profile 配置

在 `~/.dsh/profiles/<profile>/cordis.patch.yml` 中只保留一个同步插件：

```yaml
- insert:
    - id: mcp-gateway-sync
      name: "@mcp-gateway/dsh-mcp-gateway-sync"
      config:
        gatewayBaseUrl: http://127.0.0.1:8081
        tokenFile: /Users/lin/Downloads/MCP-Gateway/mcp-gateway/.codex/dsh-pcb.token
        # 网关开启身份下传时必填：文件内容即 x-dsh-auth-token 的值
        userTokenFile: /Users/lin/Downloads/MCP-Gateway/mcp-gateway/.codex/dsh-user.token
        intervalMs: 30000
        serverNamePrefix: ""
        toolCallTimeoutMs: 60000
        requestTimeoutMs: 15000
        failOnStartupError: false
```

> 如果之前使用 `dsh-mcp-sync.py` 生成过 per-server managed block，请先删除该 block，避免同一个 `serverName` 被两个 mcp-client 实例占用。

## 3. 配置项

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `gatewayBaseUrl` | 是 | - | 网关地址 |
| `tokenFile` | 是 | - | 应用 JWT（`Authorization`），建议 0600 |
| `userTokenFile` | 否* | `""` | 用户 token 文件（`x-dsh-auth-token`），建议 0600；*网关开启身份下传时必填 |
| `intervalMs` | 否 | 30000 | discovery 轮询间隔，最小 5000 |
| `serverNamePrefix` | 否 | `""` | 多网关/多项目共用一个 profile 时避免 `serverName` 冲突 |
| `toolCallTimeoutMs` | 否 | 60000 | 子 mcp-client 的工具调用超时 |
| `requestTimeoutMs` | 否 | 15000 | discovery 请求超时 |
| `failOnStartupError` | 否 | false | 首次 discovery 失败时是否让插件激活失败 |
| `approvalUnwrap` | 否 | true | 把 `call_tool` 的内层工具名写入审批展示 |
| `approvalEscalate` | 否 | false | 把「允许的 call_tool」升级为审批，让卡片展示真实工具名 |

> 网关设置 `MCP_IDENTITY_ENABLED=true` 后，discovery 仍只用应用 JWT，但每个子 `mcp-client`
> 的请求必须带 `x-dsh-auth-token`；插件会同时把该头加到 discovery 与工具调用请求上。
> `userTokenFile` 缺失时子连接会 401，日志中表现为 `initialize`/`tools/list` 失败。

最终 `serverName`：

```text
serverNamePrefix + serverKey
```

必须匹配 `[A-Za-z0-9_-]{1,32}`。

## 4. 生命周期

```text
插件 apply
  → 注册 timer + cleanup effect
  → 首次 reconcile：读 token → GET /mcp/discovery/servers
  → 对每个可见 server：
       ctx.plugin(mcp-client, {serverName, url, headers})
       await child activation（tools/list 完成）
  → 每 intervalMs 再 reconcile
       - 新增：mount child
       - 修改：dispose + mount
       - 删除/撤销：dispose child
       - token 变化：dispose + mount
  → 插件 dispose：停止 timer，逐个 dispose child
```

指纹包含：

```text
serverName + url + revision + tokenHash
```

所以 Server URL、工具 revision 或 token 变化都会重建对应 child；未变化的 child 不会重连。

## 5. 验证

插件加载后，DSH 工具列表出现（Endpoint 模式，`serverName` 为 endpointKey）：

```text
mcp__team-pcb__search_tools
mcp__team-pcb__get_tool_schema
mcp__team-pcb__call_tool
```

具体工具取决于 Endpoint 成员 Server 的 `tool_exposure_mode` 合并结果：

- 全部成员为 `DIRECT`：全量工具，工具名形如 `mcp__team-pcb__pcb__orders_get`；
- 任一成员为 `META`：`search_tools / get_tool_schema / call_tool / read_result_chunk` + 各 META Server 的热工具，
  DIRECT 成员的工具仍然直出（详见 [工具规模治理](工具规模治理.md) §3.3）。

网关日志应看到：

```text
MCP 请求 method=initialize, path=/mcp/v1/pcb-server, appId=dsh-pcb
MCP 请求 method=tools/list, path=/mcp/v1/pcb-server, appId=dsh-pcb
```

## 6. 用户态

- 插件使用当前 profile 的 token 文件，不使用 admin token；
- 网关按 `app_server_rel` + 租户过滤，插件只挂载当前用户可见的 Server；
- 一个 app 只归属一个租户；多租户需要多个 app/token；
- 共享 DSH profile 时，不同用户需要不同 token/配置；
- 多网关/多项目共用 profile 时，设置不同 `serverNamePrefix` 和不同插件 id。

## 7. 与外部脚本对比

| | `dsh-mcp-sync.py` | 本插件 |
|---|---|---|
| 运行位置 | DSH 外部 | DSH 进程内 |
| 修改 YAML | 是 | 否 |
| 动态更新 | 依赖重载/重启 | 运行时挂载/卸载 |
| 跨 DSH fork 复用 | 复制脚本 | 每个 fork 安装插件 |
| DSH 版本耦合 | 低 | 高 |
| 适用阶段 | 快速测试/多端点手工验证 | 产品化动态接入 |

## 8. 测试

```bash
cd dsh-mcp-gateway-sync
node --test test/*.test.mjs
```

集成验证使用真实 `@deepseek-ai/dsh-mcp-client` 连接网关，确认能注册工具并在 dispose 后注销。

## 9. 已知限制

- DSH MCP client 只桥接 tools，不桥接 prompts/resources；
- `serverName` 必须全局唯一；
- token 变化会重建 child，正在执行的调用可能中断；
- DSH 升级后若 Cordis 或 `dsh-mcp-client` API 变化，需要适配插件；
- 每个 Server 一个子插件，Server 多时启动/连接开销增加。

---

## 10. 审批信息还原（R6）

META 模式下长尾工具统一经 `call_tool` 代理，DSH 原生审批只拿到外层工具名。
插件通过 `tools/pre-execute` 瀑布读取内层 `name` / `reason`，在需要审批时改写展示：

```text
tools/pre-execute(exec, next)
  → decision = await next()
  → inner = unwrapMetaCall(exec.name, exec.arguments)
  → decision=ask  ：reason/displayReason 写入“代理调用 pcb__refund_apply（原因：…）”
  → decision=allow：approvalEscalate=true 时才升级为 ask
  → deny/cancel   ：原样返回，绝不放大权限
```

实现：[approval.js](../dsh-mcp-gateway-sync/approval.js)，单测 `test/approval.test.mjs`。

安装状态（2026-10-20）：已同步到
`~/.dsh/profiles/node_modules/@mcp-gateway/dsh-mcp-gateway-sync/`，
并在 `profiles/desktop/cordis.patch.yml` 打开 `approvalUnwrap`；
**需重启 DSH 后新插件代码才会加载**（运行中的进程仍持有旧模块）。

边界：

- 只改审批展示，不修改 `exec.name` / `exec.arguments`，不影响实际执行；
- “本会话始终允许”仍绑定外层 `call_tool`（DSH 审批服务的记忆键是传给它的 toolName），
  如需按内层工具记忆，需要宿主提供按目标工具授权的能力；
- 若 DSH 版本没有 `tools/pre-execute` 瀑布，插件会跳过并打 WARN，不影响 MCP 挂载。
