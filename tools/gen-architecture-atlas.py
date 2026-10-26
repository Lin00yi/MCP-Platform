#!/usr/bin/env python3
"""生成当前实现的架构图（SVG），替代图册中 2026-10-17 旧基线的对应图片。

输出：
  docs/architecture/01-system-architecture.svg
  docs/architecture/02-module-map.svg
  docs/architecture/05-auth-pipeline.svg
  docs/architecture/09-deployment.svg

事实来源：mcp-gateway 三模块、db/mysql/init.sql、README / docs/配置说明.md。
"""
from __future__ import annotations

import html
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "docs" / "architecture"

INK = "#0f172a"
MUTED = "#64748b"
LINE = "#cbd5e1"
FONT = "'PingFang SC','Hiragino Sans GB','Microsoft YaHei','Helvetica Neue',Arial,sans-serif"

INDIGO = ("#4338ca", "#eef2ff", "#c7d2fe")
EMERALD = ("#047857", "#ecfdf5", "#a7f3d0")
AMBER = ("#b45309", "#fffbeb", "#fde68a")
ROSE = ("#be123c", "#fff1f2", "#fecdd3")
SKY = ("#0369a1", "#f0f9ff", "#bae6fd")
VIOLET = ("#6d28d9", "#f5f3ff", "#ddd6fe")
SLATE = ("#334155", "#f8fafc", "#e2e8f0")


def esc(value: str) -> str:
    return html.escape(str(value), quote=True)


def text(x, y, value, size=13, weight=400, fill=INK, anchor="start", opacity=1.0, letter=0):
    return (
        f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" font-weight="{weight}" '
        f'fill="{fill}" text-anchor="{anchor}" opacity="{opacity}" letter-spacing="{letter}">{esc(value)}</text>'
    )


def rect(x, y, w, h, fill, stroke, rx=12, sw=1.2, shadow=True):
    shadow_attr = ' filter="url(#shadow)"' if shadow else ""
    return (
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
        f'stroke="{stroke}" stroke-width="{sw}"{shadow_attr}/>'
    )


def box(x, y, w, h, title, lines, palette=INDIGO, title_size=15, line_size=11.5, pad=18):
    color, bg, border = palette
    out = [rect(x, y, w, h, bg, border)]
    out.append(text(x + pad, y + 30, title, title_size, 700, color))
    for i, line in enumerate(lines):
        out.append(text(x + pad, y + 52 + i * 18, line, line_size, 400, "#475569"))
    return "".join(out)


def arrow(x1, y1, x2, y2, color="#475569", width=1.8, dashed=False):
    dash = ' stroke-dasharray="6 4"' if dashed else ""
    return (
        f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" '
        f'stroke-width="{width}"{dash} marker-end="url(#arrow)"/>'
    )


def pill(x, y, w, h, label, palette=SLATE, size=12):
    color, bg, border = palette
    return rect(x, y, w, h, bg, border, rx=h / 2, sw=1.0) + text(
        x + w / 2, y + h / 2 + 4, label, size, 600, color, "middle"
    )


def start(w, h, title, subtitle):
    return [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" '
        f'font-family="{FONT}">',
        "<defs>",
        '<filter id="shadow" x="-8%" y="-8%" width="116%" height="120%">'
        '<feDropShadow dx="0" dy="2" stdDeviation="2.6" flood-color="#0f172a" flood-opacity="0.08"/></filter>',
        '<marker id="arrow" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" '
        'orient="auto-start-reverse"><path d="M 0 1 L 9 5 L 0 9 z" fill="#475569"/></marker>',
        "</defs>",
        f'<rect width="100%" height="100%" fill="#ffffff"/>',
        text(50, 48, title, 24, 700, INK),
        text(50, 74, subtitle, 13, 400, MUTED),
    ]


def save(name, parts):
    parts.append("</svg>")
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / name
    path.write_text("\n".join(parts), encoding="utf-8")
    print("wrote", path)


def note(parts, y, lines, x=50, color="#64748b"):
    for i, line in enumerate(lines):
        parts.append(text(x, y + i * 20, line, 11.5, 400, color))


