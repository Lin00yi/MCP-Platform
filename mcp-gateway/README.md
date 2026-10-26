# MCP Gateway

MCP 网关：数据面代理（MCP → MCP / 聚合 / REST 工具）+ 控制面管理 + 客户端发现。
技术栈：Spring Boot 3.5（JDK 17）、MyBatis、MySQL 8、WebClient（WebFlux）。

---

## 1. 模块与职责

```
mcp-gateway/                 # 聚合 POM
├── mcp-shared/              # 共享内核（两个服务都依赖，它不依赖任何一方）
│   └── entity / mapper / dto / exposure / identity / topology(读) / cache / health / config
├── mcp-dataplane/           # S1 数据面 :8081
│   ├── gateway/             # JSON-RPC、聚合、上游代理、动态路由、内部接口
│   ├── access/              # /mcp/e/{endpointKey}
│   ├── discovery/           # /mcp/discovery/**
│   └── config/{filter,session}  # MCP 鉴权 / 会话 / SSE
└── mcp-admin/               # S2 控制面 :8082
    ├── admin/               # CRUD、审批、OpenAPI 导入、账号、审计、健康展示
    ├── admin/topology/      # Namespace / Endpoint 写侧
    ├── admin/security/      # 后台 JWT + RBAC
    └── admin/port/          # 访问数据面的 HTTP 适配器
```

编译期边界：`mcp-shared` 不依赖数据面/控制面；`mcp-dataplane` 与 `mcp-admin` 互不依赖。

配套前端控制台见 `../mcp-console`；架构与接口文档见 `../docs`。

---

## 2. 快速开始（本地）

### 2.1 准备 MySQL

本地使用 MySQL 8（本仓库不提供容器编排）：先执行 `../db/mysql/init.sql`，
再按文件名顺序执行 `../db/mysql/migrations/*.sql`。

### 2.2 本地 Maven 运行

拆分后是**两个进程**（数据面 + 控制面），共用同一个 MySQL：

```bash
mvn -DskipTests package
./run-local.sh both            # dataplane:8081 + admin:8082
# 或分别启动：
./run-local.sh dataplane
./run-local.sh admin
```

`run-local.sh` 会从 `.env` 读取数据库口令、AES 密钥、JWT 密钥，并默认设置
`MCP_INTERNAL_TOKEN`（控制面调用数据面内部接口的共享凭证）。

- 数据面：<http://localhost:8081>；控制面：<http://localhost:8082>
- 首次启动会由 `AdminBootstrap` 创建超管 `admin`，密码取 `MCP_ADMIN_INIT_PASSWORD`
- 控制台：见 `../mcp-console/README.md`

多副本联调（需要本地 Redis）：

```bash
MCP_REDIS_ENABLED=true MCP_REDIS_URL=redis://127.0.0.1:6379/0 ./run-local.sh dataplane
# 另开一个终端起第二个副本：MCP_DATAPLANE_PORT=18081 ...
# 自检：curl -H "X-Internal-Token: $MCP_INTERNAL_TOKEN" localhost:8081/internal/cluster-info
```

> 生产环境必须显式配置 `MCP_SECURITY_SECRETKEY` 与 `MCP_ADMIN_JWT_SECRET`，否则启动失败（防止使用默认密钥）。

---

## 3. 必配环境变量

只保留运维必须关心的变量；完整分层、工具档位与高级属性见
[`../docs/配置说明.md`](../docs/配置说明.md)。

