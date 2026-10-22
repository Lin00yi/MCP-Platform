# MCP Gateway

MCP 网关：数据面代理（MCP → MCP / 聚合 / REST 工具）+ 控制面管理 + 客户端发现。
技术栈：Spring Boot 2.7、MyBatis、MySQL 8、WebClient（WebFlux）。

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

### 2.1 Docker Compose（推荐）

```bash
cp .env.example .env
# 按需修改 .env 中的数据库口令、AES 密钥、JWT 密钥、初始超管密码
docker compose up -d --build
```

- 网关：<http://localhost:8081>
- MySQL：`127.0.0.1:3307`（仅本机）
- 首次启动会由 `AdminBootstrap` 创建超管 `admin`，密码取 `MCP_ADMIN_INIT_PASSWORD`
- 控制台：见 `../mcp-console/README.md`

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

多副本联调（需要本地 Redis）：

```bash
MCP_REDIS_ENABLED=true MCP_REDIS_URI=redis://127.0.0.1:6379/0 ./run-local.sh dataplane
# 另开一个终端起第二个副本：MCP_DATAPLANE_PORT=18081 ...
# 自检：curl -H "X-Internal-Token: $MCP_INTERNAL_TOKEN" localhost:8081/internal/cluster-info
```

> 生产环境必须显式配置 `MCP_SECURITY_SECRETKEY` 与 `MCP_ADMIN_JWT_SECRET`，否则启动失败（防止使用默认密钥）。

---

## 3. 必配环境变量

| 变量 | 说明 |
|---|---|
| `MCP_DB_URL` / `MCP_DB_USERNAME` / `MCP_DB_PASSWORD` | 数据库连接 |
| `MCP_SECURITY_SECRETKEY` | Base64 的 32 字节 AES-256 密钥，用于加密工具鉴权配置 |
| `MCP_ADMIN_JWT_SECRET` | 后台 JWT 签名密钥，>=32 字符 |
| `MCP_ADMIN_INIT_PASSWORD` | 首次启动初始超管密码，>=8 位 |
| `MCP_SWAGGER_ENABLED` | 是否开启 `/v3/api-docs`、Swagger UI，生产默认 false |
| `MCP_SECURITY_ALLOW_PRIVATE_NETWORK` | 是否允许访问内网（默认 false；下游均为内网时才开启） |
| `MCP_AUTH_USER_TOKEN_HEADER` | 用户 token 请求头（默认 `x-dsh-auth-token`） |
| `MCP_AUTH_USER_TOKEN_CACHE_TTL_SECONDS` | 身份缓存 TTL（默认 30s）；校验由 user-center 本地 `UserTokenStore` 提供 |
| `MCP_IDENTITY_ENABLED` | 是否签发下游内部断言（不是认证开关） |
| `MCP_IDENTITY_FORWARD_USER_TOKEN` | 是否把原始用户 token 透传给下游 MCP Server（默认 false） |
| `MCP_IDENTITY_TOKEN_FORMAT` | 网关来源断言 JWS/JWE；pass-through 推荐 JWS |
| `MCP_IDENTITY_SIGNING_KEY` | 网关 RSA 签名私钥；生产必须固定配置 |
| `MCP_IDENTITY_USER_TOKEN_HEADER` | 透传给下游的用户 token 头名（默认 `x-dsh-auth-token`） |
| `MCP_TOOLS_PAGE_SIZE` | `tools/list` 单页工具数（默认 200） |
| `MCP_TOOLS_WARN_THRESHOLD` / `MCP_TOOLS_MAX_THRESHOLD` | 可见工具数告警/硬阈值（默认 100 / 500） |
| `MCP_TOOLS_META_ENABLED` | META 元工具模式总开关（默认 true；false 时全部按 DIRECT） |
| `MCP_TOOLS_PAGINATION_MIN_TOOLS` | 低于该工具数整包返回（默认 500） |
| `MCP_TOOLS_PAGINATION_CLIENTS` | 分页客户端白名单（默认 codex,claude,dsh,cursor,workbuddy） |
| `MCP_EMBEDDING_HOST` / `MCP_EMBEDDING_KEY` | 语义检索 embedding 服务；**只属于数据面**（控制面通过内部接口触发重建） |
| `MCP_ASSET_PAGINATION_MIN` | Prompt/Resource 分页门槛（默认 500） |
| `MCP_PROMPT_MAX_BYTES` / `MCP_RESOURCE_MAX_BYTES` | Prompt/Resource 正文上限（默认 256KB / 1MB） |
| `MCP_INTERNAL_TOKEN` | 控制面 → 数据面 `/internal/**` 的共享凭证；两侧必须一致 |
| `MCP_DATAPLANE_URL` | 控制面访问数据面的地址（容器内 `http://dataplane:8081`） |
| `SERVER_PORT` | 服务端口：数据面 8081 / 控制面 8082 |
| `MCP_REDIS_ENABLED` / `MCP_REDIS_URI` | 数据面多副本：会话与变更事件走 Redis；关闭时只能单副本 |
| `MCP_REVISION_POLL_ENABLED` / `MCP_REVISION_POLL_MS` | 变更兜底轮询（默认开，30s） |

其余可选项见 `application.yml`、`application-{dev,test,prod}.yml`；工具治理细节见
[`../docs/工具规模治理.md`](../docs/工具规模治理.md)。

---

## 4. 构建与测试

```bash
mvn test                    # 三模块单元测试（shared 111 / dataplane 63 / admin 28）
mvn -DskipTests package     # 产出 mcp-dataplane/target/mcp-dataplane.jar、mcp-admin/target/mcp-admin.jar
```

Docker 镜像（非 root 运行；一个镜像两个 jar，用 `SERVICE` 选择）：

```bash
mvn -DskipTests package
docker build -t mcp-gateway:dev .
# SERVICE=dataplane（默认，8081） / SERVICE=admin（8082）
```

---

## 5. 安全基线

- 所有管理接口默认拒绝，权限矩阵见 `AdminPermissionEvaluator`；
- 敏感 GET（apps / tools / service-systems）需要对应管理权限，列表不再返回明文密钥；
- 多租户过滤为空集合时返回空结果（不会退化成全量）；
- MCP 数据面除握手方法外均需 app token，并按 server/tool/prompt/resource 白名单过滤；
- 开启身份下传后，`initialize` 及受保护方法还须带 `x-dsh-auth-token`（用户 token）；不再接受 `userId` 请求头；
- `/mcp/discovery/**` 必须携带 app token；
- 外部 HTTP 调用统一做 SSRF 校验，并设置连接/响应超时；
- 密码 BCrypt 存储，登录失败限流，改密/禁用后旧 token 立即失效。

---

## 6. 相关文档

- 客户端接入：`../docs/MCP客户端接入指南.md`
- 接口示例：`../docs/接口调用示例.md`
- 架构方案：`../docs/MCP网关架构解耦方案.md`
- 多租户模型：`../docs/MCP网关多租户改造方案.md`
- 安全审查与整改：`../docs/代码审查报告.md`
