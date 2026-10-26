# MCP 网关多租户架构改造方案

> **历史基线说明**：§1「现状与问题」描述的是改造前的旧模型（`tenant_api` / `method_def` / `enn_app_info`），仅作历史参考；
> 当前模型见 `db/mysql/init.sql` 与控制台。文中应用授权（`appId`）相关设计已随应用维度下线，现行多租户拓扑以
> Namespace / Endpoint 的 `tenant_id` 归属为准（见 [`配置说明.md`](配置说明.md) 4.3.1）。

> 目标：把「业务方」和「MCP Server」从当前的 1:1 强耦合中拆开，形成 **业务方 → MCP Server(s) → tool/prompt/resource** 三层模型，并把应用授权从「应用 × 工具」升级为「应用 × Server」为主、可选「应用 × 工具」为辅。

---

## 1. 现状与问题

当前核心表关系（见 `db/mysql/init.sql`）：

```
tenant_api（= 业务方 = MCP server，1:1）
  ├─ name      → 业务方名
  ├─ path      → MCP 端点（对外地址）
  ├─ status    → 是否注册路由
  └─ res_json  → initialize 能力声明

method_def（= tool，tenant_id 归属）
tenant_method_rel（= tool 归属租户的"第二套"关系，与 method_def.tenant_id 保持一致）
enn_app_info（= 应用/客户端）
mcp_sys_method_rel（= 应用 × tool 授权，method_id = method_def.id 或 "*"）
service_system（= 下游系统，SK_EXCHANGE 兜底 auth_url）
```

存在的问题：

| # | 问题 | 影响 |
|---|---|---|
| 1 | **业务方与 MCP server 强耦合 1:1** | 一个业务方无法对外暴露多个 server（如 CRM 工具集、报表工具集分两个端点） |
| 2 | **授权粒度在 tool，且不感知 server** | `McpAuthServiceImpl.checkPermission(appId, toolName)` 签名里没有 server/租户；`*` 是全局通配，无法表达「应用能用 server X 全部、但不能碰 server Y」 |
| 3 | **prompt / resource 未实现** | `resources/list` 返回 null，`prompts/list`、`resources/templates/list` 无实现分支；只有 tool 是完整的 |
| 4 | **`service_system` 语义被混用** | 注释写「id 对应 method_def.type」，但 `method_def.type` 又是「0 本地 / 1 HTTP 转发」，两套语义复用同一字段，容易错配 auth_url |
| 5 | **tool 状态过滤不完整** | `tools/list` 过滤 `status=1`，但 `tools/call` 的 `selectOneByTenantIdAndCode` 不过滤 status，被禁用的 tool 仍可被直接调用 |

---

## 2. 目标架构模型

```
业务方 tenant
  └── MCP Server（1..N，各自 path + capabilities + status）
        ├── tool（status 控制可用）
        ├── prompt（status 控制可用）
        └── resource（status 控制可用）

应用 app（客户端，enn_app_info）
  └── app_server_rel（授权：app × server，默认 server 内全放行）
        └── app_tool_rel（可选：server 内的 tool 级例外/细粒度，兼容现状跨租户授权）
```

**两层级联控制语义（关键设计，tool 授权依赖 server 授权）**：

- **第一层 谁能用 = app × server**（`app_server_rel` 主授权）：应用被授权某个 server，才能连该 server 并调用其下工具。
- **第二层 能细化到哪些工具 = app × tool 白名单**（`app_tool_rel`，可选）：在已授权的 server 内，若存在白名单则仅放行白名单内工具；无白名单则 server 内全量放行。
- **可用性 = tool/prompt/resource 各自的 `status`**：一个工具下线（`status=0`），对所有能连到该 server 的应用都不可见、不可调用。

> 级联不变量：`app_tool_rel` 只有在对应 `app_server_rel` 已授权时才生效；「授予工具」会自动补齐其所属 server 的授权。旧 seed 里的跨租户 tool 授权（如 `greatgas→tool12`）迁移时拆为「server 授权 + tool 白名单」两条，语义不变且更显式。详见 §8 迁移。

