#!/usr/bin/env python3
"""测量多 Endpoint 接入的连接数与上下文成本（P1-7 决策依据）。

只依赖 Python 标准库。

用法：
  python3 tools/measure-endpoints.py \
    --gateway http://127.0.0.1:8081 \
    --token-file ~/.dsh/mcp-gateway/token

输出：
  - Endpoint 数量（= DSH mcp-client 连接数）
  - 每个 Endpoint 的暴露工具数 / 可见工具数 / Token 估算
  - 与「单聚合端点」场景的对比与决策建议
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def parse_args():
    parser = argparse.ArgumentParser(description="Measure MCP Gateway endpoint exposure cost")
    parser.add_argument("--gateway", default="http://127.0.0.1:8081", help="Gateway base URL")
    parser.add_argument("--token-file", help="File containing the app JWT")
    parser.add_argument("--token", help="App JWT (prefer --token-file)")
    parser.add_argument("--json", action="store_true", help="Print raw JSON")
    return parser.parse_args()


def read_token(args):
    if args.token:
        return args.token.strip()
    if args.token_file:
        path = os.path.expanduser(args.token_file)
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read().strip()
    raise SystemExit("缺少 --token-file 或 --token")


def fetch_discovery(gateway, token):
    url = gateway.rstrip("/") + "/mcp/discovery/servers"
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise SystemExit("discovery 请求失败: HTTP %s %s" % (error.code, error.reason))
    except urllib.error.URLError as error:
        raise SystemExit("无法连接网关 %s: %s" % (gateway, error.reason))


def estimate_exposed_tokens(item):
    visible_tokens = int(item.get("estimatedVisibleTokens") or 0)
    visible_tools = int(item.get("toolCount") or 0)
    exposed_tools = int(item.get("exposedToolCount") or item.get("toolCount") or 0)
    if visible_tools <= 0:
        return 0
    # 按可见工具的平均 token 成本折算实际暴露的工具
    return int(visible_tokens * exposed_tools / visible_tools)


def main():
    args = parse_args()
    token = read_token(args)
    data = fetch_discovery(args.gateway, token)
    if isinstance(data, list):
        endpoints = data
    elif isinstance(data, dict) and isinstance(data.get("data"), list):
        # /mcp/discovery/servers 走统一 Result 包装：{"code":0,"data":[...]}
        endpoints = data["data"]
    else:
        endpoints = data.get("servers", [])

    if args.json:
        print(json.dumps(endpoints, ensure_ascii=False, indent=2))
        return

    if not endpoints:
        print("没有已授权的 Endpoint（发现接口返回空）。")
        return

    print("Endpoint 数（DSH 连接数）: %d" % len(endpoints))
    print("-" * 104)
    print("%-24s %-8s %8s %8s %10s %12s" % (
        "endpointKey", "mode", "servers", "visible", "exposed", "tokens(approx)"))
    total_exposed = 0
    total_tokens = 0
    max_exposed = 0
    for item in endpoints:
        exposed = int(item.get("exposedToolCount") or item.get("toolCount") or 0)
        tokens = estimate_exposed_tokens(item)
        total_exposed += exposed
        total_tokens += tokens
        max_exposed = max(max_exposed, exposed)
        print("%-24s %-8s %8d %8d %10d %12d" % (
            item.get("serverKey") or item.get("name") or "?",
            item.get("exposureMode") or "?",
            int(item.get("serverCount") or 0),
            int(item.get("toolCount") or 0),
            exposed,
            tokens,
        ))
    print("-" * 104)
    print("合计暴露工具: %d，近似 Token: %d" % (total_exposed, total_tokens))
    print("单端点最大暴露工具: %d" % max_exposed)

    print()
    print("建议：")
    if len(endpoints) <= 3 and total_exposed <= 100:
        print("  保持「按 Endpoint 独立挂载」（方法 B）：连接数与上下文都可接受。")
    elif total_exposed <= 150 and total_tokens <= 50_000:
        print("  可先保持方法 B，并优先把大 Endpoint 的成员 Server 切到 META。")
    else:
        print("  建议评估「单聚合端点 + 会话级分组过滤」（方法 C）：")
        print("  - 当前 %d 条连接、%d 个顶层工具、约 %d tokens；" % (
            len(endpoints), total_exposed, total_tokens))
        print("  - 单端点最大仅 %d 个工具，收敛后可按分组过滤。" % max_exposed)


if __name__ == "__main__":
    sys.exit(main())
