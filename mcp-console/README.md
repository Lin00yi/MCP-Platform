# MCP Gateway Console

MCP Gateway（`mcp-gateway`）的管理控制面板。基于 **React 18 + TypeScript + Vite + shadcn/ui + Tailwind CSS + TanStack Query** 构建。

## 功能

| 模块 | 说明 |
|---|---|
| 概览 Dashboard | 请求量/成功率/耗时等核心指标、14 天趋势图、方法分布、调用类型分布 |
| 业务方 | `tenant` 列表，业务方增删改、启停 |
| MCP Server | `mcp_server` 列表，动态端点刷新、增删改、启停 |
| 工具定义 | `mcp_tool` 列表，工具 Schema 查看、增删改、启停 |
| Prompt/Resource | `mcp_prompt` / `mcp_resource` 管理 |
| 应用权限 | `app_server_rel`（server 级）+ `app_tool_rel`（tool 级，可选）授权 |
| 系统服务 | `service_system` 授权地址管理 |
| 调用日志 | `tool_call_record` 调用记录筛选/分页/导出 |

## 技术栈

- **React 18 + TypeScript**（严格模式）
- **Vite 5**
- **shadcn/ui**（Radix UI primitives + Tailwind CSS 3 + CVA）
- **TanStack Query v5**（数据获取、缓存、失效）
- **React Router v6**（路由级代码分割）
- **Recharts**（图表）
- **sonner**（Toast 通知）
- **lucide-react**（图标）

设计采用深色「代码/终端」风格（Fira Code / Fira Sans 字体 + 绿色主色），契合开发者工具定位。

## 快速开始

```bash
pnpm install
pnpm dev        # http://localhost:5173
```

> 默认走**真实网关**（`VITE_USE_MOCK` 未设置时为 false）。如需离线演示，显式设置 `VITE_USE_MOCK=true`。

生产构建：

```bash
pnpm build       # 输出到 dist/（同时生成 dist/version.json）
pnpm build:rollup # 同上，Rollup+SWC 备用构建
pnpm preview     # 预览构建产物
```

## 版本号与更新提示

侧边栏底部展示控制面板版本号（点击可查看版本详情与更新内容）。部署新版本后，已打开的页面会自动发现并弹窗展示更新内容，可一键刷新。

- **版本号**：`package.json` 的 `version`，发布时按需升级。
- **更新内容**：`changelog.json` 的 `releases`，发版时在最前面追加一条：

```json
{
  "version": "1.2.0",
  "date": "2026-12-01",
  "highlights": ["新增 xxx", "修复 yyy"]
}
```

- **构建信息**：`scripts/version.mjs` 在 `pnpm dev/build/build:rollup` 前生成 `public/version.json`，包含版本号、构建时间、提交号（`GIT_COMMIT` 环境变量）和全部更新记录。
- **更新检测**：页面加载后每 60 秒、以及窗口重新获得焦点/可见时拉取 `/version.json`（`no-store`），与当前页面构建的 `buildId` 对比；不一致且服务端更新时自动弹窗。关闭后不再重复提示同一构建，直到下一次部署。

> 生产环境需保证 `/version.json` 与 `index.html` 不被缓存（反向代理设置 `Cache-Control: no-store`）；本地 `node server.cjs` 已同样处理。

## 连接真实后端

1. 复制 `.env.example` 为 `.env`（默认已是真实接口）：

```bash
VITE_USE_MOCK=false
VITE_API_BASE_URL=/api
```

2. `vite.config.js` 已配置 `/api` 代理到 `http://localhost:8081`（MCP 网关后端端口）；也可用 `node server.cjs` 托管 `dist/` 并代理 `/api`。

后端（`mcp-gateway`）管理 REST 接口统一返回 `Result<T>` 信封 `{code, message, data}`，对应 `McpAdminController`：

| 资源 | 端点 |
|---|---|
| 业务方 | `GET/POST /mcp/admin/tenants`、`PUT/DELETE /mcp/admin/tenants/{id}` |
| MCP Server | `GET/POST /mcp/admin/servers`、`PUT/DELETE /mcp/admin/servers/{id}` |
| 工具 | `GET/POST /mcp/admin/tools`、`PUT/DELETE /mcp/admin/tools/{id}` |
| Prompt | `GET/POST /mcp/admin/prompts`、`PUT/DELETE /mcp/admin/prompts/{id}` |
| Resource | `GET/POST /mcp/admin/resources`、`PUT/DELETE /mcp/admin/resources/{id}` |
| 应用 | `GET/POST /mcp/admin/apps`、`PUT /mcp/admin/apps/{id}/reset-secret`、`DELETE /mcp/admin/apps/{id}` |
| 应用×server 授权 | `GET/POST /mcp/admin/app-server-rels`、`POST /mcp/admin/app-server-rels/batch`、`DELETE /mcp/admin/app-server-rels/{id}` |
| 应用×tool 授权 | `GET/POST /mcp/admin/app-tool-rels`、`DELETE /mcp/admin/app-tool-rels/{id}` |
| 系统服务 | `GET /mcp/admin/service-systems`、`PUT /mcp/admin/service-systems/{id}` |
| 工具调用 | `GET /mcp/admin/tool-call-records`、`GET /mcp/admin/tool-call-stats` |
| 请求日志导出 | `GET /mcp/log/export` |
| 刷新端点 | `POST /mcp/admin/refresh-server-api` |

> 管理接口已接入 JWT + 角色权限矩阵（`AdminPermissionEvaluator`，默认拒绝）；敏感资源（应用密钥、工具鉴权配置、下游系统）需要对应管理角色。
> 列表接口不再返回明文 `app_secret`，仅在创建/重置时一次性展示。
> 导出已改为带 `Authorization` 的 `fetch + blob` 下载。

## 目录结构

```
src/
├── components/
│   ├── ui/              # shadcn/ui 基础组件
│   ├── layout/          # 应用布局（侧边栏/顶栏）
│   └── PageState.tsx    # 加载/空/错误/指标卡
├── pages/               # 各路由页面
├── lib/
│   ├── api.ts           # 数据访问层（mock / 真实接口切换）
│   ├── queryClient.ts   # TanStack Query 客户端
│   ├── utils.ts         # cn / 格式化
│   └── format.ts        # 展示辅助
├── mocks/               # 模拟数据与 mock API
├── types/               # 领域类型（与后端实体一一对应）
├── App.tsx / main.tsx
└── index.css            # 主题 tokens
```
