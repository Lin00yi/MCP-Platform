#!/usr/bin/env python3
"""生成工具链路与治理架构图（SVG，描述当前实现）。

输出：
  docs/architecture/13-tool-chain-optimized.svg
  docs/architecture/14-tool-governance-design.svg
  docs/architecture/15-tool-architecture.svg
  docs/architecture/16-tool-chain-flow.svg
"""
from __future__ import annotations

import html
import os
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


def arrow(x1, y1, x2, y2, color="#475569", width=1.8):
    return (
        f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" '
        f'stroke-width="{width}" marker-end="url(#arrow)"/>'
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


def band(y, h, number, title, subtitle, palette=SLATE):
    color, bg, border = palette
    parts = [rect(40, y, 1600, h, "#ffffff", "#e2e8f0", 14)]
    parts.append(rect(40, y, 212, h, bg, border, 14))
    parts.append(text(62, y + 38, f"{number}  {title}", 17, 700, color))
    for i, line in enumerate(subtitle):
        parts.append(text(62, y + 62 + i * 18, line, 11.5, 400, "#475569"))
    return "".join(parts)


def build_chain():
    w, h = 1680, 1680
    p = start(w, h, "MCP Gateway · 工具链路全貌（2026-10）",
              "接入、定义、可见性、暴露面、检索、调用到运营闭环：每一层只回答一个问题，把上下文成本留在网关侧")

    # 1 接入
    p.append(band(104, 150, "①", "接入与定义", ["REST / OpenAPI /", "本地方法 / MCP 上游"], INDIGO))
    titles = ["REST 接口", "OpenAPI 3.x", "本地方法", "MCP 上游", "Embedding 索引"]
    lines = [["单接口生成", "参数映射"], ["批量预览/导入", "导入超阈值告警"], ["反射执行", "class.method"], ["透传 / 聚合", "{name}__{tool}"], ["异步向量化", "失败自动降级 BM25"]]
    xs = [270, 514, 758, 1002, 1246]
    for x, title, ls in zip(xs, titles, lines):
        p.append(box(x, 128, 224, 96, title, ls, INDIGO, 14, 11))
    p.append(arrow(840, 254, 840, 282))

    # 2 定义 / 缓存
    p.append(band(284, 148, "②", "定义与缓存", ["Schema / tags /", "token_estimate"], EMERALD))
    p.append(box(270, 308, 330, 100, "mcp_tool", ["归属 server；code · name · tags", "res_json（inputSchema）", "version · token_estimate"], EMERALD))
    p.append(box(630, 308, 320, 100, "资产缓存", ["按 server 缓存 Tool/Prompt/Resource", "热路径不查库", "仅进程内，事件驱动失效"], EMERALD))
    p.append(box(980, 308, 300, 100, "ClusterEventBus", ["控制面 /internal/config-changed", "Redis Pub/Sub（可选）", "各副本失效本地缓存"], EMERALD))
    p.append(box(1310, 308, 290, 100, "接入护栏", ["保留名 / 超长名拒绝", "token 预计算", "导入超阈值告警"], AMBER))
    p.append(arrow(840, 432, 840, 460))

    # 3 可见性与作用域
    p.append(band(462, 218, "③", "可见性与作用域", ["谁看得到", "租户 / Server / Endpoint"], ROSE))
    p.append(box(270, 488, 340, 92, "租户边界", ["tenant → mcp_server", "→ tool / prompt / resource", "控制面是资产唯一写方"], ROSE))
    p.append(box(630, 488, 420, 92, "访问模式固定 ALL", ["启用资产对任一已认证用户可见", "tool / prompt / resource_access_mode = ALL", "无应用级关联表；权限模型只有 ALL"], ROSE))
    p.append(box(1070, 488, 240, 92, "Endpoint 收窄", ["只暴露绑定的", "Namespace / Server 前缀"], ROSE))
    p.append(box(1330, 488, 270, 92, "拓扑作用域", ["tenant_id NULL = 平台级", "（仅平台超管）", "tenant_id=N = 业务方级"], ROSE))
    p.append(box(270, 596, 640, 74, "鉴权入口：唯一凭证 x-dsh-auth-token", [
        "x-dsh-auth-token → UserTokenVerifier（SHA-256 缓存 30s）→ 用户中心 UserTokenStore",
        "401 凭证无效 · 403 接入点不存在/停用 · 503 用户中心不可用",
    ], AMBER, 14, 11))
    p.append(box(930, 596, 670, 74, "拓扑：Endpoint ↔ Namespace ↔ Server", [
        "mcp_endpoint / mcp_namespace / mcp_namespace_server / mcp_endpoint_namespace",
        "解析出 Server 集合 + 每个 Server 的前缀；仅启用绑定生效，异常一律 403",
    ], AMBER, 14, 11))
    p.append(arrow(840, 680, 840, 708))

    # 4 暴露
    p.append(band(710, 250, "④", "暴露面治理", ["一次给模型看多少", "DIRECT / META"], VIOLET))
    p.append(box(270, 738, 620, 150, "DIRECT：全量可见工具", [
        "工具数 ≤ 500 整包返回；> 500 才 cursor 分页",
        "pageSize=200；允许分页客户端：codex / claude / dsh / cursor",
        "未识别客户端 → _meta.paginationRisk=true",
        "Schema 瘦身：去 examples、description 截断 1200",
    ], VIOLET, 15, 11.5))
    p.append(box(920, 738, 620, 150, "META：4 个元工具 + 热工具", [
        "search_tools → get_tool_schema → call_tool → read_result_chunk",
        "热工具常驻（hot_tool_limit=50），少一轮推理",
        "get_tool_schema 返回 compact / full",
        "Endpoint 级 META 合并：任一成员 Server 为 META 即启用",
    ], VIOLET, 15, 11.5))
    p.append(box(270, 900, 1270, 42, "预算：warn=100 · max=500 · overWarn / overMax · visibleTokens / returnedTokens（_meta）", [
    ], AMBER, 13, 11))
    p.append(arrow(840, 960, 840, 988))

    # 5 检索
    p.append(band(990, 180, "⑤", "检索层", ["search_tools 召回", "词法 + 向量"], SKY))
    p.append(box(270, 1018, 460, 124, "词法检索 KEYWORD / BM25", ["CJK 单字 / 二元组（订单查询 ↔ 查询订单）", "中英同义词（退款 ↔ refund）", "BM25：k1=1.2 · b=0.75"], SKY))
    p.append(box(760, 1018, 460, 124, "Embedding 混合检索", ["mcp_tool_embedding · 数据面维护", "VECTOR 余弦 / HYBRID = BM25+向量 RRF(k=60)", "不可用 30s 内自动降级 BM25"], SKY))
    p.append(box(1250, 1018, 350, 124, "目录与排序", ["CATALOG：query 为空按业务分组", "KEYWORD / BM25 / VECTOR / HYBRID", "top-K 不含 inputSchema"], SKY))
    p.append(arrow(840, 1170, 840, 1198))

    # 6 调用
    p.append(band(1200, 250, "⑥", "调用链路", ["鉴权 → 校验 → 执行", "→ 日志"], AMBER))
    p.append(box(270, 1228, 360, 112, "入口与鉴权", ["x-dsh-auth-token 唯一凭证", "Endpoint 作用域收窄可见工具", "401 / 403 / 503 统一语义"], AMBER, 14, 11))
    p.append(box(650, 1228, 360, 112, "服务层校验", ["启用资产即可调用，不需授权关联", "JSON Schema Draft-07", "Schema 编译缓存"], AMBER, 14, 11))
    p.append(box(1030, 1228, 260, 112, "执行器", ["本地反射 / HTTP 转发", "SSRF 防护 · 30s 超时", "下游熔断（按 Server）"], AMBER, 14, 11))
    p.append(box(1310, 1228, 290, 112, "观测落库", ["tool_call_record", "mcp_request_log", "脱敏 + 截断 + traceId"], AMBER, 14, 11))
    p.append(box(270, 1352, 640, 88, "身份下传（按 Server identity_mode）", [
        "OFF / OPTIONAL / REQUIRED；JWS / JWE 断言",
        "OPTIONAL 告警降级；REQUIRED 失败返回 -32003",
    ], VIOLET, 14, 11))
    p.append(box(930, 1352, 670, 88, "超长结果与熔断", [
        "结果 > 4000 字截断暂存，read_result_chunk 分片读取",
        "chunk-store=memory|redis；熔断进程内、按 Server 计数",
    ], VIOLET, 14, 11))
    p.append(arrow(840, 1450, 840, 1478))

    # 7 运营
    p.append(band(1480, 160, "⑦", "运营闭环", ["可观测、可恢复、", "可审计"], EMERALD))
    ops = [
        ("预算告警", ["warn 100 / max 500", "首页横幅 + 日志"]),
        ("分页埋点", ["issued / followed", "abandoned"]),
        ("热工具推荐", ["近 30 天成功调用", "Top N 回填 hot_tool_codes"]),
        ("变更广播", ["/internal/config-changed", "30s 兜底轮询"]),
        ("客户端矩阵", ["probe / Codex", "兼容策略"]),
    ]
    for i, (title, ls) in enumerate(ops):
        p.append(box(270 + i * 258, 1508, 238, 108, title, ls, EMERALD, 14, 11))
    save("13-tool-chain-optimized.svg", p)


def build_design():
    w, h = 1680, 1120
    p = start(w, h, "工具治理设计图 · 五层控制面",
              "五层各自有输入、决策、数据落点与验收指标；不让模型上下文承担治理职责")
    headers = ["层", "目标", "机制", "配置 / 数据", "接口 / 验收"]
    xs = [60, 300, 650, 1030, 1350]
    widths = [220, 330, 360, 300, 270]
    p.append(rect(60, 110, 1560, 46, "#f1f5f9", "#e2e8f0", 8, 1.0, False))
    for x, wd, label in zip(xs, widths, headers):
        p.append(text(x + 16, 139, label, 13, 700, "#334155"))

    rows = [
        ("可见性", "租户隔离，启用即可见", [
            "多租户：tenant → mcp_server → 资产",
            "access_mode 固定 ALL",
            "Endpoint 收窄：Namespace 前缀",
            "tenant_id NULL=平台级 / N=业务方级",
        ], [
            "tenant / mcp_server",
            "*_access_mode（固定 ALL）",
            "mcp_endpoint / mcp_namespace",
            "namespace_server / endpoint_namespace",
        ], ["discovery 同一规则", "启用资产全员可见", "Endpoint 停用 → 403"]),
        ("暴露面", "一次只给模型看需要的", [
            "DIRECT：全量 + 超 500 分页",
            "META：热工具 + 4 个元工具",
            "get_tool_schema：compact / full",
            "Schema 瘦身：去 examples、截断",
        ], [
            "tool_exposure_mode",
            "hot_tool_codes",
            "page-size 200 / min 500",
            "max-description-length 1200",
        ], ["tools/list _meta", "paginationRisk", "estimatedTokens"]),
        ("检索", "在长尾里快速找到正确工具", [
            "CATALOG：query 为空列目录",
            "KEYWORD / BM25：CJK 分词 + 同义词",
            "VECTOR / HYBRID：RRF(k=60) 融合",
            "无 embedding 自动降级 BM25",
        ], [
            "mcp_tool_embedding",
            "mcp.tools.search-mode",
            "ToolSearchService 索引缓存",
            "search_tools 参数",
        ], ["search_tools top-K", "get_tool_schema 复核", "reindex 接口"]),
        ("预算/传输", "让成本可见、可预警", [
            "visibleTokens / returnedTokens",
            "warn 100 / max 500",
            "token 预计算（CJK-aware）",
            "结果 > 4000 字截断 + 分片",
        ], [
            "mcp_tool.token_estimate",
            "mcp.tools.*",
            "chunk-store=memory|redis",
            "ToolBudgetAlerter / Telemetry",
        ], ["_meta 预算字段", "overview 告警", "read_result_chunk"]),
        ("运行/闭环", "出问题能定位、能恢复", [
            "x-dsh-auth-token 单一认证",
            "身份下传 OFF / OPTIONAL / REQUIRED",
            "变更广播 + 30s 兜底轮询",
            "Maker-Checker 审批 L1–L4",
        ], [
            "tool_call_record / mcp_request_log",
            "ClusterEventBus / RevisionWatcher",
            "approval_request（AES-256-GCM）",
            "Redis（可选，多副本）",
        ], ["401 / 403 / 503 / -32003", "operation_log 审计", "17 张表 init.sql"]),
    ]
    for i, (layer, goal, mechanics, data, verify) in enumerate(rows):
        y = 156 + i * 170
        bg = "#ffffff" if i % 2 == 0 else "#fbfdff"
        p.append(rect(60, y, 1560, 170, bg, "#e2e8f0", 8, 1.0, False))
        p.append(text(76, y + 34, layer, 15, 700, "#4338ca"))
        p.append(text(76, y + 58, f"L{i+1}", 11, 600, "#94a3b8"))
        p.append(text(316, y + 32, goal, 12.5, 600, INK))
        for j, line in enumerate(mechanics):
            p.append(text(666, y + 30 + j * 26, "· " + line, 11.5, 400, "#475569"))
        for j, line in enumerate(data):
            p.append(text(1046, y + 30 + j * 26, "· " + line, 11, 400, "#475569"))
        for j, line in enumerate(verify):
            p.append(text(1366, y + 30 + j * 26, "· " + line, 11, 400, "#475569"))

    p.append(text(60, 1040, "设计原则：租户隔离 · 启用即可见（ALL）· 按需暴露（DIRECT / META）· 可观测（_meta / 日志 / 埋点）· 失败可降级",
                  12, 600, "#64748b"))
    p.append(text(60, 1064, "边界处理：Endpoint 不存在或停用 → 403；身份下传 REQUIRED 失败 → -32003；Embedding 不可用降级 BM25；资产变更经事件总线广播，30s 兜底轮询。",
                  11.5, 400, "#64748b"))
    save("14-tool-governance-design.svg", p)


def build_architecture():
    w, h = 1680, 1160
    p = start(w, h, "MCP Gateway · 工具治理架构（2026-10）",
              "Spring Boot 3.5 · 数据面 / 控制面分进程、共享内核")

    # clients
    client_y = 112
    clients = [
        ("DSH Desktop", ["discovery + ETag", "MCP Client"]),
        ("Codex CLI", ["Streamable HTTP", "JSON-RPC"]),
        ("Claude / Cursor", ["HTTP MCP", "SSE"]),
        ("自研 Agent", ["SDK / HTTP", "x-dsh-auth-token"]),
        ("管理控制台", ["/mcp/admin/**", "React 18 + Vite"]),
    ]
    for i, (title, ls) in enumerate(clients):
        p.append(box(60 + i * 316, client_y, 290, 84, title, ls, INDIGO, 14, 11))
    p.append(arrow(840, 196, 840, 236))

    # gateway outer
    p.append(rect(60, 238, 1080, 700, "#ffffff", "#cbd5e1", 16, 1.6))
    p.append(text(84, 272, "MCP Gateway · 两个可独立部署的进程", 18, 700, INK))
    p.append(text(84, 292, "共享 mcp-shared：实体 / Mapper / 暴露策略 / 身份断言 / 拓扑解析；控制面经 X-Internal-Token 调用数据面 /internal/**", 11.5, 400, MUTED))

    # data plane
    p.append(rect(84, 310, 600, 412, "#f8fafc", "#e2e8f0", 12, 1.0))
    p.append(text(102, 338, "数据面  mcp-dataplane :8081", 14, 700, "#4338ca"))
    p.append(box(102, 354, 260, 78, "Endpoint 入口", ["/mcp/e/{endpointKey}", "POST JSON-RPC · GET SSE · DELETE"], INDIGO, 13, 10.5))
    p.append(box(382, 354, 280, 78, "McpAggregateService", ["tools/list · tools/call", "Endpoint 级 META 合并"], INDIGO, 13, 10.5))
    p.append(box(102, 452, 260, 78, "UserTokenAuthenticator", ["x-dsh-auth-token", "401 / 403 / 503"], ROSE, 13, 10.5))
    p.append(box(382, 452, 280, 78, "ToolAccessPolicy / ToolPager", ["启用资产即可见 · cursor 分页", "pageSize 200 · min 500"], ROSE, 13, 10.5))
    p.append(box(102, 550, 260, 78, "ToolSearchService", ["CATALOG / KEYWORD / BM25", "VECTOR / HYBRID(RRF)"], SKY, 13, 10.5))
    p.append(box(382, 550, 280, 78, "MetaToolFactory", ["search / get_schema / call", "read_result_chunk"], VIOLET, 13, 10.5))
    p.append(box(102, 648, 260, 70, "ToolEmbeddingService", ["index / cosine / fallback", "reindex by server"], SKY, 13, 10.5))
    p.append(box(382, 648, 280, 70, "McpDiscoveryController", ["/mcp/discovery/**", "token 必填 · ETag / revision"], SLATE, 13, 10.5))

    # control plane
    p.append(rect(708, 310, 410, 412, "#f8fafc", "#e2e8f0", 12, 1.0))
    p.append(text(726, 338, "控制面  mcp-admin :8082", 14, 700, "#047857"))
    p.append(box(726, 354, 374, 64, "McpAdminController", ["Server / Tool / Tenant 写接口", "资产唯一写方 · 审批 428"], EMERALD, 13, 10.5))
    p.append(box(726, 430, 374, 64, "AccessTopologyAdminService", ["Namespace / Endpoint CRUD", "平台级 / 业务方级校验"], EMERALD, 13, 10.5))
    p.append(box(726, 506, 374, 64, "ApprovalService", ["Maker-Checker L1–L4", "AES-256-GCM · 单条处理"], EMERALD, 13, 10.5))
    p.append(box(726, 582, 374, 64, "Console", ["React 18 + Vite", "/mcp/admin/** · /mcp/log/**"], INDIGO, 13, 10.5))
    p.append(box(726, 658, 374, 64, "McpRequestLogController", ["/mcp/log/** 日志查询", "LogCleanupScheduler 清理"], SLATE, 13, 10.5))

    # cross-cutting components
    p.append(rect(84, 742, 1034, 182, "#fffdf7", "#fde68a", 12, 1.2))
    p.append(text(102, 770, "横切能力：资产缓存 / 变更广播 / 暴露预算 / 结果分片", 14, 700, "#b45309"))
    gov = [
        ("资产缓存", ["按 server 缓存", "事件驱动失效"]),
        ("ClusterEventBus", ["/internal/config-changed", "Redis Pub/Sub（可选）"]),
        ("RevisionWatcher", ["30s 兜底轮询", "版本戳比对"]),
        ("ToolExposureProperties", ["page 200 · min 500", "warn 100 · max 500"]),
        ("TokenEstimator / 分片", ["CJK-aware 预估", "chunk memory | redis"]),
    ]
    for i, (title, ls) in enumerate(gov):
        p.append(box(102 + i * 200, 786, 186, 122, title, ls, AMBER, 11.5, 10))

    # DB
    p.append(rect(60, 940, 1080, 160, "#f8fafc", "#cbd5e1", 14, 1.2))
    p.append(text(84, 970, "MySQL 8 · 17 张表（db/mysql/init.sql）· 控制面唯一写资产 · 数据面只写调用/请求日志", 14, 700, "#334155"))
    tables = [
        ("mcp_server", "租户 · 配置"),
        ("mcp_tool", "res_json / token"),
        ("mcp_endpoint", "/mcp/e/{key}"),
        ("mcp_namespace", "前缀 · 拓扑"),
        ("tool_call_record", "数据面写"),
        ("mcp_request_log", "数据面写"),
        ("approval_request", "审批 · 加密"),
        ("mcp_tool_embedding", "向量"),
    ]
    for i, (name, desc) in enumerate(tables):
        x = 84 + (i % 4) * 266
        y = 992 + (i // 4) * 40
        p.append(rect(x, y, 252, 32, "#ffffff", "#e2e8f0", 7, 1.0, False))
        p.append(text(x + 10, y + 21, name, 10.5, 700, "#334155"))
        p.append(text(x + 132, y + 21, desc, 9.5, 400, "#64748b"))

    # upstream / side services
    p.append(rect(1180, 238, 440, 472, "#f8fafc", "#e2e8f0", 14, 1.2))
    p.append(text(1204, 270, "上游 / 旁路服务", 15, 700, "#0369a1"))
    p.append(text(1204, 290, "调用 / 向量化 / token 校验", 10.5, 400, "#64748b"))
    ups = [
        ("REST API", ["HTTP 转发", "参数映射"]),
        ("MCP Server", ["Streamable HTTP", "透传 / 聚合"]),
        ("本地方法", ["反射执行", "LocalToolsService"]),
        ("Embedding 服务", ["OpenAI 兼容", "失败降级 BM25"]),
        ("用户中心", ["UserTokenStore Bean", "校验 x-dsh-auth-token"]),
    ]
    for i, (title, ls) in enumerate(ups):
        p.append(box(1204, 306 + i * 82, 392, 70, title, ls, SKY, 13.5, 10.5))
    p.append(arrow(1140, 440, 1180, 440))
    p.append(arrow(1140, 560, 1180, 560))
    p.append(arrow(1140, 655, 1180, 655))

    # clients to gateway labels
    p.append(text(828, 222, "Streamable HTTP · x-dsh-auth-token · discovery ETag", 11, 600, "#64748b", "end"))
    save("15-tool-architecture.svg", p)


def build_flow():
    w, h = 1200, 1720
    p = start(w, h, "工具链路流程图", "接入 → 定义 → 可见性 → 暴露面 → 检索 → 调用 → 运营；启用即可见、按需暴露、失败可降级")

    def stage(y, height, number, title, subtitle, palette):
        color, bg, border = palette
        return (
            rect(220, y, 760, height, bg, border, 14)
            + text(246, y + 34, f"{number}  {title}", 18, 700, color)
            + text(246, y + 58, subtitle, 11.5, 400, "#475569")
        )

    # 01
    p.append(stage(108, 112, "①", "上游接入", "REST / OpenAPI / 本地方法 / MCP 上游 / Embedding 索引", INDIGO))
    names = ["REST", "OpenAPI", "本地方法", "MCP 上游", "Embedding"]
    for i, name in enumerate(names):
        p.append(pill(246 + i * 142, 168, 128, 30, name, INDIGO, 11.5))
    p.append(arrow(600, 222, 600, 258))

    # 02
    p.append(stage(262, 100, "②", "工具定义", "mcp_tool：res_json / tags / version / token_estimate", EMERALD))
    p.append(text(1000, 300, "接入护栏：", 11, 700, "#b45309"))
    p.append(text(1000, 318, "保留名拒绝 · 超阈值告警", 10.5, 400, "#64748b"))
    p.append(arrow(600, 362, 600, 398))

    # 03
    p.append(stage(402, 100, "③", "缓存与变更", "资产缓存：按 server 缓存；ClusterEventBus 广播失效 + 30s 兜底轮询", EMERALD))
    p.append(text(1000, 440, "热路径不查库", 10.5, 400, "#64748b"))
    p.append(arrow(600, 502, 600, 538))

    # 04
    p.append(stage(542, 200, "④", "可见性治理", "谁看得到：租户边界 → Server → 资产；Endpoint 决定暴露面", ROSE))
    lines = [
        "多租户：tenant → mcp_server → tool / prompt / resource",
        "访问模式固定 ALL：启用资产对任一已认证用户可见",
        "授权模型只有 ALL；Endpoint 只决定暴露哪些 Server / 前缀",
        "tenant_id NULL = 平台级（仅超管）；N = 业务方级",
    ]
    for i, line in enumerate(lines):
        p.append(text(250, 620 + i * 27, "· " + line, 12, 400, "#475569"))
    p.append(text(1000, 620, "鉴权唯一凭证", 10.5, 400, "#64748b"))
    p.append(text(1000, 638, "x-dsh-auth-token", 11, 700, "#be123c"))
    p.append(text(1000, 660, "401 / 403 / 503", 10, 400, "#64748b"))
    p.append(arrow(600, 742, 600, 778))

    # 05
    p.append(stage(782, 250, "⑤", "暴露面", "一次给模型看多少：DIRECT 或 META", VIOLET))
    p.append(box(246, 828, 340, 168, "DIRECT", [
        "工具数 ≤ 500 整包返回",
        "> 500 才 cursor 分页",
        "分页客户端名单 / paginationRisk",
        "Schema 瘦身",
        "visibleTokens / returnedTokens",
    ], VIOLET, 16, 11.5))
    p.append(box(616, 828, 340, 168, "META", [
        "4 个元工具常驻",
        "search → schema → call → chunk",
        "热工具减少一轮推理",
        "compact / full 两种 schema",
        "isError 让模型可修正",
    ], VIOLET, 16, 11.5))
    p.append(arrow(600, 1032, 600, 1068))

    # 06
    p.append(stage(1072, 130, "⑥", "检索", "search_tools：CATALOG / KEYWORD / BM25 / VECTOR / HYBRID(RRF)", SKY))
    p.append(text(1000, 1120, "无 embedding", 10.5, 400, "#64748b"))
    p.append(text(1000, 1138, "→ 自动降级 BM25", 10.5, 600, "#0369a1"))
    p.append(arrow(600, 1202, 600, 1238))

    # 07
    p.append(stage(1242, 210, "⑦", "工具调用", "鉴权 → 校验 → 执行 → 下传 → 日志", AMBER))
    chips = [
        ("鉴权", "x-dsh-auth-token"),
        ("校验", "Draft-07"),
        ("执行", "本地 / HTTP"),
        ("下传", "JWS / JWE"),
        ("日志", "call_record"),
    ]
    for i, (title, sub) in enumerate(chips):
        p.append(box(246 + i * 144, 1300, 130, 108, title, [sub], AMBER, 13, 9.5, 12))
    p.append(arrow(600, 1452, 600, 1488))

    # 08
    p.append(stage(1492, 140, "⑧", "运营闭环", "预算告警 / 分页埋点 / 热工具推荐 / 变更广播 / 客户端矩阵", EMERALD))
    ops = ["预算告警", "分页埋点", "热工具推荐", "变更广播", "客户端矩阵"]
    for i, op in enumerate(ops):
        p.append(pill(246 + i * 142, 1556, 128, 30, op, EMERALD, 11))

    p.append(text(60, 1676, "原则：租户隔离 · 启用即可见（ALL）· 按需暴露（DIRECT / META）· 失败可降级（BM25 / OPTIONAL）· 不依赖单一客户端能力", 12, 600, "#64748b"))
    save("16-tool-chain-flow.svg", p)

if __name__ == "__main__":
    build_chain()
    build_design()
    build_architecture()
    build_flow()