def build_system():
    w, h = 1600, 1000
    p = start(w, h, "MCP Gateway · 系统架构（2026-10）",
              "客户端只连反向代理；数据面 :8081 / 控制面 :8082 互不依赖，共享 mcp-shared 内核；认证只认用户 token")

    # Row A -----------------------------------------------------------------
    p.append(box(60, 120, 230, 176, "AI 客户端", [
        "DSH Desktop（插件挂载）",
        "Codex CLI / Cursor",
        "Claude / 自研 Agent",
        "统一 Streamable HTTP",
    ], INDIGO, 15, 11))
    p.append(box(320, 120, 230, 176, "反向代理（自备）", [
        "/mcp/admin|log/* → :8082",
        "/api/* → :8082",
        "其余 /mcp/* → :8081",
        "静态资源 → 控制台 dist",
        "/internal/** 不对外",
    ], SLATE, 14, 10.5))
    p.append(box(580, 120, 520, 176, "S1 mcp-dataplane :8081", [
        "/mcp/e/{endpointKey}：POST JSON-RPC · GET SSE · DELETE",
        "认证 x-dsh-auth-token → 拓扑解析 → 聚合 → 执行 / 代理",
        "/mcp/discovery/**（需 token）· /internal/**（X-Internal-Token）",
        "写 tool_call_record / mcp_request_log；不进 /mcp/admin/**",
    ], EMERALD, 15, 11))
    p.append(box(1140, 120, 230, 176, "上游系统", [
        "REST API（HTTP 转发）",
        "MCP Server（透传）",
        "聚合上游（{name}__{tool}）",
        "本地方法（反射执行）",
    ], VIOLET, 14, 10.5))
    p.append(box(1400, 120, 140, 176, "Redis", [
        "可选",
        "会话 / 事件",
        "探活选主",
    ], SKY, 13, 10))

    # Row B -----------------------------------------------------------------
    p.append(box(60, 340, 230, 176, "mcp-console", [
        "React 18 + Vite（静态 SPA）",
        "pnpm build → dist/",
        "只调 /mcp/admin/**",
        "与 /mcp/log/**",
    ], INDIGO, 15, 11))
    p.append(box(320, 340, 470, 176, "S2 mcp-admin :8082", [
        "资产 CRUD：tenant / server / tool / prompt / resource",
        "Namespace / Endpoint 拓扑写侧 + 审批（L1–L4）",
        "OpenAPI 批量导入 · 用户与 RBAC（默认拒绝）",
        "日志查询 / 健康展示；经 /internal/** 调数据面",
    ], AMBER, 15, 11))
    p.append(box(830, 340, 280, 176, "MySQL 8（17 张表）", [
        "租户 / 资产 / 拓扑",
        "治理：审批 · 账号",
        "运行：调用日志 · 请求日志",
    ], EMERALD, 14, 11))
    p.append(box(1150, 340, 390, 176, "用户中心（外部）", [
        "提供 UserTokenStore 实现（同进程 Bean）",
        "签发 / 吊销 dsh_ 与 PAT；网关不做 HTTP introspection",
        "未接入时受保护请求返回 503（不降级匿名）",
    ], ROSE, 14, 10.5))

    # Shared kernel + footnotes ---------------------------------------------
    p.append(rect(60, 556, 1480, 132, "#f8fafc", "#e2e8f0", 14, 1.0, False))
    p.append(text(80, 590, "mcp-shared · 共享内核（jar，不依赖任一平面）", 17, 700, "#334155"))
    p.append(text(80, 616, "shared/entity · mapper · dto · enums · utils · constants · config.security（MyBatis 实体 / Mapper / DTO）", 11.5, 400, "#475569"))
    p.append(text(80, 640, "exposure（META 合并 · 分页 · 检索 · 预算 · 结果分片 · 熔断）· identity（JWS/JWE 身份断言）· topology（EndpointScopeReader）", 11.5, 400, "#475569"))
    p.append(text(80, 664, "cache（工具 / Prompt / Resource 列表缓存）· health（五态 · 探活选主）· redis · catalog（发现读服务）· port（变更事件 / 连通性探测接口）", 11.5, 400, "#475569"))

    # Arrows ----------------------------------------------------------------
    p.append(arrow(290, 208, 316, 208))
    p.append(arrow(550, 190, 576, 190))
    p.append(text(556, 182, "/mcp/*", 10, 600, "#94a3b8"))
    p.append(arrow(435, 300, 435, 336))
    p.append(text(444, 322, "管理端", 10, 600, "#94a3b8"))
    p.append(arrow(290, 428, 316, 428))
    p.append(arrow(790, 428, 826, 428))
    p.append(arrow(1100, 190, 1136, 190))
    p.append(arrow(700, 336, 700, 300))
    p.append(text(708, 322, "/internal/**", 10, 600, "#94a3b8"))
    p.append(arrow(900, 300, 900, 336))
    p.append(text(908, 322, "写日志", 10, 600, "#94a3b8"))
    p.append(text(1158, 320, "多副本时数据面接 Redis", 10, 600, "#94a3b8"))

    # Footnote --------------------------------------------------------------
    note(p, 730, [
        "关键约束：认证只认 x-dsh-auth-token（UserTokenStore 由用户中心提供）；不存在应用 JWT / access-token / 应用授权表。",
        "控制面是资产唯一写入口；数据面只读资产与拓扑、写调用日志。跨平面只走 HTTP（/internal/** + X-Internal-Token），无共享缓存直连。",
        "客户端唯一入口 /mcp/e/{endpointKey}；旧动态路由入口已删除（未注册路径返回 404）。",
    ])
    p.append(text(60, 806, "部署形态（本仓库）", 15, 700, "#334155"))
    note(p, 834, [
        "MySQL 8 手工安装，执行 db/mysql/init.sql + db/mysql/migrations/*.sql；两个 jar 由 mvn package 产出，run-local.sh 启动；",
        "控制台 pnpm build 产物由反向代理托管；Redis 仅多副本时需要；仓库不提供容器编排。",
    ])
    save("01-system-architecture.svg", p)