---

## 3. 数据模型改造（DDL）

### 3.1 新增 / 改造表

```sql
-- ============ 业务方（从 tenant_api 拆出） ============
CREATE TABLE tenant (
  id INT NOT NULL AUTO_INCREMENT COMMENT '业务方主键',
  name VARCHAR(128) COMMENT '业务方名称',
  status INT DEFAULT 1 COMMENT '1启用 0禁用',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ MCP Server（N:1 归属业务方） ============
CREATE TABLE mcp_server (
  id INT NOT NULL AUTO_INCREMENT,
  tenant_id INT NOT NULL COMMENT '归属业务方',
  name VARCHAR(128) COMMENT 'server 名称（对外展示）',
  path VARCHAR(255) COMMENT 'MCP 端点路径（动态注册用）',
  status INT DEFAULT 1 COMMENT '1启用(注册) 0禁用(下线)',
  capabilities_json LONGTEXT COMMENT 'initialize 能力声明（原 tenant_api.res_json）',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  UNIQUE KEY uk_path (path),
  KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 工具（从 method_def 演化，归属从 tenant_id → server_id） ============
CREATE TABLE mcp_tool (
  id INT NOT NULL AUTO_INCREMENT,
  server_id INT NOT NULL COMMENT '归属 server',
  name VARCHAR(255) COMMENT '工具说明',
  code VARCHAR(255) COMMENT '工具名（MCP tool name，全局唯一）',
  type INT COMMENT '执行器：0本地 1HTTP转发',
  system_id INT NULL COMMENT '关联 service_system（SK_EXCHANGE 兜底 auth_url，修正语义混用）',
  status INT DEFAULT 1 COMMENT '1启用 0禁用',
  service_url VARCHAR(500) COMMENT '本地=类名.方法名 远程=URL',
  request_type VARCHAR(10) COMMENT 'GET/POST',
  need_token INT DEFAULT 0,
  body_type INT DEFAULT 0 COMMENT '0:JSON 1:form',
  auth_type VARCHAR(32) COMMENT 'NONE/SK_EXCHANGE/API_KEY/BEARER',
  auth_config LONGTEXT,
  param_mapping LONGTEXT,
  res_json LONGTEXT COMMENT '工具描述 + inputSchema',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code (code),
  KEY idx_server (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ prompt / resource（新增） ============
CREATE TABLE mcp_prompt (
  id INT NOT NULL AUTO_INCREMENT,
  server_id INT NOT NULL,
  name VARCHAR(255),
  status INT DEFAULT 1,
  res_json LONGTEXT COMMENT 'prompt 描述 + arguments schema',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_server (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mcp_resource (
  id INT NOT NULL AUTO_INCREMENT,
  server_id INT NOT NULL,
  uri VARCHAR(500) COMMENT '资源 URI（MCP 协议用）',
  name VARCHAR(255),
  status INT DEFAULT 1,
  res_json LONGTEXT COMMENT '资源描述 + 模板定义',
  create_time DATETIME,
  update_time DATETIME,
  PRIMARY KEY (id),
  KEY idx_server (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 应用 × server 授权（主授权粒度） ============
CREATE TABLE app_server_rel (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(128) NOT NULL,
  server_id INT NOT NULL,
  status TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_server (app_id, server_id),
  KEY idx_app (app_id),
  KEY idx_server (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 应用 × tool 细粒度授权（可选，兼容现状） ============
CREATE TABLE app_tool_rel (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(128) NOT NULL,
  tool_id INT NOT NULL,
  status TINYINT DEFAULT 1,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_tool (app_id, tool_id),
  KEY idx_app (app_id),
  KEY idx_tool (tool_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.2 可废弃 / 保留的表

| 表 | 处置 |
|---|---|
| `tenant_api` | **废弃**，拆为 `tenant` + `mcp_server` |
| `tenant_method_rel` | **废弃**，tool 归属由 `mcp_tool.server_id` 直接表达（现状它只是 `method_def.tenant_id` 的冗余同步） |
| `mcp_sys_method_rel` | **废弃**，拆为 `app_server_rel`（主）+ `app_tool_rel`（可选） |
| `method_def` | **改造** → `mcp_tool` |
| `enn_app_info` | 保留不变 |
| `service_system` | 保留；`mcp_tool.system_id` 显式关联，修正原「用 type 当主键」的混用 |
| `tool_call_record` / `mcp_request_log` | 保留；`tenant_id` 建议补 `server_id` 列用于日志下钻 |

---

## 4. 后端改造点清单

### 4.1 Entity / Mapper 层

| 文件（现状） | 改动 |
|---|---|
| `entity/TenantApi.java` | 拆分 → `entity/Tenant.java` + `entity/McpServer.java` |
| `entity/MethodDef.java` | 改造 → `entity/McpTool.java`（`tenantId` → `serverId`，新增 `systemId`） |
| `entity/McpSysMethodRel.java` | 拆分 → `entity/AppServerRel.java` + `entity/AppToolRel.java` |
| `entity/TenantMethodRel.java` | 删除 |
| 新增 | `entity/McpPrompt.java`、`entity/McpResource.java` |
| `mapper/TenantApiMapper.java` + xml | 改造 → `McpServerMapper`（`selectOneByPath`、`getAllByStatus`、`selectAll` 语义保留） |
| `mapper/MethodDefMapper.java` + xml | 改造：`selectAllByTenantId` → `selectAllByServerId`（保持 `status=1`）；`selectOneByTenantIdAndCode` → `selectOneByServerIdAndCode`（**补 `status=1` 过滤**，修复问题 #5） |
| `mapper/McpSysMethodRelMapper.java` + xml | 拆分 → `AppServerRelMapper` + `AppToolRelMapper` |
| `mapper/TenantMethodRelMapper.java` + xml | 删除 |
| 新增 | `McpPromptMapper`、`McpResourceMapper` |

### 4.2 Service 层

| 文件 | 改动 |
|---|---|
| `service/impl/McpServiceImpl.java` | `buildMcpInitializeResult` / `buildToolListResult` / `toolsCall` 中 `tenantApi` → `mcpServer`（`selectOneByPath` → `selectOneByPath`）；新增 `resources/list`、`prompts/list`、`resources/templates/list` 分支；`resolveAuth` 中 `selectByPrimaryKey(methodDef.getType())` → `selectByPrimaryKey(tool.getSystemId())` |
| `service/impl/McpAuthServiceImpl.java` | `checkPermission(appId, toolName)` → 两级：`checkServerPermission(appId, serverId)`（查 `app_server_rel`）+ 可选 `checkToolPermission(appId, toolId)`（查 `app_tool_rel`） |
| `service/AdminAuthService` + impl | 数据隔离：`visibleTenantIds()` 逻辑适配「tenant → server」映射，返回可见 `server_id` 列表 |
| 新增 | `McpPromptService` / `McpResourceService`（若实现 prompt/resource 列表） |

### 4.3 Controller 层

| 文件 | 改动 |
|---|---|
| `controller/McpAdminController.java` | 端点重构（见 §5 接口对照表）；`syncMethodRel` 逻辑删除（不再需要冗余关联表） |
| `controller/TenantApiAdminController.java` | `refresh-tenant-api` → `refresh-server-api`，刷新目标从 `tenant_api` 改为 `mcp_server` |
| `controller/McpRequestLogController.java` | 日志查询/导出支持按 `server_id` 过滤（可选） |
| 新增 | `McpPromptAdminController` / `McpResourceAdminController`（若控制台需要管理 prompt/resource） |

### 4.4 拦截器 / 动态注册

| 文件 | 改动 |
|---|---|
| `config/filter/McpAuthInterceptor.java` | 从 `path` 反查 `server_id`（用 `McpServerMapper.selectOneByPath`），鉴权对象改为 `(appId, serverId)`，再按 tool status 做二次校验 |
| `config/filter/AdminAuthInterceptor.java` | `checkPermission` 的路径判断新增 `/servers`、`/tools`、`/app-server-rels`、`/prompts`、`/resources` 等分支 |
| `config/session/AdminAuthContext.java` | `tenantIds` → `serverIds`（或保留 tenantIds 并在 service 层映射为 serverIds） |
| `annotation/TenantApiDynamicControllerManager.java` | 改造 → `McpServerDynamicControllerManager`：读 `mcp_server` 表 `status=1`，注册 `path`（逻辑不变，仅换表） |
| `config/filter/McpSessionInterceptor.java` | `if(true)` 开发期跳过 session 校验，保留即可；建议抽出配置开关 |

### 4.5 MCP 协议层（McpServiceImpl / McpMethodEnum）

| method | 现状 | 目标 |
|---|---|---|
| `initialize` | 返回 `tenant_api.res_json` | 返回 `mcp_server.capabilities_json` |
| `tools/list` | `method_def` by tenant, status=1 | `mcp_tool` by server, status=1 |
| `tools/call` | by tenant+code，不过滤 status | by server+code，**过滤 status=1** |
| `resources/list` | 返回 null | 查询 `mcp_resource` by server, status=1（实现） |
| `prompts/list` | 未实现 | 查询 `mcp_prompt` by server, status=1（实现） |
| `resources/templates/list` | 未实现 | 可选实现或继续返回空 |
| `ping` / `notifications/initialized` | 已实现 | 不变 |

---

## 5. 管理 REST 接口对照（McpAdminController）

| 现状端点 | 目标端点 | 说明 |
|---|---|---|
| `GET/POST /mcp/admin/tenants`、`PUT/DELETE /mcp/admin/tenants/{id}` | `GET/POST /mcp/admin/tenants`、`PUT/DELETE /mcp/admin/tenants/{id}` | 语义变为纯「业务方」 |
| —（新增） | `GET/POST /mcp/admin/servers`、`PUT/DELETE /mcp/admin/servers/{id}` | server CRUD，含 `tenant_id` 归属 |
| `POST /mcp/admin/refresh-tenant-api` | `POST /mcp/admin/refresh-server-api` | 刷新 server 动态注册 |
| `GET/POST /mcp/admin/methods`、`PUT/DELETE /mcp/admin/methods/{id}` | `GET/POST /mcp/admin/tools`、`PUT/DELETE /mcp/admin/tools/{id}` | tool 归属改为 `server_id` |
| `GET/POST /mcp/admin/tenant-method-rels`、`DELETE .../{id}` | **废弃** | tool 归属直接由 `server_id` 表达 |
| `GET /mcp/admin/apps`、`POST /apps`、`PUT /apps/{id}/reset-secret`、`DELETE /apps/{id}` | 不变 | 应用管理 |
| `GET/POST /mcp/admin/method-rels`、`POST /method-rels/batch`、`DELETE /method-rels/{id}` | `GET/POST /mcp/admin/app-server-rels`、`POST /app-server-rels/batch`、`DELETE /app-server-rels/{id}` | 授权对象从 method 改为 server；另加 `GET/POST /app-tool-rels`（可选） |
| `GET /mcp/admin/service-systems`、`PUT /service-systems/{id}` | 不变 | 系统服务 |
| `GET /mcp/admin/overview`、`tool-call-records`、`tool-call-stats` | 统计口径改为按 `server_id` 聚合 | 概览 |
| `GET /mcp/log/list`、`/mcp/log/export` | 增加 `serverId` 过滤（可选） | 日志 |

---

## 6. 鉴权链路改造（核心）

**改造后 tools/call 鉴权流程**（`McpAuthInterceptor` + `McpAuthServiceImpl`）：

```
请求 POST /mcp/v1/core  body.method = "tools/call", params.name = "toolX"
  │
  ├─ 1. 从 path 反查 mcp_server（selectOneByPath，status=1）→ server_id，拿不到 → 404/资源不存在
  ├─ 2. 验 access-token（HS256, iss=appId）→ appId，失败 → -32001 Unauthorized
  ├─ 3. 查 app_server_rel(app_id, server_id) → 无记录 → -32003 Forbidden
  ├─ 4. （可选）查 app_tool_rel(app_id, tool_id)：若该应用存在 tool 级白名单且不含此 tool → -32003
  ├─ 5. 查 mcp_tool(server_id, code=params.name, status=1) → 拿不到 → -32002 Resource not found
  └─ 6. 放行 → McpServiceImpl.toolsCall 执行 LOCAL/EXTERNAL
