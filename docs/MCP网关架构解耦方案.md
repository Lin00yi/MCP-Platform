# MCP 网关架构解耦方案

> 版本：v2.0
> 日期：2026-10
> 范围：MCP 网关（数据面，代理/聚合）与后台控制面板（控制面）解耦 + 客户端 MCP 发现能力设计
> 前置文档：《MCP网关多租户改造方案.md》

---

## 1. 背景与现状

### 1.1 目标链路（三层）

```
客户端应用 ──▶ mcp gateway ──▶ mcp server
 (MCP client)     (代理/聚合)      (真实 MCP server)
```

- **客户端应用**：MCP client，讲 MCP 协议（SDK 原生支持）
- **mcp gateway（数据面）**：MCP **代理/聚合层**，对上是 server、对下是 client
- **mcp server**：真实的下游 MCP 服务（承载具体工具/prompt/resource 的业务逻辑）

这条链路是**两跳 MCP 连接**：

| 跳 | 两端 | 协议 | gateway 角色 |
|---|---|---|---|
| 第一跳 | 客户端应用 → gateway | Streamable HTTP | **server** |
| 第二跳 | gateway → mcp server | Streamable HTTP / stdio | **client** |

### 1.2 当前实现的缺口（关键）

当前 `mcp-transfer` 里 `tools/call` 只有两种执行方式，**还没有「转发给真实 MCP server」这一种**：

| 当前 type | 行为 | 是不是真实 mcp server |
|---|---|---|
| `type=0` | 本地反射执行（`LocalToolsService.*`） | ❌ 工具逻辑在 gateway 进程内 |
| `type=1` | WebClient 调下游 **REST** 接口 | ❌ 是 REST，不是 MCP 协议 |

即：**当前 gateway 自己就是 MCP server**（工具本地执行或转 REST），缺少「gateway 作为 MCP client 连下游真实 mcp server」的能力。`mcp_server` 表现在只是一个**逻辑分组/端点**（path + capabilities），并非真实下游 MCP 服务。

### 1.3 现状部署（数据面 + 控制面同进程）

当前 `mcp-transfer`（Spring Boot 2.7 + Java 17）是一个**单进程单应用**，同时承载两条职责面：

| 面 | 职责 | 对外入口 | 调用方 |
|---|---|---|---|
| **数据面（MCP 网关）** | MCP 协议、工具执行/转发、鉴权、会话 | 动态端点 `/mcp/v1/{serverPath}`（POST / GET-SSE / DELETE） | MCP 客户端 |
| **控制面（后台管理）** | 租户/Server/工具/Prompt/Resource/应用/授权/审批/审计/日志/概览 | `/mcp/admin/**`、`/mcp/log/**` | 管理控制台（mcp-console React 前端） |

二者共享同一 JVM、同一 Spring 容器、同一 MySQL、同一配置、同一拦截器链。

### 1.4 现有 MCP 协议能力（数据面）

| MCP 方法 | 说明 |
|---|---|
| `initialize` | 握手，返回 protocolVersion / capabilities / serverInfo，响应头带 Mcp-Session-Id |
| `notifications/initialized` | 初始化完成通知（202） |
| `ping` | 探活 |
| `tools/list` | 返回该 server 下启用工具（name/description/inputSchema） |
| `tools/call` | 调用工具（本地反射 / REST 转发） |
| `prompts/list` | 返回该 server 下 prompt |
| `resources/list` / `resources/templates/list` | 返回该 server 下 resource / 模板 |

---

## 2. 问题分析

### 2.1 数据面缺「MCP 代理」能力

| 缺口 | 现状 | 影响 |
|---|---|---|
| 上游 MCP 客户端 | 无 | gateway 无法连下游真实 mcp server |
| 工具聚合 | 无（每个 server 独立暴露，客户端连一个 path 只看到一个 server 的工具） | 无法「一个入口聚合多个 mcp server」 |
| 工具路由 | 无（工具归属写死在单个 server） | 无法按工具路由到不同下游 |
| 会话映射 | 无（只有客户端↔gateway 一段会话） | 无法维护 gateway↔mcp server 的第二段会话 |
| 能力合并 | 只按本 server 的工具派生 | 无法聚合下游各 server 的 capabilities |

### 2.2 数据面与控制面同进程耦合（双向穿透）