| 变量 | 说明 |
|---|---|
| `MCP_SECURITY_SECRETKEY` | Base64 的 32 字节 AES-256 密钥，用于加密工具鉴权配置 |
| `MCP_ADMIN_JWT_SECRET` | 后台 JWT 签名密钥，>=32 字符 |
| `MCP_ADMIN_JWT_TTL_HOURS` | 后台登录有效期（小时），默认 `168`（7 天），支持 `1`～`168` |
| `MCP_ADMIN_INIT_PASSWORD` | 首次启动初始超管密码，>=8 位 |
| `MCP_INTERNAL_TOKEN` | 控制面 → 数据面 `/internal/**` 的共享凭证；两侧必须一致 |
| `MCP_PUBLIC_BASE_URL` | discovery 返回给客户端的网关绝对地址 |
| `MCP_SWAGGER_ENABLED` | 是否开启接口文档，生产保持 false |
| `MCP_SECURITY_ALLOW_PRIVATE_NETWORK` | 是否允许访问内网（默认 false） |
| `MCP_REDIS_ENABLED` / `MCP_REDIS_URL` | 多副本开关与统一 Redis 地址（兼容旧 `MCP_REDIS_URI`） |
| `MCP_IDENTITY_ENABLED` / `MCP_IDENTITY_MODE` | 身份下传开关与 `OFF/OPTIONAL/REQUIRED` |
| `MCP_IDENTITY_TOKEN_FORMAT` / `MCP_IDENTITY_SIGNING_KEY` | JWS/JWE 与签名私钥 |
| `MCP_IDENTITY_FORWARD_USER_TOKEN` | 是否透传原始用户 token（默认 false） |
| `MCP_TOOLS_PROFILE` | 工具治理档位 `default/compact/large` |
| `MCP_TOOLS_META_ENABLED` | META 元工具应急开关 |
| `MCP_TOOLS_CHUNK_STORE` | `memory`/`redis`（多副本建议 redis） |
| `MCP_EMBEDDING_KEY` | 语义检索 key；不配则降级词法检索 |
| `MCP_DATAPLANE_URL` | 控制面访问数据面的地址（默认 `http://127.0.0.1:8081`） |
| `SERVER_PORT` / `SERVICE` | 同一套 jar 选择数据面/控制面与端口 |
| `MCP_DB_URL` / `MCP_DB_USERNAME` / `MCP_DB_PASSWORD` | 可选，覆盖默认数据库连接 |

> 旧的 `MCP_TOOLS_PAGE_SIZE`、`MCP_TOOLS_PAGINATION_MIN_TOOLS`、`MCP_SESSION_MAX_COUNT` 等高级参数
> 不再出现在 `.env`，但仍可用环境变量或 `--mcp.tools.*` 启动参数覆盖。

## 4. 构建与测试

```bash
mvn test                    # 三模块单元测试（shared 159 / dataplane 63 / admin 38）
mvn -DskipTests package     # 产出 mcp-dataplane/target/mcp-dataplane.jar、mcp-admin/target/mcp-admin.jar
```

两个 jar 用 `SERVICE` 选择启动（`dataplane` 默认 8081 / `admin` 8082）。

---

## 5. 安全基线

- 所有管理接口默认拒绝，权限矩阵见 `AdminPermissionEvaluator`；
- 敏感 GET（apps / tools / service-systems）需要对应管理权限，列表不再返回明文密钥；
- 多租户过滤为空集合时返回空结果（不会退化成全量）；
- MCP 数据面除 `ping`/`notifications/*` 外均需 `x-dsh-auth-token`；应用授权已移除，启用资产对认证用户可见；
- 开启身份下传后，`initialize` 及受保护方法还须带 `x-dsh-auth-token`（用户 token）；不再接受 `userId` 请求头；
- `/mcp/discovery/**` 必须携带 `x-dsh-auth-token`；
- 外部 HTTP 调用统一做 SSRF 校验，并设置连接/响应超时；
- 密码 BCrypt 存储，登录失败限流，改密/禁用后旧 token 立即失效。

---

## 6. 相关文档

- 客户端接入：`../docs/MCP客户端接入指南.md`
- 接口示例：`../docs/接口调用示例.md`
- 架构方案：`../docs/MCP网关架构解耦方案.md`
- 多租户模型：`../docs/MCP网关多租户改造方案.md`
- 安全审查与整改：`../docs/安全整改说明.md`
