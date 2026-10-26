# DSH Desktop 接入 MCP Gateway

> **2026-10 认证变更**：网关只认 `x-dsh-auth-token`（`dsh_` / PAT）；应用创建、`appId`/`appSecret`、应用级授权与 `access-token` 已移除。本文中相关历史段落已过期，以 [README](../README.md) 与 [MCP客户端接入指南](MCP客户端接入指南.md) 为准。

> **当前桌面环境已切换到方案 B 插件**：见 [DSH插件接入.md](DSH插件接入.md)。
> 本文保留方案 A（外部同步器 + managed block）作为备用/排障路径。

DSH Desktop 通过 `@deepseek-ai/dsh-mcp-client` 插件接入外部 MCP Server。
方案 A 不手工维护 N 个条目，而是：

```text
GET /mcp/discovery/servers（扩展后的现有接口）
  → 本机 dsh-mcp-sync 拉取当前用户可见的 Server
  → 生成/替换 cordis.patch.yml 中的 managed block
  → DSH 重载后注册 mcp__<serverKey>__<tool>
```

## 当前选型（2026-10 起）

**统一 Endpoint 接入**：客户端只从发现接口拿到「已授权的 Endpoint」，每个 Endpoint 一个地址：

```text
/mcp/e/{endpointKey}
```

工具名为 `mcp__<endpointName>__<namespacePrefix>__<tool>`（DSH 先加 `mcp__<serverName>__`，网关再加
`{namespacePrefix}__`）。同步器/插件不需要改代码：它们仍然按发现结果挂载。

Endpoint、Namespace 在控制台「资产 → Namespaces」「权限与治理 → Endpoints」维护；应用授权已移除，启用 Endpoint 即可访问。
旧的 `client.access.mode`、`/mcp/v1/aggregate` 与 `/mcp/v1/{serverPath}` 均已下线，详见
[Endpoint统一接入.md](Endpoint统一接入.md)。

配置文件：

```text
~/.dsh/profiles/desktop/cordis.patch.yml
```

---

## 1. 网关接口

`/mcp/discovery/servers` 在原有字段上新增：

```json
{
  "id": 10,
  "serverKey": "pcb",
  "name": "pcb-server",
  "path": "/mcp/e/pcb",
  "url": "http://127.0.0.1:8081/mcp/e/pcb",
  "status": 1,
  "upstreamType": "NONE",
  "toolCount": 8,
  "updatedAt": 1792218483000,
  "revision": "161ff1e5..."
}
```

- 只返回当前用户 token 可见的 Endpoint（应用授权已移除）；
- `toolCount` 是**授权后的工具数**；
- `revision` 包含 server、授权、工具变更；
- 响应带 `ETag` / `X-MCP-Revision`，支持 `If-None-Match` 304；
- `serverKey` 是稳定的 DSH `serverName`，后台改名不会改工具名。

---

## 2. 本机同步器

`tools/dsh-mcp-sync.py` 只依赖 Python 标准库，可以直接复制到其他 DSH 二开项目。
前置条件：目标网关的 `/mcp/discovery/servers` 已返回 `serverKey`、`url`、`revision` 等字段。

### 2.1 配置

```bash
mkdir -p ~/.dsh/mcp-gateway
cp mcp-gateway/.codex/dsh-pcb.token ~/.dsh/mcp-gateway/token
chmod 600 ~/.dsh/mcp-gateway/token

# 网关开启身份下传时还需要用户 token（内容即 x-dsh-auth-token 的值）
printf '%s' "$DSH_AUTH_TOKEN" > ~/.dsh/mcp-gateway/user-token
chmod 600 ~/.dsh/mcp-gateway/user-token
```

`~/.dsh/mcp-gateway/config.json`：

```json
{
  "gatewayBaseUrl": "http://127.0.0.1:8081",
  "tokenFile": "~/.dsh/mcp-gateway/token",
  "userTokenFile": "~/.dsh/mcp-gateway/user-token",
  "profilePath": "~/.dsh/profiles/desktop/cordis.patch.yml",
  "intervalSeconds": 30,
  "namespace": "mcp-gateway",
  "serverNamePrefix": "",
  "entryIdPrefix": "mcp-gw-",
  "mcpClientName": "@deepseek-ai/dsh-mcp-client",
  "stateFile": "~/.dsh/mcp-gateway/state.json",
  "lockFile": "~/.dsh/mcp-gateway/sync.lock",
  "toolCallTimeoutMs": 60000,
  "failOnStartupError": false
}
```

如果是**另一个 DSH 二开项目 / 另一个网关**，用独立 namespace、前缀和 state，避免互相覆盖：

```json
{
  "gatewayBaseUrl": "http://127.0.0.1:9090",
  "tokenFile": "~/.dsh/my-project/token",
  "userTokenFile": "~/.dsh/my-project/user-token",
  "profilePath": "~/.dsh/profiles/my-project/cordis.patch.yml",
  "namespace": "my-project",
  "serverNamePrefix": "my_",
  "entryIdPrefix": "my-gw-",
  "stateFile": "~/.dsh/my-project/state.json",
  "lockFile": "~/.dsh/my-project/sync.lock"
}
```