```

要点：

- **`*` 通配的语义变化**：旧「`*`=全局全放行」消失；新「授权到 server」本身就是「该 server 内全放行」，无需 `*` 字段。
- **server 上下文必须贯穿**：鉴权、工具发现、工具调用三处都要以 `(server_id, tool)` 定位，避免「同名 tool 跨 server」串权。
- **tool status 双重生效**：`tools/list` 不展示 + `tools/call` 直接拒绝，两个入口都过滤 `status=1`。

---

## 7. 控制台（mcp-console）改造点

| 文件 | 改动 |
|---|---|
| `src/types/index.ts` | 新增 `McpServer`、`McpPrompt`、`McpResource`、`AppServerRel`；`TenantApi` 改为 `Tenant`（去 path/res_json）；`MethodDef` → `McpTool`（`tenantId`→`serverId`） |
| `src/lib/api.ts` | 端点按 §5 对照表调整；`refreshTenantControllers` → `refreshServers`；授权函数参数从 `methodIds` → `serverIds` |
| `src/mocks/data.ts` + `mocks/api.ts` | mock 数据改三层结构，授权逻辑改 server 级 |
| `src/pages/TenantsPage.tsx` | 变为「业务方管理」，去 path/res_json 字段 |
| 新增 `src/pages/ServersPage.tsx` | 「MCP Server 管理」：归属业务方、path、capabilities、启停、刷新 |
| `src/pages/MethodsPage.tsx` | 「方法定义」→「工具定义」，归属选择从租户改为 server |
| `src/pages/PermissionsPage.tsx` | 「应用权限」：批量授权对象从 method 列表改为 server 列表（`*` 通配改为「按 server 全放行」） |
| `src/pages/SystemsPage.tsx` | 基本不变（tool 的 `system_id` 关联可在工具表单里加） |
| `src/components/layout/nav.ts` | 菜单：新增「MCP Server」项，调整描述文案 |

---

## 8. 数据迁移方案（seed → 新模型）

以 `db/mysql/init.sql` 当前种子数据为例：

### 8.1 tenant_api → tenant + mcp_server（1:1 拆分）

```sql
INSERT INTO tenant (id, name, status, create_time, update_time)
  SELECT id, name, status, create_time, update_time FROM tenant_api;

