# Endpoint 层 META 合并（P0-1）改造设计

> 状态：已实现（2026-10）
> 关联需求：R2 / R3 / R5 / R7 / R10 / E2 / E3
> 代码：`gateway/aggregate/McpAggregateService`、`gateway/aggregate/AggregateNaming`、`exposure/ToolExposureProperties`

---

## 1. 问题

客户端只通过 `/mcp/e/{endpointKey}` 接入，但 `tools/list` 的暴露策略只作用于
`/mcp/v1/{serverKey}`（排障路径）。聚合层固定返回全部可见工具，导致：

- Server 上配置的 `META` 对真实链路无效；
- 顶层工具数仍是 O(可见工具数)，渐进式披露与 Prompt Cache 收益落空；
- 跨 namespace 只能靠模型看全量列表，无法先检索再调用。

## 2. 目标与不变量

1. **不改客户端**：元工具就是普通工具，不依赖 `list_changed` 动态增删；
2. **per-server 策略仍然有效**：DIRECT 的 Server 行为不变，META 的 Server 只出热工具；
3. **顶层列表稳定**：相同配置/授权下顺序确定（按暴露名排序），不随 DB 返回顺序变化；
4. **权限不放松**：搜索与代理调用都复用 `ToolAccessPolicy`，调用前二次校验；
5. **可应急回退**：`mcp.tools.meta-enabled=false` 时全部按 DIRECT 处理。

## 3. 合并规则

| 成员 Server | 贡献到 Endpoint `tools/list` |
|---|---|
| `DIRECT` | 全部可见工具（`{prefix}__{code}`） |
| `META` | 仅 `hot_tool_codes`（`{prefix}__{code}`） |
| 任一 `META` | 追加一套端点级 `search_tools / get_tool_schema / call_tool / read_result_chunk` |
| META 全局关闭 | 所有 Server 按 DIRECT 处理 |

说明：

- 元工具**不带 namespace 前缀**，作用域是整个 Endpoint（跨 namespace / 跨 Server）；
- 只有 DIRECT Server 的 Endpoint，输出与改造前完全一致；
- 代理型 Server（`upstream_type=MCP/AGGREGATE`）无法在网关侧解析热工具 code，统一按 DIRECT 处理；
- 重复暴露名只保留第一个并打 WARN 日志（正常由 namespace 唯一性校验保证）。

## 4. 请求流

### 4.1 `tools/list`

```text
visibleServers(appId) ∩ Endpoint 成员
  → 每个 Server: 读 exposureMode + 可见工具 + 热工具
  → 合并 DIRECT 工具 / 热工具 / 元工具
  → 按暴露名排序 → 分页（通常不会触发）
  → _meta 写入 budget 与模式统计
```

### 4.2 `search_tools(query?, domain?, tags?, server_id?, top_k?)`

1. 把 Endpoint 内所有 native / 代理工具统一成 `ToolSearchService.ToolDoc`；
2. 按 `mcp.tools.search-mode` 选策略：`CATALOG`（query 空）→ 分组目录；
   `KEYWORD` / `BM25` / `VECTOR` / `HYBRID`（RRF）→ 打分召回；
3. `domain` 限定 namespace、`tags` 任一匹配、`server_id` 限定 Server；
4. 返回 `name`（暴露名）、`description`、`tags`、`server`（namespace 前缀）与 `mode`。

检索范围 = 当前应用在 Endpoint 内可见的全部工具，包含 DIRECT Server 的工具，
因此 DIRECT 工具既直出也可被检索到（不改变可见性）。

### 4.3 `get_tool_schema(name?, tool_codes?, detail_level?)`

解析暴露名 → Server + 真实工具（或代理工具定义），复用 `McpService#toToolDto`
的 Schema 瘦身与描述截断，返回 `name / description / inputSchema / tags / server`。

### 4.4 `call_tool(name, arguments, reason?)`

```text
meta 名称 → 拒绝（防递归）
解析暴露名 → Target
  ├─ 代理 Server → 还原真实工具名 → McpUpstreamClient.proxy
  └─ 本地工具   → ToolAccessPolicy.canUse 二次校验 → executeToolOnServer
```

标准 `tools/call` 与 `call_tool` 共用同一个 `callExposedTool`，鉴权、参数校验、
调用日志完全一致；参数校验失败以 `isError=true` 返回，供模型当轮修正。

`call_tool` 额外接受 `reason`：只写入审计日志并随入参暴露给客户端审批层，
**不会**混进目标工具参数。

### 4.5 双版本 Schema 与离线增强（P1-5 / P1-8）

- `get_tool_schema` 支持 `tool_codes` 批量与 `detail_level=compact|full`；
  compact 保留填参/校验关键字，full 返回入库完整版；返回体带 `tokens`；
- `ToolMetadataEnricher` 在检索前对 code/name 拆词并反向补中文同义词，
  并入 description/tags，提升英文 code 的中文召回（可配置关闭）。

### 4.6 DSH 审批信息还原（P1-6）

DSH 插件在 `tools/pre-execute` 瀑布中解包 `call_tool` 的内层工具名：

- 已有 `ask` → 改写 reason/displayReason 为“代理调用 <真实工具>（原因：…）”；
- `allow` → 仅在 `approvalEscalate=true` 时升级为 ask；
- `deny` / `cancel` → 原样返回，不放大权限；
- 不修改 `exec.name` / `exec.arguments`，不影响执行。

