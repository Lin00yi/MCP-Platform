# MCP Gateway

把散落在各业务系统里的 HTTP 接口，统一封装成 MCP Server 提供给 AI 客户端使用；同时提供多租户、授权、审批、审计与可观测能力。

- **数据面**：MCP Streamable HTTP 端点 `/mcp/v1/{serverKey}`，对客户端暴露 `tools/list` / `tools/call` / `prompts` / `resources`
- **控制面**：`/mcp/admin/**` 管理租户、Server、Tool、Prompt、Resource、审批、日志与调用记录
- **控制台**：React + TypeScript 的管理界面（`mcp-console`）

默认 3 个可部署单元：**S1 数据面**、**S2 控制面**、**S3 控制台**；后端共享 `mcp-shared` 内核。

```
   AI 客户端                     S1 mcp-dataplane :8081          上游系统
┌──────────────┐        ┌────────────────────────────┐        ┌──────────────┐
│ DSH Desktop  │        │  /mcp/e/{endpointKey}       │        │  REST API    │
│ Codex CLI    ├───────▶│  /mcp/v1/{serverPath}       ├───────▶│  (HTTP 转发) │
│ Claude / Cursor│      │  /mcp/discovery/**          │        ├──────────────┤
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
| 多租户 | `tenant` → `mcp_server` → `mcp_tool` 三级隔离，管理端默认拒绝 + 越权校验 |
| 工具接入 | 单接口 REST 生成、**OpenAPI 3.x 批量导入**、本地内置工具、MCP 上游透传 |
| 认证 | 只认 user-center 用户 token（`x-dsh-auth-token`，`dsh_`/PAT）；应用 JWT 已移除；无应用级授权 |
| 工具规模治理 | Endpoint 层 META 合并、访问模式（白名单/全部）、检索策略（目录/关键词/BM25/向量/RRF）、游标分页、预算告警、双版本 Schema、超长结果分片、下游熔断 |
| 审批流 | 通用审批矩阵，敏感操作（工具/授权变更、凭证修改）走审批后再生效 |
| 发现协议 | `/mcp/discovery/servers` 返回当前 token 可见的 Server（默认聚合为 1 条）、`toolCount`、`revision`，支持 `ETag` / `304` |
| 安全 | SSRF 校验、AES-GCM 凭证加密、日志脱敏、可选身份下传（JWE JWT） |
| 可观测 | 调用日志 `tool_call_record`、请求日志、健康五态（UP / DEGRADED / DOWN / DISABLED / UNKNOWN） |
| 多副本 | 会话 / 上游会话 / 变更事件可外置到 Redis（`MCP_REDIS_ENABLED`）；探活 Redis 选主（只有一个副本打上游）；30s revision 兜底防事件丢失；单副本无需 Redis |

## 目录结构

```
.
├── mcp-gateway/              # Maven 多模块后端（Spring Boot 2.7 / JDK 17）
│   ├── mcp-shared/           # 共享内核：entity/mapper/dto/exposure/identity/topology/cache/health
│   ├── mcp-dataplane/        # S1 数据面：gateway(协议/聚合/上游) + access(Endpoint) + discovery
│   │   └── cluster/          # 变更事件总线 / 兜底轮询 / session 与上游会话存储
│   └── mcp-admin/            # S2 控制面：admin(CRUD/审批/OpenAPI/账号) + security + topology 写侧
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

默认连真实网关；离线演示可设 `VITE_USE_MOCK=true`。

### 4. 五步接入一个工具

1. 建**业务方**（tenant）
2. 建 **MCP Server**，得到端点 `/mcp/v1/{serverKey}`
3. 建 **Tool**：单接口用「REST 生成」，整份文档用「从 OpenAPI 导入」（预览 → 勾选 → 批量入库）
4. 在 user-center 生成 `dsh_` token（DSH 桌面端）或 PAT（Codex/Cursor/脚本）
5. 建 **Namespace**（工具名前缀）与 **Endpoint**（对外接入点，可绑定多个 Namespace）
6. 客户端携带 `x-dsh-auth-token: <dsh/PAT>` 连接 Endpoint `/mcp/e/{endpointKey}`，工具名格式 `{prefix}__{toolCode}`

## 客户端接入

所有客户端统一走 **Streamable HTTP + `Mcp-Session-Id`**。

> 所有 MCP 方法（含 `initialize`、发现、Endpoint、SSE）都只认 `x-dsh-auth-token`；
> 缺失/无效返回 401，校验服务不可用返回 503。`ping`、`notifications/*` 免认证。

