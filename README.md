# MCP Gateway

把散落在各业务系统里的 HTTP 接口，统一封装成 MCP Server 提供给 AI 客户端使用；同时提供多租户、授权、审批、审计与可观测能力。

- **数据面**：MCP Streamable HTTP 端点 `/mcp/e/{endpointKey}`，对客户端暴露 `tools/list` / `tools/call` / `prompts` / `resources`
- **控制面**：`/mcp/admin/**` 管理租户、Server、Tool、Prompt、Resource、审批、日志与调用记录
- **控制台**：React + TypeScript 的管理界面（`mcp-console`）

默认 3 个可部署单元：**S1 数据面**、**S2 控制面**、**S3 控制台**；后端共享 `mcp-shared` 内核。

```
   AI 客户端                     S1 mcp-dataplane :8081          上游系统
┌──────────────┐        ┌────────────────────────────┐        ┌──────────────┐
│ DSH Desktop  │        │  /mcp/e/{endpointKey}       │        │  REST API    │
│ Codex CLI    ├───────▶│  /mcp/discovery/**          ├───────▶│  (HTTP 转发) │
│ Claude / Cursor│      │                            │        ├──────────────┤
│ 自研 Agent   │        │  · 鉴权 / 会话 / SSE / 限流  │        │  MCP 上游    │
└──────────────┘        │  · Tool 路由与参数映射       │        └──────────────┘
                        │  · 结果转换与错误归一        │
                        └──────────────┬─────────────┘
                                       │ 只读资产/拓扑；写调用日志
   ┌────────────┐        ┌─────────────▼─────────────┐
   │ 管理控制台  │───────▶│  S2 mcp-admin :8082        │
   │ mcp-console│        │  /mcp/admin/**  /mcp/log/**│
   └────────────┘        └─────────────┬─────────────┘
                                       │ 读写
                                    MySQL 8
```

> **多副本**：`MCP_REDIS_ENABLED=true` 后数据面可水平扩（会话与变更事件走 Redis）；
> 关闭时只能单副本。详见 [`docs/多副本部署.md`](docs/多副本部署.md)。
>
> 拆分说明与路线见 [`docs/服务拆分分析.md`](docs/服务拆分分析.md)。
> 两个服务**互不依赖**：控制面通过 `/internal/**`（`X-Internal-Token` 鉴权）调用数据面的
> 「测试连通性」与「配置变更通知」。

## 特性

| 能力 | 说明 |
|---|---|
| 多租户 | `tenant` → `mcp_server` → `mcp_tool` 三级隔离，管理端默认拒绝 + 越权校验（Namespace/Endpoint 支持业务方归属） |
| 工具接入 | 单接口 REST 生成、**OpenAPI 3.x 批量导入**、本地内置工具、MCP 上游透传 |
| 认证 | 只认 user-center 用户 token（`x-dsh-auth-token`，`dsh_`/PAT）；应用 JWT 已移除；无应用级授权 |
| 工具规模治理 | Endpoint 层 META 合并、检索策略（目录/关键词/BM25/向量/RRF）、游标分页、预算告警、双版本 Schema、超长结果分片、下游熔断 |
| 审批流 | 通用审批矩阵，敏感操作（Server 连接变更、删除、下游凭证修改、用户提权）走审批后再生效 |
| 发现协议 | `/mcp/discovery/servers` 返回当前 token 可见的 Endpoint、`toolCount`、`revision`，支持 `ETag` / `304` |
| 安全 | SSRF 校验、AES-GCM 凭证加密、列表不回传明文凭证、日志脱敏、可选身份下传（JWS/JWE，支持 OFF/OPTIONAL/REQUIRED 灰度） |
| 可观测 | 调用日志 `tool_call_record`、请求日志、健康五态（UP / DEGRADED / DOWN / DISABLED / UNKNOWN）；探活带 `scope=health` 断言 |
| 多副本 | 会话 / 上游会话 / 变更事件可外置到 Redis（`MCP_REDIS_ENABLED`）；探活 Redis 选主（只有一个副本打上游）；30s revision 兜底防事件丢失；单副本无需 Redis |

