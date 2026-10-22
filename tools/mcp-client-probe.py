#!/usr/bin/env python3
"""MCP 客户端能力探针：检查传输、鉴权、分页、META 元工具是否可用。

用法：
  python3 tools/mcp-client-probe.py \
    --url http://127.0.0.1:8081/mcp/v1/pcb-server \
    --token-file /path/to/token

输出 JSON，可直接贴进客户端兼容矩阵。
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

HEADERS = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}


def call(url: str, token: str, payload: dict, sid: str | None = None):
    headers = dict(HEADERS)
    if token:
        headers["Authorization"] = "Bearer " + token
    if sid:
        headers["Mcp-Session-Id"] = sid
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode()
            return resp.status, dict(resp.headers), json.loads(body) if body else None
    except urllib.error.HTTPError as exc:
        body = exc.read().decode(errors="replace")
        try:
            parsed = json.loads(body) if body else None
        except json.JSONDecodeError:
            parsed = body
        return exc.code, dict(exc.headers), parsed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8081/mcp/v1/pcb-server")
    parser.add_argument("--token-file", default="")
    parser.add_argument("--token", default="")
    parser.add_argument("--query", default="add")
    args = parser.parse_args()

    token = args.token
    if not token and args.token_file:
        token = open(args.token_file, encoding="utf-8").read().strip()

    report: dict = {
        "url": args.url,
        "initialize": False,
        "toolsList": False,
        "pagination": False,
        "metaTools": False,
        "searchTools": False,
        "toolCount": 0,
        "nextCursor": None,
        "meta": None,
        "error": None,
    }

    status, headers, payload = call(args.url, token, {
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "mcp-client-probe", "version": "1.0"}},
    })
    if status != 200 or not payload or "result" not in payload:
        report["error"] = {"stage": "initialize", "status": status, "payload": payload}
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1
    sid = headers.get("Mcp-Session-Id")
    report["initialize"] = True
    report["sessionId"] = bool(sid)
    report["serverInfo"] = (payload.get("result") or {}).get("serverInfo")

    status, _, payload = call(args.url, token, {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, sid)
    if status != 200 or not payload or "result" not in payload:
        report["error"] = {"stage": "tools/list", "status": status, "payload": payload}
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1
    result = payload["result"]
    tools = result.get("tools", [])
    report["toolsList"] = True
    report["toolCount"] = len(tools)
    report["toolNames"] = [t.get("name") for t in tools]
    report["nextCursor"] = result.get("nextCursor")
    report["meta"] = result.get("_meta")
    report["metaTools"] = any(t.get("name") == "search_tools" for t in tools)

    if report["nextCursor"]:
        status, _, payload2 = call(args.url, token, {
            "jsonrpc": "2.0", "id": 3, "method": "tools/list",
            "params": {"cursor": report["nextCursor"]},
        }, sid)
        report["pagination"] = status == 200 and bool(payload2 and payload2.get("result"))
        report["page2ToolCount"] = len(((payload2 or {}).get("result") or {}).get("tools", []))

    if report["metaTools"]:
        status, _, payload3 = call(args.url, token, {
            "jsonrpc": "2.0", "id": 4, "method": "tools/call",
            "params": {"name": "search_tools", "arguments": {"query": args.query, "limit": 5}},
        }, sid)
        text = ""
        try:
            text = payload3["result"]["content"][0]["text"]
        except Exception:  # noqa: BLE001
            text = json.dumps(payload3, ensure_ascii=False)
        report["searchTools"] = status == 200 and "tools" in text
        report["searchResult"] = text[:500]

    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