| 组件 | 归属 | 反向使用 | 耦合表现 |
|---|---|---|---|
| `McpServiceImpl` | 数据面 | 控制面 `McpAdminController` 的 `test-connect`、`from-rest` | 控制面调用数据面 `testConnection`、`resolveServerIdByPath` |
| `McpServerDynamicControllerManager` | 数据面 | 控制面 `createServer/updateServer/deleteServer` 自动触发 | 控制面写操作直接触发数据面路由重建 |
| `ToolListCache` | 数据面 | 控制面 `createTool/updateTool/deleteTool` 调 `invalidate` | 控制面穿透操作数据面缓存 |
| `SseEmitterRegistry` | 数据面 | 控制面 `onToolChanged` 调 `broadcastToServer` | 控制面向数据面 SSE 长连接广播 |
| 拦截器链（AdminAuth / McpAuth / McpSession） | 两套鉴权 | 注册在同一 `McpWebConfig`，`/**` 全拦截 | 每个请求先过一遍「判断属于哪面」的分支逻辑 |

### 2.3 数据耦合（同一张表，控制面写、数据面读）

| 表 | 控制面 | 数据面 |
|---|---|---|
| `mcp_server` / `mcp_tool` / `mcp_prompt` / `mcp_resource` | 增删改 | 动态注册、`tools/list`、`tools/call`、能力派生 |
| `app_server_rel` / `app_tool_rel` | 授权/撤销 | 每次 MCP 调用鉴权 |
| `tool_call_record` / `mcp_request_log` | 读（日志/概览） | 写（调用记录/安全审计） |
| `operation_log` / `approval_request` | 读写 | 不涉及 |

### 2.4 由此带来的痛点

| 维度 | 问题 |
|---|---|
| **故障爆炸半径** | 控制面慢查询/大导出/内存抖动直接影响 MCP 调用延迟；MCP 大流量挤占控制面资源 |
| **扩缩容** | 无法独立扩容，数据面要横向扩展时控制面被迫一起扩 |
| **安全边界** | 数据面（多租户、不可信输入）与控制面同进程，数据面被攻破则控制面同机沦陷 |
| **发布/重启** | 改控制面功能也重启整个进程 → MCP 的 SSE 长连接、session 全部断开 |
| **依赖污染** | 数据面被迫携带 POI(Excel)、springdoc(Swagger)、BCrypt/JWT 等控制面依赖 |
| **代码组织** | `McpServiceImpl` 混合 MCP 协议 + 控制面测试连通；`McpAdminController` 混合 CRUD + 数据面调用 |

### 2.5 客户端发现能力缺失

MCP 客户端当前只能拿到单一 server 内的方法列表，**完全缺失以下能力**：

| 缺失能力 | 现状 | 影响 |
|---|---|---|
| Server 目录/注册表 | 客户端必须硬编码 `path`，无「列出可连 server」接口 | 新增 server 客户端无法自动发现 |
| 跨 Server 工具检索 | 「资产目录」仅做在管理台 UI，客户端拿不到 tags/owner/version | 无法按关键词/标签找工具 |
| 工具元数据暴露 | `tools/list` 不返回 tags/version/owner/所属 server | 无法做版本感知、标签过滤 |
| 健康探测 | 只有需先握手的 `ping`，无轻量公开探测 | 客户端难做熔断/降级 |
| 变更订阅 | `listChanged` 声明了但只在控制面工具变更时广播 | 未维持 SSE 的客户端感知不到变更 |

---

## 3. 目标架构

拆为三块：**控制面（Admin Backend）+ 注册中心（Registry）+ 数据面（MCP Gateway 代理）**。

```
┌────────────────┐        ┌────────────────┐        ┌────────────────────────────┐
│  控制面         │        │  注册中心       │        │  数据面（MCP Gateway 代理）  │
│ Admin Backend  │──写入──▶│  Registry      │◀──只读──│ /mcp/v1/*                  │
│ /mcp/admin/**  │        │ (目录+元数据+    │        │ 第一跳: server 端           │
│ CRUD/授权/审批/  │        │  健康+变更订阅)  │        │ 第二跳: client 端 ──▶ mcp    │
│ 审计/日志/概览   │        │                │        │   server A/B/C            │
└────────────────┘        └────────────────┘        └────────────────────────────┘
        ▲                        ▲                          ▲
        │                        │                          │
   React 控制台           客户端发现接口                MCP 客户端
                    GET /v1/servers              (initialize/tools/*)
                    GET /v1/tools?tag=&q=
                    GET /v1/servers/{id}/health
                    SSE 变更订阅
```