> 客户端统一走 **Endpoint 入口** `/mcp/e/{endpointKey}`：一个 Endpoint 绑定 1..N 个 Namespace，
> 每个 Namespace 包含 1..N 个 MCP Server，工具名 `{namespacePrefix}__{toolCode}`。
> 旧的 `/mcp/v1/aggregate` 已下线；`/mcp/v1/{serverPath}` 仅保留兼容。
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
| S1 :8081 | MCP 数据面（Endpoint，默认） | `POST /mcp/e/{endpointKey}` |
| S1 :8081 | MCP 数据面（单 Server，旧链路） | `POST /mcp/v1/{serverPath}` |
| S1 :8081 | 客户端发现 | `GET /mcp/discovery/servers` |
| S1 :8081 | 内部接口（仅 admin 调用） | `POST /internal/config-changed`、`POST /internal/tools/test-connect` |
| S2 :8082 | 管理面 | `/mcp/admin/**`（租户 / Server / Tool / Prompt / Resource / Namespace / Endpoint / 审批） |
| S2 :8082 | 运维日志 | `/mcp/log/**` |
| S2 :8082 | OpenAPI 导入 | `POST /mcp/admin/tools/openapi/preview`、`POST /mcp/admin/tools/openapi/import` |
| 两者 | 文档 | `/v3/api-docs`、`/swagger-ui.html`（`MCP_SWAGGER_ENABLED=true` 时） |

> 对外统一入口由反向代理分流：`/mcp/admin/*`、`/mcp/log/*`、`/api/*` → admin；
> 其余 `/mcp/*` → dataplane；`/internal/**` 不对外暴露。

完整请求/响应示例见 [`docs/接口调用示例.md`](docs/接口调用示例.md)。

## 关键环境变量

| 变量 | 说明 |
|---|---|
| `MCP_DB_URL` / `MCP_DB_USERNAME` / `MCP_DB_PASSWORD` | 数据库连接 |
| `MCP_SECURITY_SECRETKEY` | Base64 的 32 字节 AES-256 密钥，加密工具凭证 |
| `MCP_ADMIN_JWT_SECRET` | 后台 JWT 签名密钥，≥32 字符 |
| `MCP_ADMIN_INIT_PASSWORD` | 初始超管密码，≥8 位 |
| `MCP_SWAGGER_ENABLED` | 是否开放接口文档，生产建议 `false` |
| `MCP_SECURITY_ALLOW_PRIVATE_NETWORK` | 是否允许访问内网上游，默认 `false` |
| `MCP_AUTH_USER_TOKEN_HEADER` | 用户 token 请求头，默认 `x-dsh-auth-token` |
| `MCP_AUTH_USER_TOKEN_CACHE_TTL_SECONDS` | 身份缓存 TTL，默认 `30`；校验由 user-center 本地 `UserTokenStore` 提供 |
| `MCP_IDENTITY_ENABLED` | 是否签发下游内部断言（不是认证开关），默认 `false` |
| `MCP_IDENTITY_FORWARD_USER_TOKEN` | 是否把原始用户 token 透传给下游 MCP Server，默认 `false` |
| `MCP_IDENTITY_TOKEN_FORMAT` | 网关来源断言格式：`JWS`（pass-through 推荐）或 `JWE` |
| `MCP_IDENTITY_SIGNING_KEY` | 网关 RSA 私钥（Base64 PKCS#8 或 PEM）；生产必须固定 |
| `MCP_IDENTITY_USER_TOKEN_HEADER` | 透传给下游的用户 token 头名，默认 `x-dsh-auth-token` |
| `MCP_TOOLS_PAGE_SIZE` | `tools/list` 单页工具数，默认 `200` |
| `MCP_TOOLS_WARN_THRESHOLD` / `MCP_TOOLS_MAX_THRESHOLD` | 可见工具数告警/硬阈值，默认 `100` / `500` |
| `MCP_TOOLS_META_ENABLED` | META 元工具模式总开关，默认 `true`（`false` 时全部按 DIRECT） |
| `MCP_TOOLS_PAGINATION_MIN_TOOLS` | 低于该工具数整包返回，默认 `500` |
| `MCP_TOOLS_PAGINATION_CLIENTS` | 允许分页的客户端名称子串，默认 `codex,claude,dsh,cursor,workbuddy` |
| `MCP_EMBEDDING_HOST` / `MCP_EMBEDDING_KEY` | 语义检索 embedding 服务；**只配在数据面**，控制面不再持有该凭证；不配置时自动降级词法检索 |
| `MCP_ASSET_PAGINATION_MIN` | Prompt/Resource 分页门槛，默认 `500` |
| `MCP_PROMPT_MAX_BYTES` / `MCP_RESOURCE_MAX_BYTES` | Prompt/Resource 正文上限，默认 256KB / 1MB |
| `MCP_TOOL_RESULT_MAX_BYTES` | tools/call 返回文本上限，默认 1MB |
| `MCP_INTERNAL_TOKEN` | 控制面 → 数据面 `/internal/**` 的共享凭证；两侧必须一致，空值时数据面只接受回环访问 |
| `MCP_REDIS_ENABLED` / `MCP_REDIS_URI` | 多副本开关与地址；关闭时会话/事件都在进程内，只能单副本 |
| `MCP_REVISION_POLL_ENABLED` / `MCP_REVISION_POLL_MS` | 变更兜底轮询（默认开，30s），Redis 丢消息时自愈 |
| `MCP_DATAPLANE_URL` | 控制面访问数据面的地址，默认 `http://127.0.0.1:8081`（容器内为 `http://dataplane:8081`） |
| `SERVER_PORT` | 服务端口：数据面 8081 / 控制面 8082（同一镜像用 `SERVICE` 选择 jar） |