def build_modules():
    w, h = 1600, 960
    p = start(w, h, "MCP Gateway · 代码模块与依赖边界（2026-10）",
              "Maven 三模块：mcp-shared 不依赖任何平面；mcp-dataplane 与 mcp-admin 互不依赖，运行时只走 HTTP")

    panel_specs = [
        (60, "mcp-dataplane（S1 · :8081）", EMERALD, [
            ("access/", "McpEndpointController · Endpoint 入口 / SSE"),
            ("gateway/aggregate/", "McpAggregateService · 按 Endpoint 聚合"),
            ("gateway/ · service/", "McpUpstreamClient · McpService · 执行调度"),
            ("discovery/", "McpDiscoveryController · 客户端资产发现"),
            ("cluster/ · config/", "ClusterEventBus · 会话 / 认证过滤器"),
            ("gateway/internal · log", "InternalApiController /internal/** · ToolCallRecordWriter"),
        ]),
        (570, "mcp-shared（共享内核 jar）", INDIGO, [
            ("shared/entity · mapper", "MyBatis 实体 / XML Mapper"),
            ("shared/dto · enums · utils", "DTO · 枚举 · 加密 / URL / 路径工具"),
            ("exposure/", "META · 分页 · 检索 · 预算 · 分片 · 熔断"),
            ("identity/", "UserToken · JWS/JWE 身份断言 · 校验器"),
            ("topology/ · cache/", "EndpointScopeReader · 列表缓存"),
            ("health/ · redis/ · port/ · catalog/", "五态探活 · Redis · 端口接口 · 发现读服务"),
        ]),
        (1090, "mcp-admin（S2 · :8082）", AMBER, [
            ("controller/", "租户 / Server / 工具 / 资产 CRUD"),
            ("controller/", "Namespace / Endpoint / 审批 / 日志"),
            ("service/ · openapi/", "业务实现 · OpenAPI 3.x 解析导入"),
            ("security/", "AdminJwtUtil · 权限矩阵（默认拒绝）"),
            ("approval/", "ApprovalPolicy · ApprovalTier（L1–L4）"),
            ("topology/ · scheduler/ · port/", "拓扑写侧 · 后台任务 · 数据面客户端"),
        ]),
    ]
    for x, title, palette, items in panel_specs:
        p.append(box(x, 110, 450, 560, title, [], palette, 16, 11))
        y = 150
        for pkg, desc in items:
            p.append(box(x + 20, y, 410, 68, pkg, [desc], palette, 12.5, 10))
            y += 82

    # 依赖边界
    p.append(arrow(516, 390, 562, 390))
    p.append(arrow(1034, 390, 1086, 390))
    p.append(text(516, 380, "编译期依赖", 10.5, 600, "#94a3b8"))
    p.append(text(1034, 380, "编译期依赖", 10.5, 600, "#94a3b8"))
    p.append(rect(60, 700, 1480, 66, "#f8fafc", "#e2e8f0", 12, 1.0, False))
    p.append(text(80, 726, "编译期强制边界", 14, 700, "#334155"))
    p.append(text(80, 748, "mcp-shared 不依赖 mcp-dataplane / mcp-admin；两个平面互不依赖 —— 跨平面只走 HTTP（/internal/** + X-Internal-Token），不直连对方缓存。", 11.5, 400, "#475569"))

    # 非 Maven 模块
    p.append(text(60, 812, "非 Maven 模块（独立构建 / 运行）", 15, 700, "#334155"))
    p.append(box(60, 830, 450, 84, "mcp-console", [
        "React 18 + Vite + TypeScript · 只调 /mcp/admin/** 与 /mcp/log/**",
    ], VIOLET, 14, 10.5))
    p.append(box(570, 830, 450, 84, "tools/", [
        "DSH 同步器 · Codex stdio 桥 · E2E / 探测 / 图册生成脚本",
    ], SLATE, 14, 10.5))
    p.append(box(1090, 830, 450, 84, "db/mysql", [
        "init.sql（17 张表）· migrations/ 增量脚本",
    ], EMERALD, 14, 10.5))
    save("02-module-map.svg", p)