### 3.1 各模块职责

| 模块 | 职责 | 状态 | 依赖 |
|---|---|---|---|
| **控制面 Admin Backend** | 租户/Server/工具/Prompt/Resource 管理、应用授权、审批、审计、日志、概览 | 有状态（写） | DB（读写） |
| **注册中心 Registry** | 对外暴露客户端发现能力：server 目录、工具目录、元数据检索、健康、变更订阅 | 无状态（读，或本地缓存） | DB（读）或事件总线 |
| **数据面 MCP Gateway** | MCP **代理**：第一跳 server + 第二跳 client、工具聚合/路由、鉴权、会话映射、限流/审计 | 无状态（读 + 本地缓存） | DB（读）+ 失效通知 + 下游 mcp server |

---

## 4. 数据面（MCP Gateway 代理）设计

### 4.1 上游 MCP 客户端（新增）

gateway 内置一个 MCP client（`MCP Upstream Client`），作为第二跳的 client 去连下游 mcp server：

- 连接协议：Streamable HTTP（首选）或 stdio
- 对每个下游 server 维护独立连接 + 独立 session
- 启动/路由刷新时主动 `initialize` + `tools/list` 拉取下游工具，聚合进本地目录

### 4.2 工具聚合与命名空间

- 多个下游 mcp server 的工具合并成**一份** `tools/list` 返回给客户端
- 工具名加**命名空间前缀**防冲突：`{serverName}__{toolName}`（或 `serverName/toolName`）
- 网关维护「聚合工具名 → (下游 server, 原始工具名)」的映射表用于路由

### 4.3 工具路由

```
客户端 tools/call { name: "pcb-server__query-post" }
   → gateway 查映射：归属 server = pcb-server，原始 tool = query-post
   → gateway 用第二跳 session 调下游 mcp server 的 tools/call
   → 结果回传客户端
```

### 4.4 会话映射（两段会话）

| 会话 | 生命周期 | 维护方 |
|---|---|---|
| 客户端 ↔ gateway | 客户端 `initialize` 创建，`DELETE`/超时销毁 | gateway（第一跳 server 端） |
| gateway ↔ mcp server | 懒建立，按需复用/重建 | gateway（第二跳 client 端） |

### 4.5 能力合并（`initialize`）

- 客户端 `initialize` 返回的 `capabilities` 由**下游各 mcp server 的能力聚合**派生
- `serverInfo` 标识「这是网关聚合端点」，下游信息下沉到工具元数据的 `server` 字段

### 4.6 横切能力（gateway 的增值层）

| 能力 | 说明 |
|---|---|
| 鉴权/多租户 | 客户端 app_secret → 租户 → 可访问哪些 server/tool（`app_server_rel`/`app_tool_rel`） |
| 限流/配额 | 按 app / 租户限流 |
| 审计/日志 | `tool_call_record`（调用记录）+ `mcp_request_log`（脱敏安全审计） |
| 缓存 | 工具目录本地缓存 + 变更失效 |
| 变更订阅 | 下游工具变化 → `notifications/tools/list_changed` 推给客户端 SSE |

---

## 5. 模块边界与接口定义

### 5.1 数据面（MCP Gateway）—— 两跳 MCP 协议

- **第一跳（对外）**：现有 MCP 协议不变，但 `tools/list` 需**增强元数据**（见 6.2）
- **第二跳（对内）**：gateway 作为 client 调下游 mcp server 的标准 MCP 协议（initialize/tools/list/tools/call/prompts/list/resources/list）

### 5.2 控制面（Admin Backend）—— 管理 REST（保持不变）

现有 `/mcp/admin/**`、`/mcp/log/**` 接口与鉴权不变。

### 5.3 注册中心（Registry）—— 客户端发现 API（新增）