- `userTokenFile`：网关开启身份下传（`MCP_IDENTITY_ENABLED=true`）时必填，内容为 `x-dsh-auth-token` 的值；
- `namespace`：managed block 标记的命名空间，不同项目必须不同；
- `serverNamePrefix`：避免不同网关的 `serverKey` 冲突，最终会变成 `my_pcb`；
- `entryIdPrefix`：插件条目 id 前缀；
- `mcpClientName`：二开项目如果换了 MCP client 包名，改这里；
- `stateFile` / `lockFile`：必须每个项目独立。

### 2.2 运行

```bash
cd <仓库根目录>

# 先看将生成什么
python3 tools/dsh-mcp-sync.py \
  --config ~/.dsh/mcp-gateway/config.json \
  --once --dry-run

# 实际写入
python3 tools/dsh-mcp-sync.py \
  --config ~/.dsh/mcp-gateway/config.json \
  --once
```

### 2.3 生成的 managed block

```yaml
# >>> mcp-gateway managed block: do not edit manually >>>
# revision=...
# 顶层是 patch，必须用 insert 新增插件条目
- insert:
    - id: "mcp-gw-pcb"
      name: "@deepseek-ai/dsh-mcp-client"
      config:
        serverName: "pcb"
        transport: streamable-http
        url: "http://127.0.0.1:8081/mcp/e/pcb"
        headers:
          x-dsh-auth-token: !!js '`${process.getBuiltinModule("node:fs").readFileSync("/Users/.../.dsh/mcp-gateway/token", "utf8").trim()}`'
        failOnStartupError: false
        toolCallTimeoutMs: 60000
# <<< mcp-gateway managed block <<<
```

同步器只会替换标记块，不会碰块外的用户配置。

> 注意：`cordis.patch.yml` 的顶层条目是 **patch**，不是插件实例。新增 MCP Server 必须放在 `insert:` 下；直接写 `- id: mcp-...` 会被 loader 当作“修改一个不存在的 entry”。

### 2.4 launchd 定时同步

`~/Library/LaunchAgents/com.deepseek.dsh-mcp-sync.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.deepseek.dsh-mcp-sync</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/python3</string>
    <string><仓库根目录>/tools/dsh-mcp-sync.py</string>
    <string>--config</string>
    <string>~/.dsh/mcp-gateway/config.json</string>
    <string>--once</string>
  </array>
  <key>StartInterval</key><integer>30</integer>
  <key>RunAtLoad</key><true/>
  <key>StandardOutPath</key><string>~/.dsh/mcp-gateway/sync.log</string>
  <key>StandardErrorPath</key><string>~/.dsh/mcp-gateway/sync.err.log</string>
</dict>
</plist>
```

```bash
launchctl load -w ~/Library/LaunchAgents/com.deepseek.dsh-mcp-sync.plist
```

---

## 3. 生命周期

```text
管理端新增/修改/停用 Server
  → mcp_server / 授权 / 工具变化
  → /mcp/discovery/servers 的 revision 变化
  → 同步器拉取（定时或手动）
  → 替换 managed block
  → DSH 重载 profile
  → MCP client initialize + tools/list
  → 注册 mcp__<serverKey>__<tool>
```

| 后台变化 | DSH 行为 |
|---|---|
| 新增 Server | managed block 新增条目；重载后出现新工具 |
| 改 name | `serverKey` 不变，工具名不变 |
| 改 path/url | 条目 URL 更新，重载后重连 |
| 停用/删除 Server | 条目移除，工具注销 |
| 新增/删除 Tool | revision 变化，重载后刷新工具列表 |
| 撤销授权 | Server 从可见列表消失 |
| token 过期 | 同步器收到 401，保留上次配置，等待刷新 token |

### DSH 重载的现实情况

同步器只负责**正确落盘**，不主动杀 DSH 进程。
当前 DSH Desktop 打包版实测：外部进程写入 `cordis.patch.yml` 后，MCP 客户端不一定会立刻重连；需要：

- 重启 DSH Desktop；或
- 使用支持 profile HMR 的 DSH 版本/环境；或
- 后续改用方案 B（网关单一聚合入口）或动态注册插件，避免改 YAML。

已用 DSH 同款 MCP SDK（1.29.0）验证：网关对 `server/discover` 返回标准 `-32601` 后，SDK 会回退到 `initialize`，并成功拿到 8 个工具。
因此同步后的配置是有效的；如果当前 DSH 进程仍显示 disconnected，重启 DSH 即可重新加载。

`--force` 可用于强制重写文件，验证 DSH 是否监听了变更：

```bash
python3 tools/dsh-mcp-sync.py --config ~/.dsh/mcp-gateway/config.json --once --force
```

---

## 4. 用户态

- 同步器使用**当前 DSH 用户的 token**（`dsh_` / PAT），不是 admin token；**不再需要应用 token**；
- 网关只按用户 token 认证，不再做应用/租户授权过滤；DSH 看到的是全部启用 Endpoint；
- 共享 `~/.dsh` profile 时，切换用户必须先替换 managed block（token 文件），再重载 DSH；
- 不要使用超管 token 以外的后台凭证；`dsh_`/PAT 失效会直接 401。

---

## 5. Token 过期

在 user-center 重新登录生成 `dsh_` token，或重新生成 PAT，把 token 文件（`~/.dsh/mcp-gateway/token`）更新为一行新的 Bearer token。

然后让 DSH 重载/重启；`cordis.patch.yml` 不需要改。

> 当前是静态 token + Streamable HTTP 的固有限制。若要完全免维护，可改用 STDIO 桥接或 OAuth。