def build_auth():
    w, h = 1600, 860
    p = start(w, h, "MCP Gateway · 认证与身份链路（2026-10）",
              "数据面只认用户 token（x-dsh-auth-token）；控制面独立后台 JWT；下游按需下发 JWS/JWE 身份断言")

    # data plane chain
    p.append(text(60, 112, "一、数据面：客户端请求（/mcp/e、/mcp/discovery、/internal）", 15, 700, "#334155"))
    steps = [
        (60, "请求", ["x-dsh-auth-token", "dsh_ / PAT"], INDIGO),
        (310, "UserTokenAuthenticator", ["取 token · 缺/无效 401", "store 故障 503"], EMERALD),
        (560, "UserTokenVerifier", ["SHA-256 缓存 30s", "不缓存原始 token"], EMERALD),
        (810, "UserTokenStore", ["用户中心提供 Bean", "同进程查签发/吊销"], ROSE),
        (1060, "ResolvedIdentity", ["userId / userName", "customerId / scopes"], VIOLET),
        (1310, "McpIdentityContext", ["请求属性 + 上下文", "initialize 时绑定"], SKY),
    ]
    for x, title, lines, palette in steps:
        p.append(box(x, 130, 180, 100, title, lines, palette, 12.5, 10.5))
    for x in (246, 496, 746, 996, 1246):
        p.append(arrow(x, 180, x + 58, 180))

    p.append(box(60, 276, 660, 128, "Endpoint 拓扑解析（认证之后）", [
        "EndpointScopeReader.resolveScope(endpointKey)：10s 缓存，返回成员 Server + Namespace 前缀；",
        "Endpoint 不存在 / 已停用 → HTTP 403（JSON-RPC -32001）；租户归属见 mcp_namespace / mcp_endpoint.tenant_id。",
    ], EMERALD, 14, 11))
    p.append(box(740, 276, 800, 128, "失败语义（fail-closed，不降级为匿名）", [
        "401：缺少 / 无效 / 过期 token；403：Endpoint 不存在或已停用；",
        "503：用户中心 UserTokenStore 不可用（不是 token 失效，客户端应退避重试）。",
    ], ROSE, 14, 11))
    p.append(arrow(360, 230, 360, 272))

    # downstream identity
    p.append(text(60, 452, "二、下游身份下传（调用上游 MCP / REST 时）", 15, 700, "#334155"))
    p.append(box(60, 470, 350, 120, "IdentityAssertionResolver", [
        "全局模式 + Server 级覆盖（identity_mode）",
        "INHERIT 沿用全局；统一判定点",
    ], VIOLET, 13, 10.5))
    p.append(box(430, 470, 350, 120, "模式判定", [
        "OFF：不下发；OPTIONAL：能签就签，失败告警继续",
        "REQUIRED：失败拒绝 -32003，不转发下游",
    ], VIOLET, 13, 10.5))
    p.append(box(800, 470, 350, 120, "签发 JWS / JWE", [
        "sub=userId · cust=客编 · aud / exp / jti · scope",
        "密钥固定 MCP_IDENTITY_SIGNING_KEY",
    ], SKY, 13, 10.5))
    p.append(box(1170, 470, 370, 120, "下游验签", [
        "McpIdentityVerifier / GatewayOriginVerifier",
        "校验 iss=mcp-gateway、aud、exp；探活 scope=health",
    ], EMERALD, 13, 10.5))
    for x in (410, 780, 1150):
        p.append(arrow(x, 530, x + 20, 530))
    note(p, 616, [
        "原始用户 token 透传默认关闭（MCP_IDENTITY_FORWARD_USER_TOKEN=false），仅可信下游开启；token 不落库、不落日志。",
    ])

    # admin plane
    p.append(text(60, 668, "三、控制面：管理端（/mcp/admin/**、/mcp/log/**）", 15, 700, "#334155"))
    p.append(box(60, 686, 280, 110, "后台登录", [
        "账号 + BCrypt 口令",
        "登录失败限流（用户名 / IP）",
    ], AMBER, 13, 10.5))
    p.append(box(360, 686, 290, 110, "AdminJwtUtil", [
        "JWT 验签 · 有效期 1~168h",
        "默认 168h（7 天），上限 7 天",
    ], AMBER, 13, 10.5))
    p.append(box(670, 686, 350, 110, "AdminPermissionEvaluator", [
        "角色矩阵默认拒绝",
        "SUPER_ADMIN > ADMIN > DEVELOPER > OPERATOR > USER",
    ], AMBER, 13, 10.5))
    p.append(box(1040, 686, 270, 110, "审批（Maker-Checker）", [
        "敏感写操作返回 428",
        "审批人 ≠ 申请人且角色更高",
    ], AMBER, 13, 10.5))
    p.append(box(1330, 686, 210, 110, "审计", [
        "operation_log",
        "approval_request（加密快照）",
    ], AMBER, 12.5, 10.5))
    for x in (340, 650, 1020, 1310):
        p.append(arrow(x, 741, x + 20, 741))
    save("05-auth-pipeline.svg", p)