## 开发与测试

```bash
# 后端（多模块：shared / dataplane / admin）
cd mcp-gateway
mvn test                     # 鉴权矩阵 / JWT / SSRF / 路径校验 / 加解密 / OpenAPI 解析
mvn -DskipTests package      # mcp-dataplane/target/mcp-dataplane.jar、mcp-admin/target/mcp-admin.jar

# 前端
cd mcp-console
pnpm build

# 本地全量端到端（需先启动两个后端 + 控制台）
cd ..
MCP_E2E_BASE=http://127.0.0.1:8081 MCP_E2E_ADMIN_BASE=http://127.0.0.1:8082 \
  MCP_ADMIN_PASSWORD=<本地 admin 密码> python3 tools/full-e2e-test.py
MCP_ADMIN_PASSWORD=<本地 admin 密码> python3 tools/rbac-approval-test.py
```

测试脚本见 `tools/` 目录。

## 文档

- **架构与流程设计图册**（本地生成，不随仓库提交）：[HTML，12 张图](docs/architecture/index.html) ｜ [PDF（A3 横向）](docs/architecture/MCP-Gateway-架构图册.pdf) ｜ [Markdown / Mermaid 版](docs/architecture/README.md)
- **工具链路优化图册**（2026-10，A3 横向 5 页）：[HTML](docs/architecture/tool-optimization.html) ｜ [PDF](docs/architecture/MCP-Gateway-工具链路优化图册.pdf) ｜ [SVG](docs/architecture/13-tool-chain-optimized.svg)
- [服务拆分分析（S1 数据面 / S2 控制面 / 共享内核）](docs/服务拆分分析.md)
- [数据面多副本部署（Redis / 扩容 / 自检）](docs/多副本部署.md)
- [MCP 客户端接入指南](docs/MCP客户端接入指南.md)
- [工具规模治理（白名单 / 分页 / META 元工具）](docs/工具规模治理.md)
- [客户端兼容矩阵](docs/客户端兼容矩阵.md)
- [工具链路优化实施报告](docs/工具链路优化实施报告.md)
- [Prompt 与 Resource 治理](docs/Prompt与Resource治理.md)
- [接口调用示例](docs/接口调用示例.md)
- [DSH Desktop 接入](docs/DSH接入.md)
- [多租户改造方案](docs/MCP网关多租户改造方案.md)
- [架构解耦方案](docs/MCP网关架构解耦方案.md)
- [身份下传与上游鉴权](docs/身份下传与上游鉴权改造方案.md)
- [审批矩阵](docs/审批矩阵.md) ｜ [安全整改说明](docs/安全整改说明.md)

## 安全说明

- 仓库不包含任何真实口令或私钥；`.env`、`*.pem`、`.codex/` 等已由 `.gitignore` 排除
- 所有密钥通过环境变量注入，切勿提交到仓库
- 内网访问需显式开启 `MCP_SECURITY_ALLOW_PRIVATE_NETWORK`，并自行评估 SSRF 风险