INSERT INTO mcp_server (id, tenant_id, name, path, status, capabilities_json, create_time, update_time)
  SELECT id, id, CONCAT(name, '-core'), path, status, res_json, create_time, update_time
  FROM tenant_api;
-- 结果：crm-tenant→server1(/mcp/v1/core)，greatgas→server2(/mcp/greatgas/core)，trade-mcp→server3(/mcp/trade/core, status=0)
```

### 8.2 method_def → mcp_tool（tenant_id 即 server_id，因当前 1:1）

```sql
INSERT INTO mcp_tool (id, server_id, name, code, type, system_id, status, service_url, request_type,
                      need_token, body_type, auth_type, auth_config, param_mapping, res_json, create_time, update_time)
  SELECT id, tenant_id, name, code, type,
         CASE WHEN type = 1 THEN 1 ELSE NULL END,   -- HTTP转发暂关联 system_id=1，需人工复核
         status, service_url, request_type, need_token, body_type,
         auth_type, auth_config, param_mapping, res_json, create_time, update_time
  FROM method_def;
```

### 8.3 mcp_sys_method_rel → 拆分（**需人工复核**）

```sql
-- 通配授权：映射到「对应业务方的 server」（现状 * 未记录 server，需按业务判断）
-- 例：crm 的 * → app_server_rel(crm, server 1)
INSERT INTO app_server_rel (app_id, server_id, status)
  SELECT r.app_id, s.id, r.status
  FROM mcp_sys_method_rel r
  JOIN mcp_server s ON s.tenant_id = (SELECT tenant_id FROM tenant WHERE name = r.app_id)  -- 示意，实际需人工映射
  WHERE r.method_id = '*';

