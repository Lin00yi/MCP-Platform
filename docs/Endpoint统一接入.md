# Endpoint 统一接入（Namespace / Endpoint）

> 客户端只通过 Endpoint 接入；Server 是上游实体，Namespace 是 Server 的逻辑分组。
> 旧的「客户端接入方式」全局开关（`client.access.mode`）与 `/mcp/v1/aggregate` 已下线。

## 1. 模型

```text
Endpoint  /mcp/e/{endpointKey}
  └── 绑定 1..N 个 Namespace
        └── 包含 1..N 个 MCP Server
              └── Tool / Prompt / Resource
```

- **Namespace**：一组 Server 的逻辑分组，带稳定前缀 `prefix`；工具名 = `{prefix}__{toolCode}`。
  - 前缀创建后**不可修改**，改名只能改显示名；
  - 同一 Namespace 内 Tool code 不允许重复（保存成员时校验）。
- **Endpoint**：对外接入点，`endpoint_key` 创建后不可修改；可绑定多个 Namespace。
- **授权**：应用通过 `app_endpoint_rel` 获得 Endpoint 准入；工具可见性再按该应用已有的
  Server / Tool 授权收窄（交集，fail-closed）。

## 2. 管理入口

控制台：

- **资产 → Namespaces**：新建/编辑、成员 Server、删除；
- **权限与治理 → Endpoints**：新建/编辑、绑定 Namespace、授权应用、删除。

接口：

```http
GET    /mcp/admin/namespaces
POST   /mcp/admin/namespaces                    {"name":"PCB","prefix":"pcb"}
PUT    /mcp/admin/namespaces/{id}               {"name":"PCB","description":"..."}
PUT    /mcp/admin/namespaces/{id}/servers       {"serverIds":[1,2]}
DELETE /mcp/admin/namespaces/{id}

GET    /mcp/admin/endpoints
POST   /mcp/admin/endpoints                     {"endpointKey":"team-pcb","name":"PCB"}
PUT    /mcp/admin/endpoints/{id}
PUT    /mcp/admin/endpoints/{id}/namespaces     {"namespaceIds":[1]}
PUT    /mcp/admin/endpoints/{id}/apps           {"appIds":["dsh-pcb"]}
DELETE /mcp/admin/endpoints/{id}
```

仅平台超管 / 管理员可维护上述接口。

## 3. 数据面

```text
POST /mcp/e/{endpointKey}
```

- 鉴权：`access-token` → appId → `app_endpoint_rel`（未授权返回 403）；
- 可见 Server = Endpoint 绑定的 Namespace 成员 ∩ 应用已授权的 Server；
- 工具名：`{namespacePrefix}__{toolCode}`；超过 64 字符时截断并追加短哈希（调用时自动反查真实工具）；
- **暴露模式在聚合层合并**：DIRECT Server 全量直出；META Server 只出热工具；只要有 META Server，
  就额外提供一套端点级 `search_tools / get_tool_schema / call_tool / read_result_chunk`（覆盖整个 Endpoint，不带前缀）；
- `initialize` 返回的 `serverInfo.name` 为 Endpoint 名称；
- Prompt / Resource 同样按前缀暴露；
- 日志的 `path` 记录实际 Endpoint 路径。

示例：

```bash
curl -X POST http://gateway:8081/mcp/e/team-pcb \
  -H 'access-token: <JWT>' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
# → pcb__orders_get 等
```

## 4. 发现接口

`GET /mcp/discovery/servers`（客户端视角）只返回**已获授权的 Endpoint 目录**：

```json
[{"serverKey":"team-pcb","path":"/mcp/e/team-pcb",
  "url":"http://gateway:8081/mcp/e/team-pcb",
  "upstreamType":"ENDPOINT","toolCount":24,"exposedToolCount":8,
  "exposureMode":"META","estimatedVisibleTokens":7200,"serverCount":1,
  "servers":[{"id":1,"serverKey":"pcb-server","namespace":"pcb","toolCount":24}]}]
```

没有 Endpoint 授权时返回空数组；管理台预览（admin JWT）仍按真实 Server 展示。

## 5. 数据库

迁移：

```bash
mysql -u<user> -p mcp_gateway < docker/mysql/migrations/20261021_namespace_endpoint.sql
mysql -u<user> -p mcp_gateway < docker/mysql/migrations/20261021_remove_client_access_mode.sql
```

新增表：`mcp_namespace`、`mcp_namespace_server`、`mcp_endpoint`、`mcp_endpoint_namespace`、`app_endpoint_rel`。

## 6. 兼容与边界

- `/mcp/v1/aggregate` 已删除；`client.access.mode` 已删除（`mcp_system_setting` 一并下线）。
- 底层 per-server 路径 `/mcp/{serverKey}/v1` 仍注册，可用于排障/直连，但不再出现在发现结果中。
- Endpoint 数据面暂未接入身份下传（`mcp.identity.enabled` 默认关闭）。