def build_deployment():
    w, h = 1600, 940
    p = start(w, h, "MCP Gateway · 部署拓扑（2026-10，交付形态）",
              "MySQL 8 + 两个 jar + 反向代理 + 控制台静态资源；多副本再启用 Redis；仓库不提供容器编排")

    # client / proxy / static
    p.append(box(60, 120, 220, 150, "客户端", [
        "AI 客户端（MCP）",
        "浏览器（控制台）",
        "tools/ 运维脚本",
    ], INDIGO, 14, 10.5))
    p.append(box(320, 120, 270, 380, "反向代理（自备）", [
        "/mcp/admin|log/* → :8082",
        "/api/* → :8082",
        "其余 /mcp/* → :8081",
        "静态资源 → 控制台 dist",
        "/internal/** 不对外",
        "TLS / 安全响应头",
        "由运维负责",
    ], SLATE, 14, 10.5))
    p.append(box(60, 310, 240, 170, "控制台静态资源", [
        "mcp-console/dist",
        "pnpm build 产物",
        "只调 /mcp/admin/**",
        "与 /mcp/log/**",
    ], VIOLET, 14, 10.5))

    # processes / stores
    p.append(box(620, 120, 380, 170, "S1 mcp-dataplane（:8081）", [
        "java -jar mcp-dataplane.jar",
        "SERVICE=dataplane · SERVER_PORT=8081",
        "/mcp/e/** · /mcp/discovery/** · /internal/**",
        "写 tool_call_record / mcp_request_log",
    ], EMERALD, 14, 10.5))
    p.append(box(620, 330, 380, 170, "S2 mcp-admin（:8082）", [
        "java -jar mcp-admin.jar",
        "SERVICE=admin · SERVER_PORT=8082",
        "/mcp/admin/** · /mcp/log/**",
        "MCP_DATAPLANE_URL 指向数据面",
        "首次启动 AdminBootstrap 建超管",
    ], AMBER, 14, 10.5))
    p.append(box(1040, 120, 280, 170, "MySQL 8 · 17 张表", [
        "新装：mysql -uroot -p < db/mysql/init.sql",
        "增量：按文件名执行 db/mysql/migrations/*.sql",
        "生产必须显式配置 MCP_DB_URL / 账号口令",
    ], EMERALD, 14, 10.5))
    p.append(box(1040, 330, 280, 170, "Redis（可选，多副本必选）", [
        "MCP_REDIS_ENABLED=true",
        "MCP_REDIS_URL 必须显式配置",
        "会话 / 事件 / 探活选主",
    ], SKY, 14, 10.5))
    p.append(box(1360, 120, 180, 380, "端口 / 路径", [
        "S1 :8081",
        "/mcp/e/**",
        "/mcp/discovery/**",
        "/internal/**",
        "",
        "S2 :8082",
        "/mcp/admin/**",
        "/mcp/log/**",
    ], SLATE, 13, 10))

    # arrows
    p.append(arrow(280, 195, 316, 195))
    p.append(arrow(320, 395, 304, 395))
    p.append(arrow(590, 195, 616, 195))
    p.append(arrow(590, 415, 616, 415))
    p.append(arrow(810, 326, 810, 294))
    p.append(text(818, 316, "/internal/**", 10, 600, "#94a3b8"))
    p.append(arrow(1000, 195, 1036, 195))
    p.append(text(624, 316, "多副本时数据面接 Redis（MCP_REDIS_ENABLED）", 10, 600, "#94a3b8"))

    # steps
    p.append(text(60, 556, "部署步骤", 16, 700, "#334155"))
    steps = [
        ("1", "准备 MySQL 8", "手工安装；执行 init.sql + migrations；字符集 utf8mb4"),
        ("2", "配置 .env", "cp .env.example .env；填密钥 / 内部凭证 / 公网地址"),
        ("3", "构建后端", "mvn -DskipTests package（JDK 17）→ 两个 jar"),
        ("4", "启动两个进程", "./run-local.sh both；或 java -jar 以 SERVICE 区分"),
        ("5", "构建控制台", "pnpm install && pnpm build → dist 交反向代理"),
        ("6", "配置反向代理", "按路径分流；/internal/** 不对外；TLS 由运维负责"),
    ]
    for i, (num, title, desc) in enumerate(steps):
        col = i % 2
        row = i // 2
        x = 60 + col * 760
        y = 574 + row * 56
        p.append(rect(x, y, 740, 48, "#ffffff", "#e2e8f0", 10, 1.0, False))
        p.append(pill(x + 12, y + 10, 28, 28, num, INDIGO))
        p.append(text(x + 52, y + 21, title, 13, 700, INK))
        p.append(text(x + 52, y + 39, desc, 10.5, 400, "#475569"))

    # self-check
    p.append(text(60, 774, "启动自检（失败拒绝启动）", 16, 700, "#334155"))
    note(p, 802, [
        "生产禁用 MCP_AUTH_STATIC_TOKENS；REQUIRED 身份下传必须配置签名私钥；开启 Redis 必须显式配置 MCP_REDIS_URL；",
        "MCP_TOOLS_PROFILE / --mcp.tools.profile 仅允许 default / compact / large；生产 PUBLIC_BASE_URL 指向 127.0.0.1 会告警。",
        "多副本：数据面各实例用 SERVER_PORT 区分端口并共享 MySQL + Redis；控制面建议单写副本。",
        "自检：curl -H \"X-Internal-Token: $MCP_INTERNAL_TOKEN\" http://127.0.0.1:8081/internal/cluster-info",
    ])
    save("09-deployment.svg", p)


