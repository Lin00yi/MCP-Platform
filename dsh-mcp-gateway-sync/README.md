# @mcp-gateway/dsh-mcp-gateway-sync

DSH Cordis 插件：在 DSH 进程内拉取 MCP Gateway discovery，并动态挂载/卸载
`@deepseek-ai/dsh-mcp-client` 子插件。

它不修改 `cordis.patch.yml`，也不依赖 DSH profile HMR：

```text
DSH 进程
  └─ mcp-gateway-sync（本插件）
       ├─ mcp-client(pcb-server)
       ├─ mcp-client(eda-server)
       └─ ...
```

- 网关新增 Server → 动态 `ctx.plugin()`；
- Server 更新 → dispose 旧 child，创建新 child；
- Server 停用/删除/撤销授权 → dispose child，工具自动注销；
- token 文件变化 → 重建 child，使用新 token。

## 前置条件

- DSH 已安装 `@deepseek-ai/dsh-mcp-client`；
- profile 可解析 `@deepseek-ai/cordis`、`@deepseek-ai/schemastery`；
- Node.js >= 20；
- 网关 `/mcp/discovery/servers` 返回 `serverKey`、`url`、`revision`；
- 一个 DSH 用户的 `dsh_`/PAT（`x-dsh-auth-token`），放在 0600 文件。

## 安装

```bash
# 在本仓库根目录
dsh plugin --profile <profile> add <仓库根目录>/dsh-mcp-gateway-sync
```

然后在对应 profile 的 `cordis.patch.yml` 中只添加一个同步插件：

```yaml
- insert:
    - id: mcp-gateway-sync
      name: "@mcp-gateway/dsh-mcp-gateway-sync"
      config:
        gatewayBaseUrl: http://127.0.0.1:8081
        tokenFile: ~/.dsh/mcp-gateway/token
        # 网关开启身份下传时必填：文件内容即 x-dsh-auth-token 的值
        userTokenFile: ~/.dsh/mcp-gateway/user-token
        intervalMs: 30000
        serverNamePrefix: ""
        toolCallTimeoutMs: 60000
        requestTimeoutMs: 15000
        failOnStartupError: false
```

> 如果之前用 `dsh-mcp-sync.py` 生成过 per-server managed block，请先删除该块，避免和本插件重复挂载同一批 `serverName`。

首次安装后重启/重载 DSH 一次；之后 Server 增删改不再需要改 YAML 或重启。

## 配置

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `gatewayBaseUrl` | 是 | - | 网关地址，如 `http://127.0.0.1:8081` |
| `tokenFile` | 是 | - | 应用 JWT 文件（`Authorization`），建议 0600 |
| `userTokenFile` | 否* | `""` | 用户 token 文件（`x-dsh-auth-token`），建议 0600；*网关开启身份下传时必填 |
| `intervalMs` | 否 | 30000 | discovery 轮询间隔，最小 5000 |
| `serverNamePrefix` | 否 | `""` | 多网关/多项目共用 profile 时避免 `serverName` 冲突 |
| `toolCallTimeoutMs` | 否 | 60000 | 传给子 `mcp-client` 的调用超时 |
| `requestTimeoutMs` | 否 | 15000 | discovery HTTP 超时 |
| `failOnStartupError` | 否 | false | 首次 discovery 失败时是否让插件激活失败 |

`serverNamePrefix + serverKey` 必须匹配 `[A-Za-z0-9_-]{1,32}`。

## 用户态

- 插件使用当前 profile 的 token 文件，不要使用 admin token；
- 网关开启身份下传时，`userTokenFile` 提供 `x-dsh-auth-token` 的用户 token；它与应用 JWT 是两个文件；
- 网关按 user token 认证，插件只挂载该用户可见的 Endpoint；
- 多租户需要多个 app/token，或每个租户一个 profile/plugin 实例；
- 共享 DSH profile 时，不同用户必须使用不同 token/配置。

## 测试

本仓库内已放置 peer 依赖软链（`node_modules` 被 gitignore）：

```bash
cd dsh-mcp-gateway-sync
node --test test/*.test.mjs
```

## 与外部脚本的区别

| | 外部 `dsh-mcp-sync.py` | 本插件 |
|---|---|---|
| 运行位置 | DSH 进程外 | DSH 进程内 |
| 改 YAML | 是 | 否 |
| 动态生效 | 依赖 DSH 重载/重启 | 运行时挂载/卸载 |
| DSH 版本耦合 | 低 | 高（依赖 Cordis/MCP client API） |
| 跨 DSH fork 复用 | 复制脚本即可 | 每个 fork 安装插件并保持 API 兼容 |

## 已知限制

- DSH MCP client 当前只桥接 tools，不桥接 prompts/resources；
- 子插件的 `serverName` 必须全局唯一；
- token 变化会重建 child，正在执行的调用可能中断；
- DSH 升级后如果 `@deepseek-ai/dsh-mcp-client` 的导出/生命周期 API 变化，需要同步适配本插件。
