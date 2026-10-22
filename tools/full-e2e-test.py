#!/usr/bin/env python3
"""MCP Gateway 全量端到端测试（本地 dev 环境）。

DEPRECATED（2026-10）：应用鉴权/授权已移除，本脚本仍按旧的 app 创建 + 授权流程编写，
暂不可直接运行；P5 会按 x-dsh-auth-token 流程重写。

覆盖：
- 管理面：登录/租户/Server/Tool/App/授权/预算/暴露策略/导入护栏/拆分迁移
- 数据面：initialize / tools/list / tools/call / prompts / resources
- 访问治理：WHITELIST、META 元工具、分页、阈值告警、未授权拒绝
- 发现接口：server/tool 目录、ETag/304
- 清理：测试数据全部删除，不污染现有业务数据

用法：
  MCP_ADMIN_PASSWORD=... python3 tools/full-e2e-test.py
可选：
  MCP_E2E_BASE=http://127.0.0.1:8081
  MCP_E2E_PAGING_BASE=http://127.0.0.1:18082   # 用 MCP_TOOLS_PAGE_SIZE=2 启动的临时实例
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request

# 拆分后数据面与控制面是不同服务/端口：
#   数据面 /mcp/e、/mcp/v1、/mcp/discovery  -> MCP_E2E_BASE（默认 8081）
#   控制面 /mcp/admin、/mcp/log            -> MCP_E2E_ADMIN_BASE（默认 8082）
BASE = os.environ.get("MCP_E2E_BASE", "http://127.0.0.1:8081")
ADMIN_BASE = os.environ.get("MCP_E2E_ADMIN_BASE", "http://127.0.0.1:8082")
PAGING_BASE = os.environ.get("MCP_E2E_PAGING_BASE", "http://127.0.0.1:18082")
INTERNAL_TOKEN = os.environ.get("MCP_INTERNAL_TOKEN", "")
ADMIN_PASSWORD = os.environ.get("MCP_ADMIN_PASSWORD")
if not ADMIN_PASSWORD:
    raise SystemExit("缺少 MCP_ADMIN_PASSWORD")

STAMP = time.strftime("%Y%m%d%H%M%S")
TENANT_NAME = f"e2e-tenant-{STAMP}"
SERVER_NAME = f"e2e-server-{STAMP}"
SERVER_PATH = f"/mcp/v1/e2e-{STAMP}"
TARGET_NAME = f"e2e-target-{STAMP}"
TARGET_PATH = f"/mcp/v1/e2e-target-{STAMP}"
CROSS_TENANT = f"e2e-other-{STAMP}"
CROSS_SERVER_NAME = f"e2e-cross-{STAMP}"
CROSS_SERVER_PATH = f"/mcp/v1/e2e-cross-{STAMP}"
APP_ID = f"e2e-app-{STAMP}"

results: list[dict] = []
state: dict = {}


def record(name: str, ok: bool, detail: str = "") -> None:
    results.append({"name": name, "ok": ok, "detail": detail})
    print(("PASS  " if ok else "FAIL  ") + name + (("  | " + detail) if detail and not ok else ""))


def check(name: str, fn):
    try:
        detail = fn()
        record(name, True, "" if detail is None else str(detail))
        return detail
    except Exception as exc:  # noqa: BLE001
        record(name, False, f"{type(exc).__name__}: {exc}")
        return None




def check_if(name: str, value, condition):
    """在 check 内部做断言：条件不满足时记录 FAIL，而不是中断整个测试。"""
    def run():
        if not condition:
            raise AssertionError(value)
        return value
    return check(name, run)

def call(base: str, path: str, data=None, token=None, method=None, headers=None):
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(
        base + path,
        data=body,
        method=method or ("POST" if data is not None else "GET"),
    )
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, dict(resp.headers), resp.read().decode()
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read().decode()


def admin(path: str, data=None, method=None, token=None):
    token = token or state.get("admin")
    status, _, body = call(ADMIN_BASE, path, data, token, method)
    try:
        payload = json.loads(body) if body else None
    except json.JSONDecodeError:
        payload = body
    return status, payload


def expect_ok(status, payload, label: str):
    if status >= 500:
        raise AssertionError(f"{label}: HTTP {status} {payload}")
    if not isinstance(payload, dict) or payload.get("code") != 0:
        raise AssertionError(f"{label}: {status} {payload}")
    return payload.get("data")


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def app_token(app_id: str, secret: str) -> str:
    now = int(time.time())
    header = b64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = b64url(json.dumps({"iss": app_id, "iat": now, "exp": now + 3600}, separators=(",", ":")).encode())
    signing = (header + "." + payload).encode()
    sig = b64url(hmac.new(secret.encode(), signing, hashlib.sha256).digest())
    return header + "." + payload + "." + sig


def mcp(base: str, payload: dict, sid: str | None = None, token: str | None = None):
    headers = {"Accept": "application/json, text/event-stream"}
    if sid:
        headers["Mcp-Session-Id"] = sid
    status, resp_headers, body = call(base, state["server_path"], payload,
                                      token or state["app_token"], headers=headers)
    try:
        parsed = json.loads(body) if body else None
    except json.JSONDecodeError:
        parsed = body
    return status, resp_headers, parsed


def mcp_initialize(base: str):
    status, headers, payload = mcp(base, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "e2e", "version": "1.0"}},
    })
    if status != 200 or not payload or "result" not in payload:
        raise AssertionError(f"initialize failed: {status} {payload}")
    return headers.get("Mcp-Session-Id")


def main() -> int:
    # ---------- 管理面基础 ----------
    status, payload = admin("/mcp/admin/auth/login", {"username": "admin", "password": ADMIN_PASSWORD}, token="")
    data = expect_ok(status, payload, "admin login")
    state["admin"] = data["token"]
    check("admin login", lambda: "token ok")

    def bad_login():
        status, payload = admin("/mcp/admin/auth/login", {"username": "admin", "password": "wrong"}, token="")
        assert status in (400, 401, 403) or (isinstance(payload, dict) and payload.get("code") != 0), (status, payload)
        return status
    check("admin wrong password rejected", bad_login)

    def no_token():
        status, _, _ = call(ADMIN_BASE, "/mcp/admin/servers")
        assert status == 401, status
        return status
    check("admin endpoint without token -> 401", no_token)

    # ---------- 测试租户 / Server / Tools / App ----------
    tenant = expect_ok(*admin("/mcp/admin/tenants", {"name": TENANT_NAME, "status": 1}), "create tenant")
    state["tenant_id"] = tenant["id"]
    check("create tenant", lambda: tenant["id"])

    server = expect_ok(*admin("/mcp/admin/servers", {
        "tenantId": state["tenant_id"], "name": SERVER_NAME, "path": SERVER_PATH, "status": 1,
        "toolAccessMode": "WHITELIST", "toolExposureMode": "DIRECT",
    }), "create server")
    state["server_id"] = server["id"]
    state["server_key"] = server.get("serverKey") or "e2e"
    state["server_path"] = SERVER_PATH
    check("create server (WHITELIST/DIRECT)", lambda: state["server_key"])

    def create_tool(code, name, service, schema):
        return expect_ok(*admin("/mcp/admin/tools", {
            "serverId": state["server_id"], "name": name, "code": code, "type": 0, "status": 1,
            "serviceUrl": "LocalToolsService." + service, "authType": "NONE",
            "resJson": json.dumps({"name": code, "description": name, "inputSchema": schema}, ensure_ascii=False),
            "tags": "e2e,local", "version": "1.0.0",
            "needToken": 0, "bodyType": 0,
        }), "create tool " + code)

    tool_add = create_tool("e2e_add", "两数相加", "add", {
        "type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
        "required": ["a", "b"], "additionalProperties": False,
    })
    tool_echo = create_tool("e2e_echo", "回显", "echo", {
        "type": "object", "properties": {"text": {"type": "string"}}, "additionalProperties": False,
    })
    tool_time = create_tool("e2e_time", "当前时间", "currentTime", {"type": "object", "properties": {}})
    state["tool_ids"] = [tool_add["id"], tool_echo["id"], tool_time["id"]]
    check("create 3 local tools", lambda: state["tool_ids"])

    def reserved_code():
        status, payload = admin("/mcp/admin/tools", {
            "serverId": state["server_id"], "name": "bad", "code": "search_tools", "type": 0,
            "status": 1, "serviceUrl": "LocalToolsService.add", "authType": "NONE",
            "resJson": json.dumps({"name": "bad", "inputSchema": {"type": "object", "properties": {}}}),
        })
        assert isinstance(payload, dict) and payload.get("code") != 0, payload
        return payload.get("message")
    check("reserved tool code rejected", reserved_code)

    app = expect_ok(*admin("/mcp/admin/apps", {"appId": APP_ID, "tenantId": state["tenant_id"]}), "create app")
    state["app_db_id"] = app["id"]
    state["app_secret"] = app["appSecret"]
    state["app_token"] = app_token(APP_ID, state["app_secret"])
    check("create app", lambda: app["appId"])

    expect_ok(*admin("/mcp/admin/app-server-rels", {"appId": APP_ID, "serverId": state["server_id"]}), "grant server")
    rels = expect_ok(*admin("/mcp/admin/app-tool-rels"), "list app tool rels")
    auto_grants = [r for r in rels if r.get("appId") == APP_ID]
    check_if("server grant auto-grants current tools", f"{len(auto_grants)} rels", len(auto_grants) == 3)
    budgets = expect_ok(*admin("/mcp/admin/servers/budgets"), "budgets")
    my_budget = [b for b in budgets if b["serverId"] == state["server_id"]][0]
    check_if("budget snapshot", f"{my_budget['visibleTools']} tools / {my_budget['estimatedTokens']} tokens", my_budget["visibleTools"] == 3)
    def invalid_mode():
        status, payload = admin(f"/mcp/admin/servers/{state['server_id']}/tool-exposure",
                                {"toolAccessMode": "NOPE"}, method="PUT")
        assert isinstance(payload, dict) and payload.get("code") != 0, payload
        return payload.get("message")
    check("invalid access mode rejected", invalid_mode)

    # 阈值告警：3 个工具，阈值 2
    expect_ok(*admin(f"/mcp/admin/servers/{state['server_id']}/tool-exposure",
                     {"toolAccessMode": "WHITELIST", "toolExposureMode": "DIRECT",
                      "toolWarnThreshold": 2, "toolMaxThreshold": 5}, method="PUT"), "set warn threshold")
    my_budget = [b for b in expect_ok(*admin("/mcp/admin/servers/budgets"), "budgets2")
                 if b["serverId"] == state["server_id"]][0]
    check_if("over-warn budget flag", my_budget, my_budget["overWarn"] and not my_budget["overMax"])
    overview = expect_ok(*admin("/mcp/admin/overview"), "overview")
    warnings = [w for w in overview.get("toolBudgetWarnings", []) if w["serverId"] == state["server_id"]]
    check_if("overview budget warning", warnings[0] if warnings else None, bool(warnings))
    # 发现接口
    status, headers, body = call(BASE, "/mcp/discovery/servers", token=state["app_token"])
    discovery = json.loads(body)
    item = [s for s in discovery["data"] if s["id"] == state["server_id"]][0]
    check_if("discovery server toolCount/modes", item, item["toolCount"] == 3 and item["toolAccessMode"] == "WHITELIST")
    etag = headers.get("ETag") or headers.get("Etag")
    status2, _, _ = call(BASE, "/mcp/discovery/servers", token=state["app_token"],
                         headers={"If-None-Match": etag} if etag else None)
    check_if("discovery ETag -> 304", status2, status2 == 304)
    status, _, body = call(BASE, "/mcp/discovery/tools?q=e2e_add", token=state["app_token"])
    tools = json.loads(body)["data"]
    check_if("discovery tool search", len(tools), len(tools) == 1 and tools[0]["code"] == "e2e_add")
    # ---------- MCP DIRECT ----------
    sid = mcp_initialize(BASE)
    state["sid"] = sid
    check("MCP initialize", lambda: sid)

    def tools_list():
        status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, sid)
        names = [t["name"] for t in payload["result"]["tools"]]
        assert status == 200 and len(names) == 3, (status, names)
        assert payload["result"]["_meta"]["accessMode"] == "WHITELIST", payload["result"]["_meta"]
        assert payload["result"].get("nextCursor") is None
        return names
    check("tools/list DIRECT", tools_list)

    def tool_call(name, args, expect_error=False):
        status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                        "params": {"name": name, "arguments": args}}, sid)
        result = (payload or {}).get("result") or {}
        text = ""
        if result.get("content"):
            text = result["content"][0].get("text", "")
        if expect_error:
            assert result.get("isError") is True, (status, payload)
        return status, text, payload
    status, text, payload = tool_call("e2e_add", {"a": 3, "b": 4})
    check_if("tools/call add", text, "7.0" in text)
    status, text, payload = tool_call("e2e_echo", {"text": "hello"})
    check_if("tools/call echo", text, "hello" in text)
    def unknown_tool():
        status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                                        "params": {"name": "e2e_not_exist", "arguments": {}}}, sid)
        assert isinstance(payload, dict) and payload.get("error", {}).get("code") == -32002, (status, payload)
        return status
    check("unknown tool rejected", unknown_tool)

    # 撤销 echo 授权 -> list 2 / call 403
    echo_rel = [r for r in auto_grants if r["toolId"] == tool_echo["id"]][0]
    expect_ok(*admin(f"/mcp/admin/app-tool-rels/{echo_rel['id']}", method="DELETE"), "revoke echo")
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 5, "method": "tools/list"}, sid)
    names = [t["name"] for t in payload["result"]["tools"]]
    check_if("whitelist hides revoked tool", names, "e2e_echo" not in names and len(names) == 2)
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 6, "method": "tools/call",
                                    "params": {"name": "e2e_echo", "arguments": {"text": "x"}}}, sid)
    check_if("revoked tool call -> forbidden", status, status in (403, 404))
    expect_ok(*admin("/mcp/admin/app-tool-rels", {"appId": APP_ID, "toolId": tool_echo["id"]}), "restore echo")

    # ---------- Prompt / Resource ----------
    prompt = expect_ok(*admin("/mcp/admin/prompts", {
        "serverId": state["server_id"], "name": "e2e-greet", "status": 1,
        "resJson": json.dumps({"name": "e2e-greet", "description": "问候",
                               "messages": [{"role": "user", "content": {"type": "text", "text": "你好"}}]}),
    }), "create prompt")
    expect_ok(*admin("/mcp/admin/app-prompt-rels", {"appId": APP_ID, "promptId": prompt["id"]}), "grant prompt")
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 7, "method": "prompts/list"}, sid)
    check_if("prompts/list", payload["result"]["prompts"], any(p.get("name") == "e2e-greet" for p in payload["result"]["prompts"]))
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 8, "method": "prompts/get",
                                    "params": {"name": "e2e-greet"}}, sid)
    check_if("prompts/get", payload["result"], payload["result"].get("messages"))
    resource = expect_ok(*admin("/mcp/admin/resources", {
        "serverId": state["server_id"], "uri": "e2e://hello", "name": "e2e-hello", "status": 1,
        "resJson": json.dumps({"uri": "e2e://hello", "name": "e2e-hello", "description": "示例",
                               "contents": [{"uri": "e2e://hello", "mimeType": "text/plain", "text": "hello"}]}),
    }), "create resource")
    expect_ok(*admin("/mcp/admin/app-resource-rels", {"appId": APP_ID, "resourceId": resource["id"]}), "grant resource")
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 9, "method": "resources/list"}, sid)
    check_if("resources/list", payload["result"]["resources"], any(r.get("uri") == "e2e://hello" for r in payload["result"]["resources"]))
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 10, "method": "resources/read",
                                    "params": {"uri": "e2e://hello"}}, sid)
    check_if("resources/read", payload["result"], payload["result"].get("contents"))
    # ---------- META ----------
    expect_ok(*admin(f"/mcp/admin/servers/{state['server_id']}/tool-exposure", {
        "toolAccessMode": "WHITELIST", "toolExposureMode": "META",
        "hotToolCodes": ["e2e_add"], "toolWarnThreshold": 2, "toolMaxThreshold": 5,
    }, method="PUT"), "set META")
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 11, "method": "tools/list"}, sid)
    names = [t["name"] for t in payload["result"]["tools"]]
    check_if("META tools/list", names, names == ["e2e_add", "search_tools", "get_tool_schema", "call_tool"])
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 12, "method": "tools/call",
                                    "params": {"name": "search_tools", "arguments": {"query": "echo"}}}, sid)
    text = payload["result"]["content"][0]["text"]
    check_if("META search_tools", text, "e2e_echo" in text)
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 13, "method": "tools/call",
                                    "params": {"name": "get_tool_schema", "arguments": {"name": "e2e_add"}}}, sid)
    text = payload["result"]["content"][0]["text"]
    check_if("META get_tool_schema", text, "inputSchema" in text)
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 14, "method": "tools/call",
                                    "params": {"name": "call_tool", "arguments": {"name": "e2e_add", "arguments": {"a": 1, "b": 2}}}}, sid)
    text = payload["result"]["content"][0]["text"]
    check_if("META call_tool", text, "3.0" in text)
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 15, "method": "tools/call",
                                    "params": {"name": "call_tool", "arguments": {"name": "nope", "arguments": {}}}}, sid)
    check_if("META call_tool unauthorized", payload["result"], payload["result"].get("isError") is True)
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 16, "method": "tools/call",
                                    "params": {"name": "call_tool", "arguments": {"name": "call_tool", "arguments": {}}}}, sid)
    check_if("META recursion rejected", payload["result"], payload["result"].get("isError") is True)
    # 白名单对 search 生效
    echo_rel2 = [r for r in expect_ok(*admin("/mcp/admin/app-tool-rels"), "rels2") if r["appId"] == APP_ID and r["toolId"] == tool_echo["id"]][0]
    expect_ok(*admin(f"/mcp/admin/app-tool-rels/{echo_rel2['id']}", method="DELETE"), "revoke echo 2")
    status, _, payload = mcp(BASE, {"jsonrpc": "2.0", "id": 17, "method": "tools/call",
                                    "params": {"name": "search_tools", "arguments": {"query": "echo"}}}, sid)
    text = payload["result"]["content"][0]["text"]
    check_if("META search respects whitelist", text, "e2e_echo" not in text)
    expect_ok(*admin("/mcp/admin/app-tool-rels", {"appId": APP_ID, "toolId": tool_echo["id"]}), "restore echo 2")

    # ---------- 分页（临时实例 page-size=2） ----------
    # 先切回 DIRECT：META 模式只返回热工具+元工具，本就不分页
    expect_ok(*admin(f"/mcp/admin/servers/{state['server_id']}/tool-exposure", {
        "toolAccessMode": "WHITELIST", "toolExposureMode": "DIRECT", "hotToolCodes": [],
        "toolWarnThreshold": 2, "toolMaxThreshold": 5,
    }, method="PUT"), "set DIRECT for pagination")

    def refresh_paging():
        # 分页实例是纯数据面：通过内部接口刷新路由（本地回环可免 token）
        headers = {"X-Internal-Token": INTERNAL_TOKEN} if INTERNAL_TOKEN else {}
        status, _, payload = call(PAGING_BASE, "/internal/config-changed",
                                  {"changeType": "routes"}, None, "POST", headers)
        assert status < 500, (status, payload)
    check("paging instance refresh routes", refresh_paging)
    p_sid = mcp_initialize(PAGING_BASE)
    status, _, payload = mcp(PAGING_BASE, {"jsonrpc": "2.0", "id": 20, "method": "tools/list"}, p_sid)
    page1 = payload["result"]
    check_if("pagination page 1", page1.get("nextCursor"),
             len(page1.get("tools", [])) == 2 and bool(page1.get("nextCursor")))
    status, _, payload = mcp(PAGING_BASE, {"jsonrpc": "2.0", "id": 21, "method": "tools/list",
                                          "params": {"cursor": page1["nextCursor"]}}, p_sid)
    page2 = payload["result"]
    check_if("pagination page 2", len(page2.get("tools", [])),
             len(page2.get("tools", [])) == 1 and not page2.get("nextCursor"))
    status, _, payload = mcp(PAGING_BASE, {"jsonrpc": "2.0", "id": 22, "method": "tools/list",
                                          "params": {"cursor": "bad-cursor"}}, p_sid)
    check_if("pagination invalid cursor fallback", len(payload.get("result", {}).get("tools", [])),
             len(payload.get("result", {}).get("tools", [])) == 2)
    # ---------- OpenAPI 导入 + 护栏 ----------
    document = json.dumps({
        "openapi": "3.0.0",
        "info": {"title": "e2e", "version": "1.0.0"},
        "servers": [{"url": "https://api.example.com"}],
        "paths": {"/ping": {"get": {"operationId": "e2e_ping", "summary": "Ping",
                                    "responses": {"200": {"description": "ok"}}}}},
    })
    plan = expect_ok(*admin("/mcp/admin/tools/openapi/preview",
                            {"serverId": state["server_id"], "document": document}), "preview openapi")
    check_if("openapi preview", len(plan.get("tools", [])), len(plan.get("tools", [])) == 1)
    imported = expect_ok(*admin("/mcp/admin/tools/openapi/import",
                                {"serverId": state["server_id"], "document": document,
                                 "codes": ["e2e_ping"]}), "import openapi")
    check_if("openapi import + over-warn", imported, imported.get("created") == 1 and imported.get("overWarn"))
    # ---------- 拆分迁移 ----------
    target = expect_ok(*admin("/mcp/admin/servers", {
        "tenantId": state["tenant_id"], "name": TARGET_NAME, "path": TARGET_PATH, "status": 1,
        "toolAccessMode": "WHITELIST", "toolExposureMode": "DIRECT",
    }), "create target server")
    state["target_id"] = target["id"]
    moved = expect_ok(*admin("/mcp/admin/tools/bulk-move",
                             {"toolIds": [tool_echo["id"]], "targetServerId": target["id"]}), "bulk move")
    check_if("bulk move tool", moved, moved.get("moved") == 1)
    status, payload = admin("/mcp/admin/tools/bulk-move",
                            {"toolIds": [tool_echo["id"]], "targetServerId": target["id"]})
    check_if("bulk move no-op rejected", payload, isinstance(payload, dict) and payload.get("code") != 0)
    other = expect_ok(*admin("/mcp/admin/tenants", {"name": CROSS_TENANT, "status": 1}), "create other tenant")
    state["other_tenant"] = other["id"]
    cross = expect_ok(*admin("/mcp/admin/servers", {
        "tenantId": other["id"], "name": CROSS_SERVER_NAME, "path": CROSS_SERVER_PATH, "status": 1,
        "toolAccessMode": "WHITELIST", "toolExposureMode": "DIRECT",
    }), "create cross server")
    state["cross_server"] = cross["id"]
    status, payload = admin("/mcp/admin/tools/bulk-move",
                            {"toolIds": [tool_add["id"]], "targetServerId": cross["id"]})
    check_if("bulk move cross-tenant rejected", payload, isinstance(payload, dict) and payload.get("code") != 0)
    # ---------- 前端/代理快速冒烟 ----------
    def console():
        status, _, body = call("http://127.0.0.1:5173", "/")
        assert status == 200 and "root" in body, (status, body[:80])
        return status
    check("console dev page", console)

    def proxy_login():
        status, _, body = call("http://127.0.0.1:5173", "/api/mcp/admin/auth/login",
                               {"username": "admin", "password": ADMIN_PASSWORD})
        assert status == 200 and json.loads(body).get("code") == 0, (status, body[:120])
        return status
    check("console proxy -> backend", proxy_login)

    return 0


def cleanup() -> None:
    print("\n[cleanup]")
    # 删除应用（连带授权）
    if state.get("app_db_id"):
        admin(f"/mcp/admin/apps/{state['app_db_id']}", method="DELETE")
        print("  app deleted")
    # 删除 Server（级联 tool/prompt/resource/授权）
    for key in ("cross_server", "target_id", "server_id"):
        sid = state.get(key)
        if sid:
            admin(f"/mcp/admin/servers/{sid}", method="DELETE")
            print(f"  server {sid} deleted")
    # 删除租户
    for key in ("other_tenant", "tenant_id"):
        tid = state.get(key)
        if tid:
            admin(f"/mcp/admin/tenants/{tid}", method="DELETE")
            print(f"  tenant {tid} deleted")


if __name__ == "__main__":
    try:
        code = main()
    except Exception as exc:  # 初始化阶段失败：记录后仍然产出报告
        record("fatal setup", False, f"{type(exc).__name__}: {exc}")
    finally:
        cleanup()
    passed = sum(1 for r in results if r["ok"])
    failed = [r for r in results if not r["ok"]]
    print(f"\nRESULT: {passed}/{len(results)} passed, {len(failed)} failed")
    for item in failed:
        print("  FAILED:", item["name"], "|", item["detail"])
    report_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "mcp-gateway", "target")
    os.makedirs(report_dir, exist_ok=True)
    with open(os.path.join(report_dir, "e2e-report.json"), "w", encoding="utf-8") as fh:
        json.dump({"passed": passed, "total": len(results), "results": results}, fh, ensure_ascii=False, indent=2)
    sys.exit(0 if not failed else 1)
