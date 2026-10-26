#!/usr/bin/env python3
"""RBAC + 审批流端到端测试（本地 dev）。

验证：
- 开发者不能直接创建 Server（428 / 审批提示）
- 开发者提交 SERVER_CREATE 审批
- 管理员审批通过后 Server 落库并注册
- 租户隔离：开发者看不到其他租户的 Server
- 清理测试用户/Server/租户
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

# 本脚本只打控制面（/mcp/admin/**），拆分后默认指向 8082
BASE = os.environ.get("MCP_E2E_ADMIN_BASE", "http://127.0.0.1:8082")
ADMIN_PASSWORD = os.environ.get("MCP_ADMIN_PASSWORD")
if not ADMIN_PASSWORD:
    raise SystemExit("缺少 MCP_ADMIN_PASSWORD")
STAMP = time.strftime("%Y%m%d%H%M%S")
TENANT_NAME = f"e2e-rbac-{STAMP}"
USERNAME = f"e2e-dev-{STAMP}"
PASSWORD = "DevPass123!"
SERVER_NAME = f"e2e-rbac-server-{STAMP}"
SERVER_PATH = f"/assets/e2e-rbac-{STAMP}"

results = []
state = {}


def record(name, ok, detail=""):
    results.append({"name": name, "ok": ok, "detail": detail})
    print(("PASS  " if ok else "FAIL  ") + name + (("  | " + detail) if detail and not ok else ""))


def check(name, fn):
    try:
        value = fn()
        record(name, True, "" if value is None else str(value))
        return value
    except Exception as exc:  # noqa: BLE001
        record(name, False, f"{type(exc).__name__}: {exc}")
        return None


def call(path, data=None, token=None, method=None):
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(BASE + path, data=body, method=method or ("POST" if data is not None else "GET"))
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        body = exc.read().decode()
        try:
            return exc.code, json.loads(body)
        except json.JSONDecodeError:
            return exc.code, body


def ok(pair, label):
    status, payload = pair
    if status >= 500 or not isinstance(payload, dict) or payload.get("code") != 0:
        raise AssertionError(f"{label}: {status} {payload}")
    return payload.get("data")


def main():
    admin = ok(call("/mcp/admin/auth/login", {"username": "admin", "password": ADMIN_PASSWORD}, token=""), "admin login")["token"]
    state["admin"] = admin
    check("admin login", lambda: "ok")

    tenant = ok(call("/mcp/admin/tenants", {"name": TENANT_NAME, "status": 1}, admin), "create tenant")
    state["tenant"] = tenant["id"]
    check("create test tenant", lambda: tenant["id"])

    def create_user():
        return ok(call("/mcp/admin/users", {
            "username": USERNAME, "password": PASSWORD, "name": "E2E Dev",
            "role": "DEVELOPER", "status": 1, "tenantIds": [state["tenant"]],
        }, admin), "create developer")
    user = create_user()
    state["user"] = user["id"]
    check("create developer user", lambda: user["id"])

    def dev_login():
        token = ok(call("/mcp/admin/auth/login", {"username": USERNAME, "password": PASSWORD}, token=""), "dev login")["token"]
        state["dev"] = token
        return "ok"
    check("developer login", dev_login)

    def dev_isolation():
        servers = ok(call("/mcp/admin/servers", token=state["dev"]), "dev servers")
        assert all(s["tenantId"] == state["tenant"] for s in servers), servers
        return f"{len(servers)} visible"
    check("developer sees only own tenant", dev_isolation)

    def dev_direct_create():
        status, payload = call("/mcp/admin/servers", {
            "tenantId": state["tenant"], "name": SERVER_NAME, "path": SERVER_PATH, "status": 1,
            "toolAccessMode": "WHITELIST", "toolExposureMode": "DIRECT",
        }, state["dev"])
        assert status == 428 or (isinstance(payload, dict) and payload.get("code") == 428), (status, payload)
        return payload.get("message")
    check("developer direct create -> 428", dev_direct_create)

    def create_approval():
        data = ok(call("/mcp/admin/approvals", {
            "action": "SERVER_CREATE",
            "reason": "e2e approval flow",
            "payload": {
                "tenantId": state["tenant"], "name": SERVER_NAME, "path": SERVER_PATH, "status": 1,
                "toolAccessMode": "WHITELIST", "toolExposureMode": "DIRECT",
            },
        }, state["dev"]), "create approval")
        state["approval"] = data["id"]
        return data["id"]
    check("developer submits SERVER_CREATE approval", create_approval)

    def approve():
        data = ok(call(f"/mcp/admin/approvals/{state['approval']}/approve", {"comment": "approved by e2e"}, state["admin"]), "approve")
        return data.get("status")
    check("admin approves", approve)

    def server_created():
        servers = ok(call("/mcp/admin/servers", token=state["admin"]), "list servers")
        mine = [s for s in servers if s["name"] == SERVER_NAME]
        assert len(mine) == 1, mine
        state["server"] = mine[0]["id"]
        assert mine[0].get("toolAccessMode") == "WHITELIST", mine[0]
        return mine[0]["path"]
    check("approved server created", server_created)

    return 0


def cleanup():
    print("\n[cleanup]")
    if state.get("server"):
        call(f"/mcp/admin/servers/{state['server']}", method="DELETE", token=state.get("admin"))
        print("  server deleted")
    if state.get("approval"):
        # 审批单随 server 删除被清理；此处不单独删除
        pass
    if state.get("user"):
        call(f"/mcp/admin/users/{state['user']}", method="DELETE", token=state.get("admin"))
        print("  user deleted")
    if state.get("tenant"):
        call(f"/mcp/admin/tenants/{state['tenant']}", method="DELETE", token=state.get("admin"))
        print("  tenant deleted")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:  # noqa: BLE001
        record("fatal setup", False, f"{type(exc).__name__}: {exc}")
    finally:
        cleanup()
    failed = [r for r in results if not r["ok"]]
    passed = len(results) - len(failed)
    print(f"\nRESULT: {passed}/{len(results)} passed, {len(failed)} failed")
    for item in failed:
        print("  FAILED:", item["name"], "|", item["detail"])
    sys.exit(0 if not failed else 1)