### 4.7 多 Endpoint 实测（P1-7）与熔断（P1-9）

- 发现接口新增 `exposureMode / exposedToolCount / estimatedVisibleTokens`；
- `tools/measure-endpoints.py` 采集连接数、顶层工具数与 Token；
- 决策文档：[P1-7-多Endpoint实测方案.md](P1-7-多Endpoint实测方案.md)；
- 当前结论：暂不收敛单连接，设定复核触发条件（Endpoint ≥7 / 工具 ≥300 / Token ≥100k）；
- `UpstreamCircuitBreaker` 保护本地与代理调用，参数错误不计入失败；
- P1-10（第三方 OAuth / Token Exchange）按触发条件延后，理由见同一文档 §6。

### 4.8 超长结果分片（P0-3）

`ToolResultService` 在 META 模式下对超过 `result-spill-threshold`（默认 4000 字符）的文本结果：

1. 全文存入 `ToolResultStore`（按 appId 隔离、LRU + TTL），得到 `call_id`；
2. 返回前 4000 字符预览 + 总长度 + `read_result_chunk` 指引；
3. `read_result_chunk(call_id, offset, limit)` 按偏移读取，默认 3000 字符。

DIRECT 模式不启用（顶层没有分片工具），继续使用 1MB 硬截断。
多副本部署时当前进程内缓存不可跨节点，需替换为 Redis。

## 5. 命名与 64 字符保护（P0-4）

`AggregateNaming.exposeTool(prefix, code, max)`：

- 未超限：`{prefix}__{code}`；
- 超限：`{prefix}__{code 前段}_{8位短哈希}`，总长 ≤ `mcp.tools.max-exposed-name-length`（默认 64）；
- 哈希由工具名确定性生成（SHA-256 前 8 位 hex），多副本/重启一致；
- 解析：先按剥离前缀后的名字精确查 `mcp_tool.code`；查不到再在可见工具（或代理工具缓存）中
  按“暴露名 == 入参”反查真实 code；
- Prompt / Resource 不套用该限制（继续使用 `expose`）。

> 已知边界：客户端（如 DSH）还会叠加 `mcp__{serverName}__` 前缀。网关只能约束自身暴露名，
> 端到端总长度需在第 4 步实测后决定是否预留客户端前缀余量。

## 6. 预算元数据

`tools/list._meta` 新增/修正：

| 字段 | 语义 |
|---|---|
| `exposureMode` | `META`（含元工具）/ `DIRECT` |
| `directServers` / `metaServers` | 两类成员 Server 数量 |
| `hotTools` | 实际下发的热工具数 |
| `visibleTools` | 授权后可见工具总数（含被 META 隐藏的） |
| `returned` | 本次实际返回数（META 下通常 = 热工具 + 3） |
| `visibleTokens` / `returnedTokens` | 按 `mcp_tool.token_estimate` 或 CJK-aware 估算 |
| `tokenizer` | `cjk-aware/approx-v1` |

同时按 Server 触发 `ToolBudgetAlerter`（沿用节流），并把 `exposureMode` 补进
`/mcp/discovery/servers`，便于控制台与第 4 步的实测统计。

## 7. 兼容与回滚

- 旧客户端：多出的元工具是普通工具，不影响 DIRECT 工具调用；
- 只改数据面读路径，无数据库迁移；
- 单 Server 排障：管理台把该 Server 切回 `DIRECT` 即可；
- 全局回退：`MCP_TOOLS_META_ENABLED=false` 后重启。

## 8. 测试

新增/扩展（全部通过，后端共 189 项；DSH 插件 9 项）：

- `McpAggregateServiceTest`
  - META + DIRECT 混合：热工具 + DIRECT 工具 + 三元工具，非热工具不直出；
  - META 全局关闭：回退为全量 DIRECT；
  - `search_tools` 跨 Server 返回带前缀的名称；
  - `get_tool_schema` / `call_tool` 正确解包前缀并落到真实工具；
  - 超长工具名可被截断后反查；
  - `_meta` 预算字段与 `tokenizer`。
- `AggregateNamingTest`：64 字符、确定性、哈希区分、0=不限制。
- `ToolSearchServiceTest`：目录/关键词/BM25/混合 RRF、domain 过滤、参数解析。
- `ToolResultServiceTest`：超长截断、分片读取、appId 隔离、DIRECT 不启用。
- `ToolSchemaCompactorTest`：compact 保留校验关键字、剔除展示字段。
- `ToolMetadataEnricherTest` / `ToolSynonymsTest`：拆词、反向同义词、可关闭。
- `dsh-mcp-gateway-sync/test/approval.test.mjs`：前缀解包、ask 增强、escalate、deny/cancel 透传。
- `UpstreamCircuitBreakerTest`：连续失败打开、成功关闭、按 Server 隔离、阈值可关。

## 9. 验收清单

- [x] Endpoint `tools/list` 在 META 成员下只返回热工具 + 3 个元工具；
- [x] 元工具可跨 namespace 检索/查参/调用；
- [x] 调用前二次可见性校验与参数校验；
- [x] 超长工具名 ≤ 64 且可路由；
- [x] `_meta` 暴露 `visibleTokens/returnedTokens`；
- [x] 全局开关可回退；
- [x] 文档更新（工具规模治理 / Endpoint 统一接入 / DSH 插件接入）。