统一前缀建议 `/mcp/discovery/**`（或独立 `/registry/**`），**只读、可选鉴权**：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/servers` | server 目录：id/name/path/tenant/capabilities/status/版本 |
| GET | `/servers/{id}` | server 详情 + 工具/prompt/resource 列表 |
| GET | `/tools` | 工具目录：`?q=` 关键词、`?tag=` 标签、`?serverId=`、`?tenantId=`、`?status=` |
| GET | `/tools/{id}` | 工具详情（含 tags/owner/version/inputSchema/lifecycle/归属 server） |
| GET | `/servers/{id}/health` | 健康探测（server 是否启用、path 是否注册、下游连通性） |
| GET | `/servers/{id}/changes`（SSE） | 变更订阅：tools/resources/prompts 的 list_changed 事件 |
| GET | `/prompts` / `/resources` | prompt/resource 目录（可选） |

### 5.4 内部通信（控制面 → 数据面）

| 方案 | 机制 | 适用 |
|---|---|---|
| **A. 共享 DB + 失效通知** | 控制面写库，数据面读库 + 本地缓存；控制面写后发失效事件（DB 轮询 / 消息 / HTTP 回调） | 小规模、单团队，改动最小 |
| **B. 注册中心 + 事件总线** | 控制面写注册中心，注册中心向数据面推送变更（推送式） | 多数据面实例、独立扩缩容 |

---

## 6. 客户端发现能力设计

### 6.1 Server 目录（解决「客户端硬编码 path」）

客户端启动时拉一次 `GET /discovery/servers`，得到所有启用 server 的 `path`，据此构建 MCP 连接配置，无需人工登记。

### 6.2 工具元数据增强（`tools/list` 返回更多字段）

在现有 `tools/list` 的返回基础上，每个工具补充：

```json
{
  "name": "pcb-server__query-post",
  "description": "查询文章",
  "inputSchema": { "type": "object", "properties": {} },
  "tags": ["crm", "客户"],
  "version": "1.0.0",
  "server": { "id": 10, "name": "pcb-server" }
}
```

> 版本号用于客户端的「工具版本感知 / 升级提示」；`server` 字段标明工具归属（聚合场景下区分来源）。

### 6.3 健康探测

- 轻量 `GET /discovery/servers/{id}/health` → `{ok, pathRegistered, toolCount, upstreamOk}`，无需 MCP 握手
- 供客户端做熔断、降级、故障转移

### 6.4 变更订阅（SSE）

- 数据面已具备 `SseEmitterRegistry` 与 `tools.listChanged` 广播能力
- 下游 mcp server 工具变化 → gateway 感知 → 推 `notifications/tools/list_changed` 给客户端
- 注册中心独立后，由数据面（或注册中心）统一对外提供 `changes` SSE

---

## 7. 分阶段迁移路线

| 阶段 | 目标 | 关键动作 | 风险 |
|---|---|---|---|
| **P0 逻辑解耦（不拆物理）** | 边界清晰、低风险 | ① 按 `gateway` / `admin` / `shared` 分包隔离 Service/Controller/Config；② 控制面不再直调数据面内部方法，下沉到共享 `gateway-core`（`testConnection` 等）；③ 明确「控制面写 → 通知 → 数据面缓存刷新」链路 | 低，纯代码重构 |
| **P1 补 MCP 代理能力（MCP→MCP 转发）** | 数据面变成真代理 | ① 新增上游 MCP client；② 工具聚合 + 命名空间 + 路由表；③ 两段会话映射；④ 能力合并 | 中，需选型下游连接方式（SDK/WebClient） |
| **P2 补客户端发现能力** | 对外可用 | 新增 `/discovery/**`（server 目录、工具检索、健康、SSE 订阅）；`tools/list` 增强元数据 | 低，纯增量 |
| **P3 缓存与失效分离** | 数据面可独立读 | 工具/授权定义改为「控制面写库 + 数据面读缓存 + 失效通知」；数据面不穿透控制面 | 中，需处理缓存一致性 |
| **P4 物理拆分** | 三服务独立 | Admin / Registry / Gateway 拆为独立部署；数据面多实例横向扩容；DB 或注册中心解耦 | 高，需评估部署/配置/监控 |

> 建议顺序：**先 P0 + P1 + P2**（逻辑解耦 + 代理能力 + 发现能力，贴合三层链路、价值最大），数据面出现扩容诉求时再做 P3/P4。

---

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 下游 mcp server 连接失败/不稳定 | 上游连接池 + 超时 + 重试 + 熔断 + 健康探测降级 |
| 工具名冲突（多 server 同名工具） | 强制命名空间前缀，映射表唯一约束 |
| 拆服务后缓存一致性 | 事件驱动失效 + TTL 兜底 + 版本号比对 |
| 数据面鉴权与控制面策略不同步 | 鉴权策略统一由「注册中心/共享配置」下发，数据面只执行 |
| 两段会话状态不一致 | 会话映射表 + 懒重建 + 幂等 |
| 迁移期双写 | 控制面新旧接口并存一个版本，灰度切换 |

---

## 9. 附录：现有代码映射（实现状态）

### 9.1 已落地（P1/P2/P3）

| 目标模块 | 落地代码 |
|---|---|
| 数据面代理（第二跳 client 端） | `com.mcp.transfer.gateway.McpUpstreamClient`（上游 MCP client + 两段会话映射） |
| 数据面缓存失效分离 | `com.mcp.transfer.gateway.ToolChangeNotifier`（控制面经此发布变更，不再直触缓存/SSE）<br>2026-10-22 P2 已替换为 `cluster.ClusterEventBus` + `ConfigChangeApplier` |
| 客户端发现（注册中心前置） | `com.mcp.transfer.discovery.McpDiscoveryService` + `McpDiscoveryController`（`/mcp/discovery/servers|tools|servers/{id}/health|servers/{id}/changes`） |
| 数据模型 | `mcp_server.upstream_type` / `upstream_url`（NONE=本地/REST，MCP=代理） |
| 工具元数据 | `McpTool` 结果 DTO 增 `tags`/`version`/`server`，`tools/list` 已返回 |

### 9.2 未落地（待建/延后）

| 目标模块 | 现有代码 | 待建 |
|---|---|---|
| 数据面核心（第一跳 server 端） | `McpController`、`McpServiceImpl`、`McpServerDynamicControllerManager`、`SseEmitterRegistry`、`ToolListCache`、`UrlSecurityValidator`、`SecretCrypto` | 物理拆分时迁入独立 gateway 服务 |
| 控制面核心 | `McpAdminController`、`AdminAuthController`、`McpRequestLogController`、`McpServerAdminController`、`AdminAuthServiceImpl` | 物理拆分时迁入独立 admin 服务 |
| 工具聚合/命名空间（跨多下游合并） | `McpUpstreamClient` 的 `AGGREGATE` 模式：`{name}__{tool}` 前缀合并 + 按前缀路由 + 每上游独立会话 | — |
| 物理拆分（两服务） | ✅ Admin / Dataplane 拆为独立部署（Registry 并入数据面） | 多实例扩容（需先做状态外置） |

### 9.3 分包边界（P0，已落地）

- `com.mcp.transfer.gateway.*`：数据面（McpController、McpService/Impl、McpUpstreamClient；变更事件改为 `cluster.*`）
- `com.mcp.transfer.admin.*`：控制面（McpAdminController、AdminAuthController、McpRequestLogController、McpServerAdminController、AdminAuthService/Impl）
- `com.mcp.transfer.discovery.*`：注册中心前置（发现接口）
- `com.mcp.transfer.entity` / `mapper` / `dto` / `config` / `enums`：共享层（实体/Mapper/DTO/配置/枚举，两平面共用）

---

## 10. 实现进度汇总

| 阶段 | 状态 |
|---|---|
| P0 逻辑解耦 | ✅ `gateway`（数据面）/ `admin`（控制面）/ `discovery`（注册中心前置）分包落地，`entity`/`mapper`/`dto`/`config` 为共享层 |
| P1 MCP→MCP 代理转发 | ✅ 透明代理（单下游）+ 聚合代理（多下游 `{name}__` 前缀 + 路由）+ 两段会话 + 鉴权跳过 + session 映射 |
| P2 客户端发现 API | ✅ `/mcp/discovery/**` + `tools/list` 元数据增强 |
| P3 缓存失效分离 | ✅ `ToolChangeNotifier` |
| P4 物理拆分 | ✅ 2026-10-22 落地两服务（S1 数据面 / S2 控制面）+ 共享内核：Maven 三模块、端口化、独立启动类、反向代理按路径分流、双服务部署。详见《服务拆分分析.md》第 9 章 |
| P5 注册中心独立 / 数据面多副本 | ⏳ 未做：状态外置（session/SSE/上游会话）与探活单点仍是前置条件 |
