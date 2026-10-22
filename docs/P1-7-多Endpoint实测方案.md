# P1-7 多 Endpoint 接入实测与决策方案

> 状态：待实测（工具与指标已就绪）
> 关联：R5（Prompt Cache）、R10（会话级分组）、§4.1（业务分组启停）

---

## 1. 要回答的问题

当前客户端（DSH 同步插件）为**每个已授权 Endpoint** 挂载一个 `mcp-client`，
即 N 个 Endpoint = N 条 MCP 连接、N 份顶层工具列表。

需要实测决定：

- 保持**方法 B**（按业务分组暴露独立 Endpoint，客户端多连接）；
- 还是收敛到**方法 C**（单聚合端点 + 云端会话级分组状态，客户端 1 条连接）。

判断依据不是“看起来优雅”，而是：连接数、顶层工具数、Token 占用、缓存命中与选错率。

## 2. 已就绪的观测数据

`GET /mcp/discovery/servers` 每个 Endpoint 现已返回：

| 字段 | 含义 |
|---|---|
| `exposureMode` | `META` / `DIRECT`（是否启用元工具） |
| `toolCount` | 授权后可见工具总数 |
| `exposedToolCount` | 客户端**实际**拿到的顶层工具数（META 下为热工具 + 4 个元工具） |
| `estimatedVisibleTokens` | 可见工具的 Token 估算 |
| `serverCount` | Endpoint 内成员 Server 数 |

另外 `tools/list._meta` 提供 `visibleTokens / returnedTokens / tokenizer`，
可直接核验客户端实际拿到的成本。

## 3. 实测步骤

### 3.1 采集基线

```bash
python3 tools/measure-endpoints.py \
  --gateway http://127.0.0.1:8081 \
  --token-file ~/.dsh/mcp-gateway/token
```

输出示例：

```text
Endpoint 数（DSH 连接数）: 3
endpointKey              mode     servers  visible   exposed  tokens(approx)
team-pcb                 META           2      186        12           3640
team-eda                 META           1       64         8           2100
team-ops                 DIRECT         1       40        40          14000
合计暴露工具: 60，近似 Token: 19740
单端点最大暴露工具: 40
```

### 3.2 采集 DSH 侧

1. 打开 DSH 会话，确认工具列表中 `mcp__<endpoint>__...` 的数量与脚本一致；
2. 连续进行 3 轮以上多轮对话，记录：
   - 每轮工具定义占用的上下文（DSH 用量面板或模型用量回调）；
   - 是否出现 Prompt Cache 命中率下降（网关侧无法看到，需模型网关/客户端统计）；
   - 典型任务（跨 PCB / EDA / OPS 提问）的工具选择是否命中正确 Endpoint。

### 3.3 对照实验

| 场景 | 配置 |
|---|---|
| B-1 | 3 个 Endpoint，全部 DIRECT（对照组） |
| B-2 | 3 个 Endpoint，大分组切 META（推荐基线） |
| C-1 | 仅挂载 1 个总聚合 Endpoint（把全部 Namespace 绑到同一 Endpoint），成员切 META |

每个场景记录：连接数、顶层工具数、Token、首轮延迟、选错次数。

### 3.4 本地实测结果（2026-10-20）

环境：本地 dev，网关 8081，MySQL 3307；应用 `dsh-pcb` 授权 2 个 Endpoint。

```text
Endpoint 数（DSH 连接数）: 2
endpointKey              mode      servers  visible    exposed tokens(approx)
team-pcb                 META            1       32          9         1563
team-eda                 DIRECT          1        0          0            0
合计暴露工具: 9，近似 Token: 1563
单端点最大暴露工具: 9
```

- `pcb-server`：32 个可见工具，META，热工具 5 个 + 4 个元工具 = 9；
- `eda-server`：当前 0 个工具（本地环境未导入 EDA 工具），作为“空分组连接开销”样本；
- DSH 侧实际出现 `mcp__team-pcb__search_tools / get_tool_schema / call_tool / read_result_chunk`
  与 5 个 `mcp__team-pcb__pcb__*` 热工具，与脚本一致。

**结论**：在该规模下保持方法 B；但第二个分组为空，尚不能代表“多个大分组”的极端场景。
复核时至少准备 2 个有工具的分组，或直接把单分组做大到 ≥300 个工具。

> 测量脚本已修正 `/mcp/discovery/servers` 的 `{"code":0,"data":[...]}` 包装解析；
> 早期版本会误读为 `servers` 字段而返回空。

## 4. 决策矩阵

| 条件 | 建议 |
|---|---|
| Endpoint ≤ 3 且顶层工具 ≤ 100 | 保持方法 B |
| Endpoint 4~6，顶层工具 ≤ 150，Token ≤ 50k | 保持 B，优先把大分组切 META |
| Endpoint ≥ 7，或顶层工具 ≥ 300，或 Token ≥ 100k | 评估方法 C（单连接 + 会话分组） |
| 只有少数用户需要跨分组 | 保持 B，另加一个可选总聚合 Endpoint |

如果 C 的收益不显著（Token 降幅 < 20%，或选错率无改善），**不引入会话分组**，
避免额外维护“激活分组状态”的一致性。

## 5. 当前决策（2026-10）

**暂不收敛到单连接**，理由：

1. Endpoint 层 META 已生效，单 Endpoint 顶层工具从 O(N) 降到热工具 + 4，
   B 场景下每个连接通常只有十几到几十个工具；
2. DSH 同步插件按 Endpoint 挂载是现有稳定实现，改成单连接需要新增会话分组状态、
   启停同步与缓存失效链路，收益需实测支撑；
3. 多 Endpoint 也提供了天然隔离（一个分组故障不影响其他连接）。

**复核触发条件**（满足任一即启动方法 C 评估）：

- 单用户授权 Endpoint ≥ 7；
- 顶层工具合计 ≥ 300；
- 每轮工具定义 Token ≥ 100k；
- 实测出现跨分组任务频繁走错 Endpoint。

## 6. P1-10（下游 OAuth / Token Exchange）的范围判断

当前下游鉴权已覆盖：内部身份断言（JWE/JWS）、API_KEY、BEARER、SK_EXCHANGE。
**个人第三方 OAuth 云端托管**与 **RFC 8693 Token Exchange** 暂不实施：

- 触发条件：下游需要“代表用户”访问第三方系统，或下游需要获取限定 audience 的委托 token；
- 前置条件：方法 A（身份下传）灰度完成、`mcp_upstream` 表落地；
- 设计边界：网关作为 STS，短 TTL、可撤销、全链路审计，不向客户端下发第三方 refresh token。

在上述条件出现前，保持现有四种下游鉴权方式，避免同时改身份与委托两层。