def build_data_model():
    w, h = 1600, 900
    p = start(w, h, "MCP Gateway · 数据模型（17 张表）",
              "租户 → Server → 资产三级归属；Namespace / Endpoint 组成访问拓扑；审批与日志分域；全部为逻辑外键")

    groups = [
        (60, 300, "租户与资产（7）", EMERALD, [
            ("tenant", "业务方（租户）"),
            ("mcp_server", "资产容器 · 租户归属"),
            ("mcp_tool", "工具 · res_json / 凭证密文"),
            ("mcp_prompt", "Prompt 资产"),
            ("mcp_resource", "Resource 资产"),
            ("mcp_tool_embedding", "向量索引"),
            ("service_system", "下游系统 / sk 换取"),
        ]),
        (380, 260, "访问拓扑（4）", VIOLET, [
            ("mcp_endpoint", "唯一对外接入点 · endpoint_key"),
            ("mcp_endpoint_namespace", "Endpoint ↔ Namespace"),
            ("mcp_namespace", "工具名前缀 · tenant_id 可空"),
            ("mcp_namespace_server", "Namespace ↔ Server"),
        ]),
        (660, 250, "账号与治理（4）", AMBER, [
            ("admin_user", "后台账号 · BCrypt"),
            ("admin_user_tenant", "账号 ↔ 租户可见范围"),
            ("approval_request", "审批单 · payload 加密快照"),
            ("operation_log", "操作审计"),
        ]),
        (930, 230, "运行日志（2）", SKY, [
            ("tool_call_record", "工具调用记录（数据面写）"),
            ("mcp_request_log", "请求日志（数据面写）"),
        ]),
    ]
    for x, gw, title, palette, items in groups:
        p.append(box(x, 120, gw, 520, title, [], palette, 15, 11))
        y = 158
        for name, desc in items:
            p.append(box(x + 14, y, gw - 28, 58, name, [desc], palette, 11.5, 9.5))
            y += 66

    # relations
    p.append(arrow(360, 210, 376, 210))
    p.append(text(356, 202, "tenant_id", 9.5, 600, "#94a3b8"))
    p.append(arrow(910, 400, 926, 400))
    p.append(text(906, 392, "user_id", 9.5, 600, "#94a3b8"))
    p.append(arrow(380, 470, 364, 470, dashed=True))
    p.append(text(368, 462, "server_id", 9.5, 600, "#94a3b8"))

    # key fields
    p.append(box(1160, 120, 380, 520, "关键字段", [], SLATE, 15, 11))
    fields = [
        ("mcp_server", "status · upstream_type / url · tool_exposure_mode"),
        ("", "identity_mode · audience / identity_public_key"),
        ("mcp_tool", "code · type · auth_type / auth_config（密文）"),
        ("", "version · token_estimate · tags"),
        ("mcp_endpoint", "endpoint_key · name · status"),
        ("mcp_namespace", "prefix · tenant_id（NULL=平台级）"),
        ("approval_request", "action · tier · status · payload（AES-256-GCM）"),
        ("tool_call_record", "tenant_id · server_id · user_id · method_code"),
        ("", "success · duration · create_time（按保留天数清理）"),
    ]
    y = 186
    for name, desc in fields:
        if name:
            p.append(text(1178, y, name, 11.5, 700, "#334155"))
        p.append(text(1178, y + 17, desc, 10, 400, "#475569"))
        y += 44

    # relation notes
    p.append(text(60, 690, "关系要点", 15, 700, "#334155"))
    note(p, 718, [
        "资产：tenant 1—N mcp_server 1—N mcp_tool / mcp_prompt / mcp_resource；mcp_tool_embedding 跟随 mcp_tool；service_system 供 SK_EXCHANGE 工具换取凭证。",
        "拓扑：mcp_endpoint 1—N mcp_endpoint_namespace N—1 mcp_namespace 1—N mcp_namespace_server N—1 mcp_server；工具名 = {namespace.prefix}__{tool.code}，prefix 全局唯一。",
        "拓扑租户归属：mcp_namespace / mcp_endpoint 的 tenant_id 可空 —— NULL = 平台级（仅超管，可跨租户聚合），非 NULL = 业务方级（只能绑定本租户 Server）。",
        "治理：admin_user 1—N admin_user_tenant；approval_request 记录审批动作与加密 payload（result_secret 等应用维度字段已下线）；operation_log 记录管理面操作。",
        "运行：tool_call_record / mcp_request_log 由数据面写入、控制面只读；审计与日志按 mcp.log.* 保留天数清理。",
        "一致性：DDL 不建物理外键，只建普通索引；级联与归属由应用层与审批执行保证。",
    ])
    save("08-data-model.svg", p)


if __name__ == "__main__":
    build_system()
    build_modules()
    build_auth()
    build_deployment()
    build_data_model()
