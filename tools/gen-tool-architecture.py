#!/usr/bin/env python3
"""生成工具链路优化后的架构/设计图（SVG）。

输出：
  docs/architecture/13-tool-chain-optimized.svg
  docs/architecture/14-tool-governance-design.svg
  docs/architecture/15-tool-architecture.svg
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
    w, h = 1680, 1640
    p = start(w, h, "MCP Gateway · 工具链路优化（2026-10）",
              "从接入、可见性、暴露面、检索、调用到运营闭环：每一层只回答一个问题，避免把成本推给模型上下文")

    # 1 接入
    p.append(band(104, 150, "①", "接入与定义", ["REST / OpenAPI /", "本地方法 / MCP 上游"], INDIGO))
    titles = ["REST 接口", "OpenAPI 3.x", "本地方法", "MCP 上游", "Embedding 索引"]
    lines = [["单接口生成", "参数映射"], ["批量预览/导入", "导入超阈值告警"], ["反射执行", "class.method"], ["透传 / 聚合", "{name}__{tool}"], ["异步向量化", "失败自动降级"]]
    xs = [270, 514, 758, 1002, 1246]
    for x, title, ls in zip(xs, titles, lines):
        p.append(box(x, 128, 224, 96, title, ls, INDIGO, 14, 11))
    p.append(arrow(840, 254, 840, 282))

    # 2 定义 / 缓存
    p.append(band(284, 148, "②", "定义与缓存", ["Schema / tags /", "token_estimate"], EMERALD))
    p.append(box(270, 308, 330, 100, "mcp_tool", ["code · name · description · tags", "res_json（inputSchema）", "version · token_estimate"], EMERALD))
    p.append(box(630, 308, 320, 100, "ToolListCache", ["按 server 缓存启用工具", "热路径不查库", "工具变更主动失效"], EMERALD))
    p.append(box(980, 308, 300, 100, "ToolChangeNotifier", ["失效缓存", "广播 tools/list_changed"], EMERALD))
    p.append(box(1310, 308, 290, 100, "接入护栏", ["保留名拒绝", "token 预计算", "导入超阈值告警"], AMBER))
    p.append(arrow(840, 432, 840, 460))

    # 3 可见性
    p.append(band(462, 218, "③", "可见性治理", ["谁能看到", "Server / App / Profile"], ROSE))
    p.append(box(270, 488, 340, 92, "server.tool_access_mode", ["WHITELIST / ALL / LEGACY", "新 Server 默认 WHITELIST"], ROSE))
    p.append(box(630, 488, 420, 92, "app_server_rel", ["tool_access：INHERIT / ALL / WHITELIST", "profile_tags：按标签交集过滤"], ROSE))
    p.append(box(1070, 488, 240, 92, "app_tool_rel", ["工具级白名单", "空 = 看不到"], ROSE))
    p.append(box(1330, 488, 270, 92, "影响预检", ["切换前告诉你会", "影响哪些应用"], AMBER))
    p.append(box(270, 596, 640, 68, "切 WHITELIST 前：exposure-impact", ["受影响应用 / 当前工具授权数 / willLoseTools"], AMBER, 14, 11))
    p.append(box(930, 596, 670, 68, "补齐：backfillExistingApps / 单应用 ALL", ["避免“切完模式，工具突然消失”"], AMBER, 14, 11))
    p.append(arrow(840, 680, 840, 708))

    # 4 暴露
    p.append(band(710, 250, "④", "暴露面治理", ["一次给模型看多少", "DIRECT / META"], VIOLET))
    p.append(box(270, 738, 620, 150, "DIRECT：全量可见工具", [
        "工具数 ≤ 500 整包返回；> 500 才 cursor 分页",
        "分页客户端白名单：codex / claude / dsh / cursor",
        "未识别客户端 → _meta.paginationRisk=true",
        "Schema 瘦身：去 examples、截断 description",
    ], VIOLET, 15, 11.5))
    p.append(box(920, 738, 620, 150, "META：3 个元工具 + 热工具", [
        "search_tools → get_tool_schema → call_tool",
        "热工具常驻，减少一轮推理",
        "工具级错误 isError=true，模型可读",
        "call_tool 复用鉴权 / 校验 / 日志",
    ], VIOLET, 15, 11.5))
    p.append(box(270, 900, 1270, 42, "预算：warn=100 · max=500 · overWarn / overMax · visibleTokens / returnedTokens", [
    ], AMBER, 13, 11))
    p.append(arrow(840, 960, 840, 988))

    # 5 检索
    p.append(band(990, 180, "⑤", "检索层", ["search_tools 召回", "词法 + 语义"], SKY))
    p.append(box(270, 1018, 460, 124, "词法检索", ["CJK 单字 / 二元组（订单查询 ↔ 查询订单）", "中英同义词（退款 ↔ refund）", "近 30 天成功调用热度加权"], SKY))
    p.append(box(760, 1018, 460, 124, "Embedding 混合检索", ["mcp_tool_embedding · 按 server 内存缓存", "query 向量 + 余弦相似度", "不可用 / 超时 → 自动降级词法"], SKY))
    p.append(box(1250, 1018, 350, 124, "统一排序", ["精确 code 置顶", "top-K 返回 name / description / tags", "不返回 inputSchema"], SKY))
    p.append(arrow(840, 1170, 840, 1198))

    # 6 调用
    p.append(band(1200, 194, "⑥", "调用链路", ["鉴权 → 校验 → 执行", "→ 日志"], AMBER))
    p.append(box(270, 1228, 360, 138, "拦截器", ["app token / server grant", "app-level tool_access", "tool grant / META 元工具放行"], AMBER, 14, 11))
    p.append(box(650, 1228, 360, 138, "服务层", ["canUse + profile_tags 二次校验", "JSON Schema Draft-07", "Schema 编译缓存"], AMBER, 14, 11))
    p.append(box(1030, 1228, 260, 138, "执行器", ["本地反射", "HTTP 转发", "SSRF / 30s"], AMBER, 14, 11))
    p.append(box(1310, 1228, 290, 138, "日志", ["tool_call_record", "mcp_request_log", "脱敏 + 截断"], AMBER, 14, 11))
    p.append(arrow(840, 1394, 840, 1422))

    # 7 运营
    p.append(band(1424, 160, "⑦", "运营闭环", ["可观测、可恢复、", "可拆分"], EMERALD))
    ops = [
        ("预算告警", ["100 / 500", "首页横幅"]),
        ("分页埋点", ["issued / followed", "abandoned"]),
        ("热工具推荐", ["近 30 天 Top N", "成功调用"]),
        ("拆分迁移", ["多选 → 移动", "grantToApps"]),
        ("客户端矩阵", ["probe / Codex", "兼容策略"]),
    ]
    for i, (title, ls) in enumerate(ops):
        p.append(box(270 + i * 258, 1452, 238, 108, title, ls, EMERALD, 14, 11))
    save("13-tool-chain-optimized.svg", p)


def build_design():
    w, h = 1680, 1120
    p = start(w, h, "工具治理设计图 · 五层控制面",
              "每一层都有明确的输入、决策、数据落点与验收指标；不让模型上下文承担治理职责")
    headers = ["层", "目标", "机制", "配置 / 数据", "接口 / 验收"]
    xs = [60, 300, 650, 1030, 1350]
    widths = [220, 330, 360, 300, 270]
    p.append(rect(60, 110, 1560, 46, "#f1f5f9", "#e2e8f0", 8, 1.0, False))
    for x, wd, label in zip(xs, widths, headers):
        p.append(text(x + 16, 139, label, 13, 700, "#334155"))

    rows = [
        ("可见性", "应用只能看到被授权的工具", [
            "Server 模式：WHITELIST / ALL / LEGACY",
            "App 覆盖：INHERIT / ALL / WHITELIST",
            "画像标签：profile_tags 交集",
            "工具白名单：app_tool_rel",
        ], [
            "mcp_server.tool_access_mode",
            "app_server_rel.tool_access",
            "app_server_rel.profile_tags",
            "app_tool_rel",
        ], ["exposure-impact 预检", "backfill 补齐", "tools/call 同规则"]),
        ("暴露面", "一次只给模型看需要的内容", [
            "DIRECT：全量 + 分页 + 瘦身",
            "META：3 元工具 + 热工具",
            "热工具：手工 / 调用推荐",
            "Schema：去 examples、截断描述",
        ], [
            "tool_exposure_mode",
            "hot_tool_codes",
            "page-size / pagination-min-tools",
            "max-description-length",
        ], ["tools/list _meta", "paginationRisk", "estimatedTokens"]),
        ("检索", "在长尾里快速找到正确工具", [
            "中文分词：单字 + 二元组",
            "中英同义词扩展",
            "近 30 天热度加权",
            "Embedding 余弦混合，失败降级",
        ], [
            "mcp_tool_embedding",
            "embedding.* 配置",
            "tool_call_record（热度）",
            "search_tools 参数",
        ], ["search_tools top-K", "get_tool_schema", "reindex 接口"]),
        ("预算/传输", "让成本可见、可预警", [
            "visibleTokens / returnedTokens",
            "warn 100 / max 500",
            "token 预计算（CJK-aware）",
            "分页门槛 500 + 客户端白名单",
        ], [
            "mcp_tool.token_estimate",
            "mcp.tools.*",
            "ToolPaginationTelemetry",
            "ToolBudgetAlerter",
        ], ["_meta 预算字段", "overview 告警", "pagination stats"]),
        ("运行/闭环", "出问题能定位、能恢复", [
            "拦截器 + 服务层双重鉴权",
            "Schema 校验 / SSRF / 超时",
            "调用日志 + 请求日志",
            "拆分迁移 / 客户端矩阵",
        ], [
            "tool_call_record",
            "mcp_request_log",
            "bulk-move / probe",
            "Codex wrapper",
        ], ["审计日志", "全量 E2E", "兼容矩阵"]),
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

    p.append(text(60, 1040, "设计原则：默认拒绝（WHITELIST）· 可观测（_meta / 日志 / 埋点）· 可回退（LEGACY / DIRECT / META_ENABLED）· 不依赖单一客户端能力",
                  12, 600, "#64748b"))
    p.append(text(60, 1064, "边界处理：切换白名单先预检再补齐；分页未知客户端标 risk；Embedding 不可用降级词法；切换/迁移均有审计。",
                  11.5, 400, "#64748b"))
    save("14-tool-governance-design.svg", p)


def build_architecture():
    w, h = 1680, 1160
    p = start(w, h, "MCP Gateway · 工具治理架构（2026-10）",
              "数据面、控制面、发现面共享同一套可见性策略；Embedding 与 token 预计算作为旁路能力，不阻塞主链路")

    # clients
    client_y = 112
    clients = [
        ("DSH Desktop", ["discovery + ETag", "MCP Client"]),
        ("Codex CLI", ["streamable HTTP", "approve-for-me"]),
        ("Claude / Cursor", ["HTTP MCP", "Bearer"]),
        ("自研 Agent", ["SDK / HTTP", "tool profiles"]),
        ("管理控制台", ["/mcp/admin/**", "React + Vite"]),
    ]
    for i, (title, ls) in enumerate(clients):
        p.append(box(60 + i * 316, client_y, 290, 84, title, ls, INDIGO, 14, 11))
    p.append(arrow(840, 196, 840, 236))

    # gateway outer
    p.append(rect(60, 238, 1080, 620, "#ffffff", "#cbd5e1", 16, 1.6))
    p.append(text(84, 272, "MCP Gateway :8081", 18, 700, INK))
    p.append(text(84, 292, "Spring Boot 2.7 · 数据面 / 控制面 / 发现面同进程逻辑解耦", 11.5, 400, MUTED))

    # data plane
    p.append(rect(84, 310, 600, 330, "#f8fafc", "#e2e8f0", 12, 1.0))
    p.append(text(102, 338, "数据面  /mcp/e/{endpointKey}", 14, 700, "#4338ca"))
    p.append(box(102, 354, 260, 78, "McpController", ["initialize / tools/list", "tools/call / SSE"], INDIGO, 13, 10.5))
    p.append(box(382, 354, 280, 78, "McpServiceImpl", ["buildToolListResult", "toolsCall / meta tools"], INDIGO, 13, 10.5))
    p.append(box(102, 452, 260, 78, "ToolAccessPolicy", ["visibleTools / canUse", "profile_tags / app override"], ROSE, 13, 10.5))
    p.append(box(382, 452, 280, 78, "ToolPager / MetaToolFactory", ["cursor pagination", "search/get_schema/call"], VIOLET, 13, 10.5))
    p.append(box(102, 550, 260, 70, "ToolSearchService", ["lexical + synonym + heat", "hybrid ranking"], SKY, 13, 10.5))
    p.append(box(382, 550, 280, 70, "ToolEmbeddingService", ["index / cosine / fallback", "reindex by server"], SKY, 13, 10.5))

    # control/discovery
    p.append(rect(708, 310, 410, 330, "#f8fafc", "#e2e8f0", 12, 1.0))
    p.append(text(726, 338, "控制面 / 发现面", 14, 700, "#047857"))
    p.append(box(726, 354, 374, 64, "McpAdminController", ["Server / Tool / App / Grant", "exposure / impact / backfill"], EMERALD, 13, 10.5))
    p.append(box(726, 430, 374, 64, "Console", ["Server list / Settings", "Permissions / Tools"], INDIGO, 13, 10.5))
    p.append(box(726, 506, 374, 64, "McpDiscoveryService", ["servers / tools / ETag", "toolCount / modes / overWarn"], SLATE, 13, 10.5))
    p.append(box(726, 582, 374, 48, "ToolChangeNotifier", ["invalidate + list_changed"], SLATE, 13, 10.5))

    # governance components
    p.append(rect(84, 660, 1034, 176, "#fffdf7", "#fde68a", 12, 1.2))
    p.append(text(102, 688, "治理与旁路能力", 14, 700, "#b45309"))
    gov = [
        ("ToolExposureProperties", ["page size / thresholds", "pagination clients"]),
        ("TokenEstimator", ["CJK-aware estimate", "precompute on save"]),
        ("ToolPaginationTelemetry", ["issued / followed", "abandoned"]),
        ("ToolSchemaTrimmer", ["examples removal", "description limit"]),
        ("ToolBudgetAlerter", ["warn / max", "throttled logs"]),
    ]
    for i, (title, ls) in enumerate(gov):
        p.append(box(102 + i * 200, 704, 186, 112, title, ls, AMBER, 11.5, 10))

    # DB
    p.append(rect(60, 900, 1080, 150, "#f8fafc", "#cbd5e1", 14, 1.2))
    p.append(text(84, 930, "MySQL 8 · 工具治理相关表", 14, 700, "#334155"))
    tables = [
        ("mcp_server", "mode / thresholds"),
        ("mcp_tool", "res_json / token"),
        ("app_server_rel", "access / profile"),
        ("app_tool_rel", "whitelist"),
        ("tool_call_record", "popularity / audit"),
        ("mcp_tool_embedding", "vectors"),
    ]
    for i, (name, desc) in enumerate(tables):
        x = 84 + (i % 3) * 340
        y = 950 + (i // 3) * 40
        p.append(rect(x, y, 320, 32, "#ffffff", "#e2e8f0", 7, 1.0, False))
        p.append(text(x + 12, y + 21, name, 11.5, 700, "#334155"))
        p.append(text(x + 190, y + 21, desc, 10, 400, "#64748b"))

    # upstream
    p.append(rect(1180, 238, 440, 400, "#f8fafc", "#e2e8f0", 14, 1.2))
    p.append(text(1204, 270, "上游 / 旁路服务", 15, 700, "#0369a1"))
    ups = [
        ("REST API", ["HTTP 转发", "参数映射"]),
        ("MCP Server", ["透传 / 聚合", "streamable HTTP"]),
        ("本地方法", ["反射执行", "LocalToolsService"]),
        ("Embedding 服务", ["OpenAI 兼容", "失败降级"]),
    ]
    for i, (title, ls) in enumerate(ups):
        p.append(box(1204, 292 + i * 82, 392, 70, title, ls, SKY, 13.5, 10.5))
    p.append(arrow(1140, 440, 1180, 440))
    p.append(arrow(1140, 560, 1180, 560))
    p.append(text(1142, 430, "调用 / 索引", 10.5, 600, "#64748b", "end"))
    p.append(text(1142, 550, "向量化", 10.5, 600, "#64748b", "end"))

    # clients to gateway labels
    p.append(text(840, 222, "Streamable HTTP · Bearer · discovery ETag", 11, 600, "#64748b", "middle"))
    p.append(text(840, 884, "读 / 写", 10.5, 600, "#64748b", "middle"))
    save("15-tool-architecture.svg", p)




def build_flow():
    w, h = 1200, 1720
    p = start(w, h, "工具链路流程图", "接入 → 定义 → 可见性 → 暴露面 → 检索 → 调用 → 运营；默认拒绝、按需暴露、失败可降级")

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
    p.append(stage(402, 100, "③", "缓存与变更", "ToolListCache：按 server 缓存；变更失效 + tools/list_changed", EMERALD))
    p.append(text(1000, 440, "热路径不查库", 10.5, 400, "#64748b"))
    p.append(arrow(600, 502, 600, 538))

    # 04
    p.append(stage(542, 200, "④", "可见性治理", "谁能看到：Server 模式 + 应用覆盖 + 画像标签 + 工具白名单", ROSE))
    lines = [
        "server.tool_access_mode：WHITELIST / ALL / LEGACY",
        "app_server_rel.tool_access：INHERIT / ALL / WHITELIST",
        "app_server_rel.profile_tags：按标签交集过滤",
        "app_tool_rel + 切换前 exposure-impact 预检 / backfill 补齐",
    ]
    for i, line in enumerate(lines):
        p.append(text(250, 620 + i * 27, "· " + line, 12, 400, "#475569"))
    p.append(text(1000, 620, "新 Server 默认", 10.5, 400, "#64748b"))
    p.append(text(1000, 638, "WHITELIST", 11, 700, "#be123c"))
    p.append(arrow(600, 742, 600, 778))

    # 05
    p.append(stage(782, 250, "⑤", "暴露面", "一次给模型看多少：DIRECT 或 META", VIOLET))
    p.append(box(246, 828, 340, 168, "DIRECT", [
        "工具数 ≤ 500 整包返回",
        "> 500 才 cursor 分页",
        "客户端白名单 / paginationRisk",
        "Schema 瘦身",
        "visibleTokens / returnedTokens",
    ], VIOLET, 16, 11.5))
    p.append(box(616, 828, 340, 168, "META", [
        "3 个元工具常驻",
        "search_tools → get_schema → call",
        "热工具减少一轮推理",
        "isError 让模型可修正",
        "returnedTokens 通常几百",
    ], VIOLET, 16, 11.5))
    p.append(arrow(600, 1032, 600, 1068))

    # 06
    p.append(stage(1072, 130, "⑥", "检索", "search_tools：CJK 分词 + 中英同义词 + 热度 + Embedding 混合", SKY))
    p.append(text(1000, 1120, "Embedding 失败", 10.5, 400, "#64748b"))
    p.append(text(1000, 1138, "→ 自动降级词法", 10.5, 600, "#0369a1"))
    p.append(arrow(600, 1202, 600, 1238))

    # 07
    p.append(stage(1242, 210, "⑦", "工具调用", "鉴权 → 校验 → 执行 → 日志", AMBER))
    chips = [
        ("拦截器", "token / grant / META"),
        ("服务层", "canUse + profile"),
        ("Schema", "Draft-07 校验"),
        ("执行", "本地 / HTTP / SSRF"),
        ("日志", "call_record / 脱敏"),
    ]
    for i, (title, sub) in enumerate(chips):
        p.append(box(246 + i * 144, 1300, 130, 108, title, [sub], AMBER, 13, 9.5, 12))
    p.append(arrow(600, 1452, 600, 1488))

    # 08
    p.append(stage(1492, 140, "⑧", "运营闭环", "预算告警 / 分页埋点 / 热工具推荐 / 拆分迁移 / 客户端矩阵", EMERALD))
    ops = ["预算告警", "分页埋点", "热工具推荐", "拆分迁移", "客户端矩阵"]
    for i, op in enumerate(ops):
        p.append(pill(246 + i * 142, 1556, 128, 30, op, EMERALD, 11))

    p.append(text(60, 1676, "原则：默认拒绝 · 可观测 · 可回退（LEGACY / DIRECT / META_ENABLED）· 不依赖单一客户端能力", 12, 600, "#64748b"))
    save("16-tool-chain-flow.svg", p)

if __name__ == "__main__":
    build_chain()
    build_design()
    build_architecture()
    build_flow()