## 目录结构

```
.
├── mcp-gateway/              # Maven 多模块后端（Spring Boot 3 / JDK 17）
│   ├── mcp-shared/           # 共享内核：shared/entity,mapper,dto,enums,utils + exposure/identity/topology/cache/health
│   ├── mcp-dataplane/        # S1 数据面：gateway(协议/聚合/上游) + access(Endpoint) + discovery
│   │   └── cluster/          # 变更事件总线 / 兜底轮询 / session 与上游会话存储
│   └── mcp-admin/             # S2 控制面：admin(CRUD/审批/OpenAPI/账号) + security + topology 写侧
├── db/mysql/                 # MySQL 初始化脚本与增量迁移（init.sql + migrations/）
├── mcp-console/              # React 18 + Vite 管理控制台（只调 /mcp/admin/**，拆分零改造）
├── tools/                    # DSH 同步器、Codex 桥接、E2E 脚本
└── docs/                     # 架构、接口、接入、安全与审批文档
```

**编译期边界**：`mcp-shared` 不依赖任何一方；`mcp-dataplane` 与 `mcp-admin` 互不依赖。

## 快速开始

### 1. 准备 MySQL

本地使用 MySQL 8（本仓库不提供容器编排）。建库并导入初始化脚本：

```bash
mysql -uroot -p < db/mysql/init.sql
# 增量迁移按文件名顺序执行
for f in db/mysql/migrations/*.sql; do mysql -uroot -p mcp_gateway < "$f"; done
```

### 2. 启动后端（直接跑 jar）

```bash
cd mcp-gateway
cp .env.example .env
# 编辑 .env：数据库口令、AES 密钥、JWT 密钥、初始超管密码、MCP_INTERNAL_TOKEN

mvn -DskipTests package
./run-local.sh both        # dataplane:8081 + admin:8082
# 或分开：./run-local.sh dataplane / ./run-local.sh admin
```

启动后：

- 数据面 S1：<http://127.0.0.1:8081>（MCP 客户端入口）
- 控制面 S2：<http://127.0.0.1:8082>（管理台 API）
- MySQL：`127.0.0.1:3306`
- 首次启动由 `AdminBootstrap` 创建超管 `admin`，密码取 `MCP_ADMIN_INIT_PASSWORD`

> 生产环境必须显式配置 `MCP_SECURITY_SECRETKEY` 与 `MCP_ADMIN_JWT_SECRET`，否则拒绝启动，避免默认密钥。

### 3. 启动控制台

```bash
cd mcp-console
pnpm install
pnpm dev          # http://localhost:5173
```

默认连真实网关；本地 mock 已随应用维度一起下线，`VITE_USE_MOCK=true` 会直接报错。

### 4. 五步接入一个工具

1. 建**业务方**（tenant）
2. 建 **MCP Server**（资产容器，`path` 仅作标识，不再注册 `/mcp/v1` 路由）
3. 建 **Tool**：单接口用「REST 生成」，整份文档用「从 OpenAPI 导入」（预览 → 勾选 → 批量入库）
4. 在 user-center 生成 `dsh_` token（DSH 桌面端）或 PAT（Codex/Cursor/脚本）
5. 建 **Namespace**（工具名前缀）与 **Endpoint**（唯一对外接入点，可绑定多个 Namespace）
6. 客户端携带 `x-dsh-auth-token: <dsh/PAT>` 连接 Endpoint `/mcp/e/{endpointKey}`，工具名格式 `{prefix}__{toolCode}`

## 客户端接入

所有客户端统一走 **Streamable HTTP + `Mcp-Session-Id`**。

> 所有 MCP 方法（含 `initialize`、发现、Endpoint、SSE）都只认 `x-dsh-auth-token`；
> 缺失/无效返回 401，校验服务不可用返回 503。`ping`、`notifications/*` 免认证。