-- 具体 tool 级授权：迁移到 app_tool_rel（保留跨 server 细粒度）
INSERT INTO app_tool_rel (app_id, tool_id, status)
  SELECT app_id, CAST(method_id AS UNSIGNED), status
  FROM mcp_sys_method_rel
  WHERE method_id <> '*';
-- 结果：greatgas→tool12(get-enable-zones)、trade→tool6/11 保留为 app_tool_rel
```

> ⚠️ 迁移风险点：旧的 `*` 是**全局**通配，语义比「授权某个 server」更宽。迁移时必须逐个确认每个 `*` 应用实际应授权哪些 server，避免越权（收紧）或权限丢失（过宽）。这是本次迁移唯一需要人工介入的地方。

---

## 9. 兼容性与回滚

- **灰度策略**：新表上线后保留只读的旧表，`tools/call` 鉴权同时兼容旧 `mcp_sys_method_rel` 一段时间（双读），确认无遗漏后下线旧逻辑。
- **对外路径不变**：`mcp_server.path` 沿用原 `tenant_api.path`（如 `/mcp/v1/core`），MCP 客户端无需改配置。
- **回滚**：迁移采用「新建表 + 数据复制」而非原地改表，回滚只需切回旧 mapper/逻辑。
- **控制台**：`VITE_USE_MOCK` 模式先行更新 mock，再切真实后端。

---

## 10. 分阶段实施计划

| 阶段 | 内容 | 交付 |
|---|---|---|
| P1 数据层 | 建新表、写迁移脚本、双写/双读兼容 | DDL + 迁移 SQL |
| P2 后端改造 | entity/mapper/service/controller 重构，动态注册换表，鉴权链路改 server 级 | 后端编译通过 + 单测 |
| P3 MCP 协议 | tools/list 按 server、tools/call 过滤 status、实现 resources/prompts list | 网关可被标准 MCP 客户端握手调用 |
| P4 控制台 | types/api/mocks/页面改造 | 控制台可管理 tenant/server/tool/prompt/resource 与授权 |
| P5 迁移上线 | 数据迁移 + 灰度 + 回滚预案 | 生产切换 |

---

## 11. 风险与注意点

1. **`*` 通配迁移需人工确认**（见 §8.3），是本方案唯一不可自动化的风险点。
2. **tool 级授权语义取舍**：若产品决定「只做 server 级 + tool status」，则 `app_tool_rel` 可去掉，但需明确接受「同一 server 内无法按应用差异化授权工具」的取舍。
3. **`service_system` 语义修正**：`system_id` 显式关联，需清理存量 `method_def.type` 被当 service_system 主键的历史数据。
4. **动态注册无版本概念**：`refresh-server-api` 是全量重建，刷新瞬间可能有极短路由空窗；高可用场景可改为「先注册新、后删旧」的两阶段刷新。
5. **日志表补 `server_id`**：`mcp_request_log` / `tool_call_record` 建议加 `server_id`，便于按 server 统计与下钻，否则概览页无法按 server 维度聚合。
