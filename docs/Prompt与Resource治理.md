# Prompt / Resource 治理（2026-10）

Prompt / Resource 不照搬 Tool 的 META、热工具、Embedding，而是按它们的真实成本做**轻量治理**：

- Tool 贵在每轮注入的 Schema；
- Prompt 贵在 `prompts/get` 返回的正文；
- Resource 贵在 `resources/read` 返回的内容。

所以重点不是“少列几个”，而是**授权一致、内容有上限、数量可观测、列表可翻页**。

---

## 1. 访问模式

`mcp_server` 新增：

| 字段 | 取值 | 语义 |
|---|---|---|
| `prompt_access_mode` | `WHITELIST` / `ALL` | WHITELIST：仅 `app_prompt_rel` 显式授权可见；ALL：全部启用 Prompt |
| `resource_access_mode` | `WHITELIST` / `ALL` | 同上，对应 `app_resource_rel` |

- 与 Tool 共用同一套访问模式枚举，三项各自独立配置；
- 默认 `WHITELIST`（fail-closed）；需要放开时显式改为 `ALL`；
- 授权 Server 时：若对应模式为 `WHITELIST`，自动补齐该 Server 下当前全部 Prompt / Resource；
- 之后新增的 Prompt / Resource 仍需显式授权（与 Tool 的最小权限语义一致）。

`prompts/get`、`resources/read` 在拦截器和服务层**双重校验**，不再只依赖列表过滤。

## 2. 内容大小上限

配置：

```yaml
mcp:
  assets:
    prompt-max-bytes: 262144      # 256KB
    resource-max-bytes: 1048576   # 1MB
    tool-result-max-bytes: 1048576
```

- 对 `messages[].content.text`、`contents[].text/blob` 递归截断；
- 按 UTF-8 字节安全截断，不切断多字节字符；
- 追加 `...[truncated: original N bytes, limit M bytes]`；
- `tools/call` 返回结果同样受限，避免单个工具结果撑爆上下文。

## 3. 列表分页与预算

`prompts/list`、`resources/list`、`resources/templates/list` 支持：

- MCP 标准 `cursor` / `nextCursor`；
- 阈值 `mcp.tools.asset-pagination-min`（默认 500）；
- 客户端白名单与 `paginationRisk`（复用 `mcp.tools.pagination-clients`）；
- `_meta`：

```json
{
  "kind": "prompts",
  "totalCount": 1,
  "visibleCount": 1,
  "returned": 1,
  "accessMode": "WHITELIST",
  "paginationUsed": false,
  "paginationRisk": false,
  "paginationThreshold": 500,
  "visibleTokens": 120,
  "returnedTokens": 120,
  "tokenizer": "cjk-aware/approx-v1"
}
```

分页埋点按 `tools/prompts/resources` 分类统计，`GET /mcp/admin/tool-pagination-stats` 返回 `byKind`。

## 4. 缓存与变更通知

- `PromptListCache` / `ResourceListCache` 按 server 缓存；
- 新增/更新/删除 Prompt / Resource 时失效缓存；
- SSE 广播：
  - `notifications/prompts/list_changed`
  - `notifications/resources/list_changed`

## 5. 标签与 token

- `mcp_prompt` / `mcp_resource` 新增 `tags`、`token_estimate`、`tokenizer`；
- 保存时预计算 token（`res_json` + 30 固定结构开销）；
- 发现接口支持 `tag` 过滤：
  - `GET /mcp/discovery/prompts?tag=order`
  - `GET /mcp/discovery/resources?tag=docs`
  - 管理台预览：`/mcp/admin/discovery/prompts|resources?tag=...`
- 控制台 Prompt / Resource 标签页支持编辑标签、展示标签与 token。

## 6. 数量告警

`ToolBudget` 扩展：

- `totalPrompts/visiblePrompts`
- `totalResources/visibleResources`
- `promptOverWarn/resourceOverWarn`
- `promptEstimatedTokens/resourceEstimatedTokens`

`/mcp/admin/servers/budgets`、`/mcp/admin/servers/{id}/tool-budget`、Server 列表、Server 设置页均展示；
超过 `warn-threshold` 时写限流 WARN 日志。

## 7. 不做的事

- 不给 Prompt / Resource 加 META 元工具：MCP 没有标准，客户端不会主动调用；
- 不做 Prompt / Resource 的 Embedding 检索：数量级与收益不匹配；
- 不为 Resource 列表做 Schema 瘦身：Resource 没有 Schema。

Resource 数量很大时，优先用 `resources/templates/list` + URI 模板，而不是把每个 URI 都列出来。

---

## 8. 迁移与验证

```bash
mysql -u<user> -p mcp_gateway < db/mysql/migrations/20261020_asset_optimizations.sql
```

已覆盖：

- 后端 131 个单测通过；
- 全量 E2E 45/45 通过（临时分页实例）；
- Prompt/Resource 白名单：撤销后 `prompts/list` 为空，`prompts/get` / `resources/read` 返回 403；
- 300KB Prompt 正文按 256KB 截断；300KB Resource 未超 1MB 原样返回；
- 3 个 Prompt / 3 个 Resource 分页为 2+1，`byKind` 埋点正确；
- 授权 Server 自动补齐 Prompt/Resource 授权；
- 发现接口 `tag=order` / `tag=docs` 过滤正确；
- 前端 `tsc` / `vite build` 通过；
- `POST /mcp/admin/tools/token-estimates/rebuild` 同时重算 Tool / Prompt / Resource。