> 客户端统一走 **Endpoint 入口** `/mcp/e/{endpointKey}`：一个 Endpoint 绑定 1..N 个 Namespace，
> 每个 Namespace 包含 1..N 个 MCP Server，工具名 `{namespacePrefix}__{toolCode}`。
> **`/mcp/v1/{serverPath}` 已下线**，未注册路径返回 404。
> 详见 [`docs/Endpoint统一接入.md`](docs/Endpoint统一接入.md)。

| 客户端 | 方式 |
|---|---|
| DSH Desktop | `dsh-mcp-gateway-sync` 插件或 `tools/dsh-mcp-sync.py` 按发现接口自动挂载；Endpoint 模式下工具名 `mcp__gateway__<prefix>__<tool>` |
| Codex / 仅支持 stdio 的客户端 | `tools/codex-pcb-bridge.sh` 做 stdio → HTTP 桥接 |
| Claude Desktop / Cursor / 自研 | 直接配置 HTTP MCP 地址与 `x-dsh-auth-token` 头 |

调用示例与字段说明见 [`docs/MCP客户端接入指南.md`](docs/MCP客户端接入指南.md)、[`docs/DSH接入.md`](docs/DSH接入.md)。

## 接口速览

| 服务 | 分组 | 路径 |
|---|---|---|
| S1 :8081 | MCP 数据面（唯一入口） | `POST /mcp/e/{endpointKey}` |
| S1 :8081 | 客户端发现 | `GET /mcp/discovery/servers` |
| S1 :8081 | 内部接口（仅 admin 调用） | `POST /internal/config-changed`、`POST /internal/tools/test-connect` |
| S2 :8082 | 管理面 | `/mcp/admin/**`（租户 / Server / Tool / Prompt / Resource / Namespace / Endpoint / 审批） |
| S2 :8082 | 运维日志 | `/mcp/log/**` |
| S2 :8082 | OpenAPI 导入 | `POST /mcp/admin/tools/openapi/preview`、`POST /mcp/admin/tools/openapi/import` |
| 两者 | 文档 | `/v3/api-docs`、`/swagger-ui.html`（`MCP_SWAGGER_ENABLED=true` 时） |

> 生产统一入口由反向代理按路径分流：`/mcp/admin/*`、`/mcp/log/*`、`/api/*` → admin（:8082）；
> 其余 `/mcp/*` → dataplane（:8081）；`/internal/**` 不对外暴露。

完整请求/响应示例见 [`docs/接口调用示例.md`](docs/接口调用示例.md)。

## 关键环境变量

生产 `.env` 只保留约 20 个运维变量；分页、检索、分片、探活、熔断、embedding 模型等内部参数
已有安全默认值，不再暴露为环境变量。完整分层与高级属性见 [`docs/配置说明.md`](docs/配置说明.md)。

| 变量 | 说明 |
|---|---|
| `MCP_SECURITY_SECRETKEY` | Base64 的 32 字节 AES-256 密钥，加密工具凭证 |
| `MCP_ADMIN_JWT_SECRET` | 后台 JWT 签名密钥，≥32 字符 |
| `MCP_ADMIN_JWT_TTL_HOURS` | 后台登录有效期（小时），默认 `168`（7 天），最长 7 天 |
| `MCP_ADMIN_INIT_PASSWORD` | 初始超管密码，≥8 位 |
| `MCP_INTERNAL_TOKEN` | 控制面 → 数据面 `/internal/**` 的共享凭证 |
| `MCP_PUBLIC_BASE_URL` | discovery 返回给客户端的网关绝对地址 |
| `MCP_SWAGGER_ENABLED` | 是否开放接口文档，生产保持 `false` |
| `MCP_SECURITY_ALLOW_PRIVATE_NETWORK` | 是否允许访问内网上游，默认 `false` |
| `MCP_REDIS_ENABLED` / `MCP_REDIS_URL` | 多副本开关与统一 Redis 地址（兼容旧 `MCP_REDIS_URI`） |
| `MCP_IDENTITY_ENABLED` | 身份下传总开关，默认 `false` |
| `MCP_IDENTITY_MODE` | `OFF` / `OPTIONAL` / `REQUIRED`；Server 可用 `identity_mode` 覆盖 |
| `MCP_IDENTITY_SIGNING_KEY` | 网关 RSA 私钥；生产必须固定配置 |
| `MCP_IDENTITY_TOKEN_FORMAT` | `JWS`（只签名）或 `JWE`（需下游公钥） |
| `MCP_IDENTITY_FORWARD_USER_TOKEN` | 是否透传原始用户 token，默认 `false` |
| `MCP_TOOLS_PROFILE` | 工具治理档位：`default` / `compact` / `large` |
| `MCP_TOOLS_META_ENABLED` | META 元工具应急开关，默认 `true` |
| `MCP_TOOLS_CHUNK_STORE` | 超长结果分片：`memory`（默认）/ `redis` |
| `MCP_EMBEDDING_KEY` | 语义检索 key；不配则自动降级词法检索 |
| `MCP_AUTH_STATIC_TOKENS` | **仅开发/E2E**；生产配置会启动失败 |
| `MCP_DB_URL` | 可选，改用外部 MySQL 时覆盖 JDBC URL |
| `MCP_DATAPLANE_URL` | 控制面访问数据面地址，默认 `http://127.0.0.1:8081` |
| `SERVER_PORT` / `SERVICE` | 同一套 jar 选择数据面/控制面与端口 |

