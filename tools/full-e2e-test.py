#!/usr/bin/env python3
"""MCP Gateway 端到端回归（2026-10 版）。

覆盖当前架构的最小闭环：
  管理面：登录 → 租户 → Server → 本地 Tool → Namespace → Endpoint
  数据面：/mcp/e/{endpointKey} 的 initialize / tools/list / tools/call / 会话删除
  发现：/mcp/discovery/servers（ETag/304）、/mcp/discovery/tools
  健康：/mcp/admin/health/servers
  下线校验：/mcp/v1/{serverPath} 必须 404
  内部：/internal/cluster-info（配置了 X-Internal-Token 时）

前置：
  1. 数据面 8081、控制面 8082 已启动；
  2. 控制面能登录（MCP_ADMIN_PASSWORD）；
  3. 数据面能校验用户 token —— 本地/E2E 用静态 token 启动即可：
       MCP_AUTH_STATIC_TOKENS='e2e-token|u-e2e|E2E 用户|C-E2E|tool:read,tool:write' \
       ./run-local.sh
     然后把同一个 token 传给本脚本：MCP_E2E_USER_TOKEN=e2e-token
     （生产环境由 user-center 提供 UserTokenStore，无需静态 token）

用法：
  MCP_ADMIN_PASSWORD=... MCP_E2E_USER_TOKEN=e2e-token python3 tools/full-e2e-test.py

可选环境变量：
  MCP_E2E_BASE=http://127.0.0.1:8081       数据面
  MCP_E2E_ADMIN_BASE=http://127.0.0.1:8082 控制面
  MCP_INTERNAL_TOKEN=...                   控制面→数据面内部凭证
  MCP_E2E_KEEP=1                           失败时保留测试数据
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("MCP_E2E_BASE", "http://127.0.0.1:8081").rstrip("/")
ADMIN_BASE = os.environ.get("MCP_E2E_ADMIN_BASE", "http://127.0.0.1:8082").rstrip("/")
ADMIN_PASSWORD = os.environ.get("MCP_ADMIN_PASSWORD")
USER_TOKEN = os.environ.get("MCP_E2E_USER_TOKEN")
INTERNAL_TOKEN = os.environ.get("MCP_INTERNAL_TOKEN", "")
KEEP = os.environ.get("MCP_E2E_KEEP") == "1"

STAMP = time.strftime("%Y%m%d%H%M%S")
TENANT_NAME = f"e2e-tenant-{STAMP}"
SERVER_NAME = f"e2e-server-{STAMP}"
SERVER_PATH = f"/assets/e2e-{STAMP}"
ENDPOINT_KEY = f"e2e-{STAMP}"
NAMESPACE_PREFIX = f"e2e{STAMP[-6:]}"

if not ADMIN_PASSWORD:
    raise SystemExit("缺少 MCP_ADMIN_PASSWORD（控制台超管密码）")
if not USER_TOKEN:
    raise SystemExit(
        "缺少 MCP_E2E_USER_TOKEN。\n"
        "本地/E2E 请用静态 token 启动数据面：\n"
        "  MCP_AUTH_STATIC_TOKENS='e2e-token|u-e2e|E2E 用户|C-E2E|tool:read,tool:write' ./run-local.sh\n"
        "然后：MCP_E2E_USER_TOKEN=e2e-token python3 tools/full-e2e-test.py"
    )

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
    def run():
        if not condition:
            raise AssertionError(value)
        return value
    return check(name, run)


def call(base: str, path: str, data=None, headers=None, method=None, raw_body=None):
    body = raw_body
    if data is not None:
        body = json.dumps(data).encode()
    req = urllib.request.Request(
        base + path,
        data=body,
        method=method or ("POST" if body is not None else "GET"),
    )
    req.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, dict(resp.headers), resp.read().decode()
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers), exc.read().decode()


def admin(path: str, data=None, method=None, headers=None):
    h = {"Authorization": "Bearer " + state["admin"]} if state.get("admin") else {}
    h.update(headers or {})
    status, resp_headers, body = call(ADMIN_BASE, path, data, h, method)
    try:
        payload = json.loads(body) if body else None
    except json.JSONDecodeError:
        payload = body
    return status, resp_headers, payload


def expect_ok(result, label: str):
    status, _, payload = result
    if status >= 500:
        raise AssertionError(f"{label}: HTTP {status} {payload}")
    if not isinstance(payload, dict) or payload.get("code") != 0:
        raise AssertionError(f"{label}: {status} {payload}")
    return payload.get("data")


def mcp(payload: dict, sid: str | None = None, token: str | None = None):
    headers = {
        "x-dsh-auth-token": token or USER_TOKEN,
        "Accept": "application/json, text/event-stream",
    }
    if sid:
        headers["Mcp-Session-Id"] = sid
    status, resp_headers, body = call(
        BASE, f"/mcp/e/{ENDPOINT_KEY}", payload, headers=headers
    )
    try:
        parsed = json.loads(body) if body else None
    except json.JSONDecodeError:
        parsed = body
    return status, resp_headers, parsed


def main() -> int:
    # ---------- 控制面登录 ----------
    state["admin"] = expect_ok(
        admin("/mcp/admin/auth/login", {"username": "admin", "password": ADMIN_PASSWORD}),
        "admin login",
    )["token"]
    record("admin login", True)

    no_token_status = call(ADMIN_BASE, "/mcp/admin/servers")[0]
    check_if("admin endpoint without token -> 401", no_token_status, no_token_status == 401)

    # ---------- 租户 / Server / 本地 Tool ----------
    tenant = expect_ok(admin("/mcp/admin/tenants", {"name": TENANT_NAME, "status": 1}), "create tenant")
    state["tenant_id"] = tenant["id"]

    server = expect_ok(admin("/mcp/admin/servers", {
        "tenantId": state["tenant_id"],
        "name": SERVER_NAME,
        "path": SERVER_PATH,
        "status": 1,
        "toolExposureMode": "DIRECT",
        "identityMode": "OFF",
    }), "create server")
    state["server_id"] = server["id"]
    state["server_key"] = server.get("serverKey")
    record("create tenant + server", True,
           f"server={state['server_id']} key={state['server_key']}")

    def create_tool(code, name, method, schema):
        return expect_ok(admin("/mcp/admin/tools", {
            "serverId": state["server_id"], "name": name, "code": code, "type": 0, "status": 1,
            "serviceUrl": "LocalToolsService." + method, "authType": "NONE",
            "resJson": json.dumps({"name": code, "description": name, "inputSchema": schema},
                                  ensure_ascii=False),
            "tags": "e2e,local", "version": "1.0.0", "needToken": 0, "bodyType": 0,
        }), "create tool " + code)

    create_tool("e2e_add", "两数相加", "add", {
        "type": "object",
        "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
        "required": ["a", "b"],
        "additionalProperties": False,
    })
    create_tool("e2e_echo", "回显", "echo", {
        "type": "object", "properties": {"text": {"type": "string"}},
        "additionalProperties": False,
    })
    record("create 2 local tools", True)

    # ---------- Namespace / Endpoint ----------
    ns = expect_ok(admin("/mcp/admin/namespaces", {
        "name": f"E2E {STAMP}", "prefix": NAMESPACE_PREFIX, "status": 1,
    }), "create namespace")
    state["namespace_id"] = ns["id"]
    expect_ok(admin(f"/mcp/admin/namespaces/{ns['id']}/servers",
                    {"serverIds": [state["server_id"]]}, method="PUT"),
              "bind namespace server")

    ep = expect_ok(admin("/mcp/admin/endpoints", {
        "endpointKey": ENDPOINT_KEY, "name": f"E2E {STAMP}", "status": 1,
    }), "create endpoint")
    state["endpoint_id"] = ep["id"]
    expect_ok(admin(f"/mcp/admin/endpoints/{ep['id']}/namespaces",
                    {"namespaceIds": [ns["id"]]}, method="PUT"),
              "bind endpoint namespace")
    record("create namespace + endpoint", True, f"endpoint={ENDPOINT_KEY}")

    # ---------- 数据面 MCP 会话 ----------
    status, resp_headers, payload = mcp({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "e2e", "version": "1.0"}},
    })
    sid = resp_headers.get("Mcp-Session-Id") or resp_headers.get("mcp-session-id")
    check_if("initialize returns session", sid,
             status == 200 and bool(sid) and payload and "result" in payload)
    if not sid:
        raise AssertionError("initialize 未返回 Mcp-Session-Id，后续数据面用例无法执行")

    exposed: dict = {}

    def list_tools():
        s, _, body = mcp({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, sid)
        names = [t.get("name") for t in (body or {}).get("result", {}).get("tools", [])]
        add = next((n for n in names if n == "e2e_add" or n.endswith("__e2e_add")), None)
        assert s == 200 and add, (s, body)
        exposed["add"] = add
        return names
    check("tools/list contains local tools", list_tools)

    def call_add():
        s, _, body = mcp({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                          "params": {"name": exposed["add"], "arguments": {"a": 3, "b": 4}}}, sid)
        text = json.dumps(body, ensure_ascii=False)
        assert s == 200 and "7" in text, (s, body)
        return "3 + 4 = 7"
    check("tools/call add", call_add)

    def unknown_tool():
        s, _, body = mcp({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                          "params": {"name": exposed["add"] + "_missing", "arguments": {}}}, sid)
        result = (body or {}).get("result") or {}
        assert s == 200 and ((body or {}).get("error") or result.get("isError")), (s, body)
        return "rejected"
    check("unknown tool rejected", unknown_tool)

    # ---------- 发现 ----------
    def discovery():
        h = {"x-dsh-auth-token": USER_TOKEN}
        s, headers, body = call(BASE, "/mcp/discovery/servers", headers=h)
        assert s == 200, (s, body)
        data = json.loads(body).get("data") or []
        item = next((x for x in data if x.get("serverKey") == ENDPOINT_KEY), None)
        assert item is not None, data
        assert item.get("path") == f"/mcp/e/{ENDPOINT_KEY}", item
        etag = headers.get("ETag") or headers.get("etag")
        assert etag, headers
        s2, _, _ = call(BASE, "/mcp/discovery/servers",
                        headers={"x-dsh-auth-token": USER_TOKEN, "If-None-Match": etag})
        assert s2 == 304, s2
        return f"{len(data)} endpoints, etag ok"
    check("discovery servers + ETag/304", discovery)

    def discovery_tools():
        q = urllib.parse.urlencode({"q": "e2e_add"})
        h = {"x-dsh-auth-token": USER_TOKEN}
        s, _, body = call(BASE, "/mcp/discovery/tools?" + q, headers=h)
        assert s == 200, (s, body)
        tools = json.loads(body).get("data") or []
        assert any("e2e_add" in json.dumps(t, ensure_ascii=False) for t in tools), tools
        return f"{len(tools)} matches"
    check("discovery tool search", discovery_tools)

    # ---------- 健康 ----------
    def health():
        s, _, body = admin("/mcp/admin/health/servers")
        assert s == 200 and body.get("code") == 0, (s, body)
        rows = body.get("data") or []
        row = next((r for r in rows if r.get("serverId") == state["server_id"]
                    or r.get("id") == state["server_id"]), None)
        assert row is not None, rows
        return row.get("status")
    check("admin health view", health)

    # ---------- 下线校验 ----------
    def legacy_route_gone():
        s, _, _ = call(BASE, SERVER_PATH,
                       data={"jsonrpc": "2.0", "id": 9, "method": "ping"},
                       headers={"x-dsh-auth-token": USER_TOKEN})
        assert s == 404, s
        return 404
    check("legacy /mcp/v1 route removed", legacy_route_gone)

    # ---------- 内部自检 ----------
    if INTERNAL_TOKEN:
        def cluster_info():
            s, _, body = call(BASE, "/internal/cluster-info",
                              headers={"X-Internal-Token": INTERNAL_TOKEN})
            assert s == 200, (s, body)
            data = json.loads(body)
            assert data.get("sessionStore") in ("memory", "redis"), data
            return data.get("sessionStore")
        check("internal cluster-info", cluster_info)

    # ---------- 会话清理 ----------
    def terminate_session():
        s, _, _ = call(BASE, f"/mcp/e/{ENDPOINT_KEY}", headers={
            "x-dsh-auth-token": USER_TOKEN, "Mcp-Session-Id": sid,
        }, method="DELETE")
        assert s in (204, 200), s
        return s
    check("delete session", terminate_session)

    return 0


def cleanup() -> None:
    if KEEP:
        print("\n[cleanup] 已跳过（MCP_E2E_KEEP=1）")
        return
    print("\n[cleanup]")
    for path in (
        f"/mcp/admin/endpoints/{state.get('endpoint_id')}" if state.get("endpoint_id") else None,
        f"/mcp/admin/namespaces/{state.get('namespace_id')}" if state.get("namespace_id") else None,
        f"/mcp/admin/servers/{state.get('server_id')}" if state.get("server_id") else None,
        f"/mcp/admin/tenants/{state.get('tenant_id')}" if state.get("tenant_id") else None,
    ):
        if not path:
            continue
        try:
            admin(path, method="DELETE")
            print("  deleted", path)
        except Exception as exc:  # noqa: BLE001
            print("  cleanup failed", path, exc)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001
        record("fatal", False, f"{type(exc).__name__}: {exc}")
    finally:
        cleanup()

    passed = sum(1 for r in results if r["ok"])
    failed = [r for r in results if not r["ok"]]
    print(f"\nRESULT: {passed}/{len(results)} passed, {len(failed)} failed")
    for item in failed:
        print("  FAILED:", item["name"], "|", item["detail"])

    report_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                              "mcp-gateway", "target")
    os.makedirs(report_dir, exist_ok=True)
    with open(os.path.join(report_dir, "e2e-report.json"), "w", encoding="utf-8") as fh:
        json.dump({"passed": passed, "total": len(results), "results": results},
                  fh, ensure_ascii=False, indent=2)
    sys.exit(0 if not failed else 1)