## 开发与测试

```bash
# 后端（多模块：shared / dataplane / admin）
cd mcp-gateway
mvn test                     # 鉴权矩阵 / JWT / SSRF / 路径校验 / 加解密 / OpenAPI 解析
mvn -DskipTests package      # mcp-dataplane/target/mcp-dataplane.jar、mcp-admin/target/mcp-admin.jar

# 前端
cd mcp-console
pnpm typecheck
pnpm build

# 本地全量端到端（需先启动两个后端；用静态 token 启动数据面）
cd ..
MCP_AUTH_STATIC_TOKENS='e2e-token|u-e2e|E2E 用户|C-E2E|tool:read,tool:write' ./mcp-gateway/run-local.sh both
MCP_ADMIN_PASSWORD=<本地 admin 密码> MCP_E2E_USER_TOKEN=e2e-token \
  MCP_INTERNAL_TOKEN=<本地 internal token> python3 tools/full-e2e-test.py
MCP_ADMIN_PASSWORD=<本地 admin 密码> python3 tools/rbac-approval-test.py
```

E2E 结果写入 `mcp-gateway/target/e2e-report.json`。生产环境由 user-center 提供 `UserTokenStore`，不需要 `MCP_AUTH_STATIC_TOKENS`。

## 文档

- **架构与流程图册**（SVG + 说明）：[`docs/architecture/README.md`](docs/architecture/README.md)
- [配置说明（环境变量分层 / 工具档位 / 高级属性）](docs/配置说明.md)
- [服务拆分分析（S1 数据面 / S2 控制面 / 共享内核）](docs/服务拆分分析.md)
- [数据面多副本部署（Redis / 扩容 / 自检）](docs/多副本部署.md)
- [待办清单（未完成事项）](docs/待办清单.md)
- [MCP 客户端接入指南](docs/MCP客户端接入指南.md)
- [工具规模治理（分页 / META 元工具）](docs/工具规模治理.md)
- [客户端兼容矩阵](docs/客户端兼容矩阵.md)
- [工具链路优化实施报告](docs/工具链路优化实施报告.md)
- [Prompt 与 Resource 治理](docs/Prompt与Resource治理.md)
- [接口调用示例](docs/接口调用示例.md)
- [DSH Desktop 接入](docs/DSH接入.md)
- [架构解耦方案](docs/MCP网关架构解耦方案.md)
- [身份下传与上游鉴权](docs/身份下传与上游鉴权改造方案.md)
- [审批矩阵](docs/审批矩阵.md) ｜ [安全整改说明](docs/安全整改说明.md)

## 安全说明

- 仓库不包含任何真实口令或密钥；`.env`、`.codex/` 等均已忽略
- 生产部署请更换全部默认密钥，并通过密钥管理服务注入
- 内网访问需显式开启 `MCP_SECURITY_ALLOW_PRIVATE_NETWORK`，并自行评估 SSRF 风险
