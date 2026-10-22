#!/usr/bin/env python3
"""Sync MCP Gateway discovery into DSH Desktop's cordis.patch.yml.

The gateway endpoint is the existing `/mcp/discovery/servers`; this script only
turns the returned visible servers into `@deepseek-ai/dsh-mcp-client` entries.

Usage:
  python3 tools/dsh-mcp-sync.py --once
  python3 tools/dsh-mcp-sync.py --once --dry-run
  python3 tools/dsh-mcp-sync.py --interval 30

Config (~/.dsh/mcp-gateway/config.json):
  {
    "gatewayBaseUrl": "http://127.0.0.1:8081",
    "tokenFile": "~/.dsh/mcp-gateway/token",
    "userTokenFile": "~/.dsh/mcp-gateway/user-token",
    "profilePath": "~/.dsh/profiles/desktop/cordis.patch.yml",
    "intervalSeconds": 30
  }
"""
from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

BEGIN = "# >>> mcp-gateway managed block: do not edit manually >>>"
END = "# <<< mcp-gateway managed block <<<"
SERVER_KEY_RE = re.compile(r"^[A-Za-z0-9_-]{1,32}$")
NAMESPACE_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
DEFAULT_CONFIG = Path("~/.dsh/mcp-gateway/config.json").expanduser()
DEFAULT_TOKEN = Path("~/.dsh/mcp-gateway/token").expanduser()
DEFAULT_PROFILE = Path("~/.dsh/profiles/desktop/cordis.patch.yml").expanduser()
STATE_FILE = Path("~/.dsh/mcp-gateway/state.json").expanduser()
LOCK_FILE = Path("~/.dsh/mcp-gateway/sync.lock").expanduser()
DEFAULT_STATE_FILE = STATE_FILE
DEFAULT_LOCK_FILE = LOCK_FILE


def log(message: str) -> None:
    print(f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] {message}", flush=True)


def load_config(path: Path) -> dict:
    if not path.exists():
        raise SystemExit(f"配置文件不存在: {path}\n请先创建 {path}")
    with path.open(encoding="utf-8") as fh:
        cfg = json.load(fh)
    cfg.setdefault("gatewayBaseUrl", "http://127.0.0.1:8081")
    cfg.setdefault("tokenFile", str(DEFAULT_TOKEN))
    cfg.setdefault("userTokenFile", "")
    cfg.setdefault("profilePath", str(DEFAULT_PROFILE))
    cfg.setdefault("intervalSeconds", 30)
    # 多项目/多网关隔离：每个项目用不同 namespace / 前缀 / state 文件
    namespace = cfg.setdefault("namespace", "mcp-gateway")
    if not NAMESPACE_RE.match(str(namespace)):
        raise SystemExit(f"非法 namespace: {namespace!r}（只允许字母、数字、点、下划线、中划线）")
    cfg.setdefault("serverNamePrefix", "")
    cfg.setdefault("entryIdPrefix", "mcp-gw-")
    cfg.setdefault("mcpClientName", "@deepseek-ai/dsh-mcp-client")
    if "stateFile" not in cfg:
        cfg["stateFile"] = str(STATE_FILE) if namespace == "mcp-gateway" else f"~/.dsh/{namespace}/state.json"
    if "lockFile" not in cfg:
        cfg["lockFile"] = str(LOCK_FILE) if namespace == "mcp-gateway" else f"~/.dsh/{namespace}/sync.lock"
    cfg.setdefault("toolCallTimeoutMs", 60000)
    cfg.setdefault("failOnStartupError", False)
    return cfg


def expand(path: str) -> Path:
    return Path(os.path.expandvars(os.path.expanduser(path)))


def read_token(path: Path) -> str:
    if not path.exists():
        raise SystemExit(f"token 文件不存在: {path}")
    token = path.read_text(encoding="utf-8").strip()
    if not token:
        raise SystemExit(f"token 文件为空: {path}")
    return token


def fetch_servers(base_url: str, token: str, etag: str | None) -> tuple[int, str | None, dict | None]:
    url = base_url.rstrip("/") + "/mcp/discovery/servers"
    headers = {
        "Accept": "application/json",
        "x-dsh-auth-token": token,
    }
    if etag:
        headers["If-None-Match"] = etag
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, resp.headers.get("ETag"), json.loads(body) if body else None
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        if exc.code == 304:
            return 304, exc.headers.get("ETag"), None
        if exc.code in (401, 403):
            challenge = exc.headers.get("WWW-Authenticate", "")
            raise SystemExit(f"网关鉴权失败 HTTP {exc.code} {challenge}\n{body}")
        raise SystemExit(f"网关请求失败 HTTP {exc.code}\n{body}")
    except urllib.error.URLError as exc:
        raise SystemExit(f"无法连接网关 {url}: {exc.reason}")


def yaml_quote(value: str) -> str:
    """JSON 字符串也是合法 YAML 双引号标量。"""
    return json.dumps(value, ensure_ascii=False)


def block_markers(namespace: str) -> tuple[str, str]:
    """默认 namespace 兼容历史块；其他项目用独立标记，互不覆盖。"""
    ns = (namespace or "mcp-gateway").strip()
    if ns == "mcp-gateway":
        return BEGIN, END
    return (
        f"# >>> dsh-mcp-sync[{ns}] managed block: do not edit manually >>>",
        f"# <<< dsh-mcp-sync[{ns}] managed block <<<",
    )


def render_block(servers: list[dict], token_file: Path, revision: str, cfg: dict) -> str:
    begin, end = block_markers(str(cfg.get("namespace") or "mcp-gateway"))
    prefix = str(cfg.get("serverNamePrefix") or "")
    entry_prefix = str(cfg.get("entryIdPrefix") or "mcp-gw-")
    client_name = str(cfg.get("mcpClientName") or "@deepseek-ai/dsh-mcp-client")
    timeout = int(cfg.get("toolCallTimeoutMs") or 60000)
    fail_on_startup = bool(cfg.get("failOnStartupError", False))
    lines = [
        begin,
        f"# revision={revision}",
        "# 由 dsh-mcp-sync.py 根据 /mcp/discovery/servers 生成；请勿手工修改本块",
        "# 顶层是 patch，必须用 insert 新增插件条目",
    ]
    seen = set()
    entries: list[list[str]] = []
    for server in servers:
        key = str(server.get("serverKey") or "").strip()
        url = str(server.get("url") or "").strip()
        if not SERVER_KEY_RE.match(key):
            log(f"跳过非法 serverKey: {key!r}")
            continue
        server_name = prefix + key
        if not SERVER_KEY_RE.match(server_name):
            log(f"跳过非法 serverName（检查 serverNamePrefix）: {server_name!r}")
            continue
        if server_name in seen:
            log(f"跳过重复 serverName: {server_name}")
            continue
        if not (url.startswith("http://") or url.startswith("https://")):
            log(f"跳过非法 URL: serverName={server_name} url={url!r}")
            continue
        seen.add(server_name)
        token_literal = json.dumps(str(token_file), ensure_ascii=False)
        auth_expr = (
            "!!js '`${process.getBuiltinModule(\"node:fs\").readFileSync("
            + token_literal
            + ", \"utf8\").trim()}`'"
        )
        headers = [f"          x-dsh-auth-token: {auth_expr}"]
        entries.append([
            f"    - id: {yaml_quote(entry_prefix + server_name)}",
            f"      name: {yaml_quote(client_name)}",
            "      config:",
            f"        serverName: {yaml_quote(server_name)}",
            "        transport: streamable-http",
            f"        url: {yaml_quote(url)}",
            "        headers:",
            *headers,
            f"        failOnStartupError: {'true' if fail_on_startup else 'false'}",
            f"        toolCallTimeoutMs: {timeout}",
        ])
    if entries:
        lines.append("- insert:")
        for entry in entries:
            lines.extend(entry)
    else:
        lines.append("- insert: []")
    lines.append(end)
    return "\n".join(lines) + "\n"
def replace_managed_block(existing: str, block: str, begin: str = BEGIN, end_marker: str = END) -> str:
    start = existing.find(begin)
    end = existing.find(end_marker)
    if start == -1 and end == -1:
        if existing and not existing.endswith("\n"):
            existing += "\n"
        return existing + ("\n" if existing else "") + block
    if start == -1 or end == -1 or end < start:
        raise SystemExit("cordis.patch.yml 中 managed block 标记不完整，拒绝写入")
    end += len(end_marker)
    while end < len(existing) and existing[end] in "\r\n":
        end += 1
    return existing[:start] + block + ("\n" if end < len(existing) else "") + existing[end:]


def atomic_write(path: Path, content: str) -> None:
    """原地写入，保留 inode，确保 DSH 的文件 watcher 能收到变更。

    配置文件很小，配合 sync.lock 使用；崩溃时最多留下半写文件，可从备份恢复。
    """
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8") as fh:
            fh.write(content)
        os.chmod(path, 0o600)
        return
    mode = path.stat().st_mode & 0o777
    with path.open("w", encoding="utf-8") as fh:
        fh.write(content)
        fh.flush()
        os.fsync(fh.fileno())
    os.chmod(path, mode)


def save_state(state_file: Path, etag: str | None, revision: str) -> None:
    state_file.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "etag": etag,
        "revision": revision,
        "lastSync": datetime.now().isoformat(timespec="seconds"),
    }
    tmp = state_file.with_name(state_file.name + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, state_file)


def load_state_etag(state_file: Path) -> str | None:
    try:
        return json.loads(state_file.read_text(encoding="utf-8")).get("etag")
    except Exception:
        return None


def sync_once(cfg: dict, dry_run: bool = False, force: bool = False) -> None:
    base_url = str(cfg["gatewayBaseUrl"])
    user_token_file = str(cfg.get("userTokenFile") or "").strip()
    token_path = expand(user_token_file or str(cfg["tokenFile"]))
    profile_path = expand(str(cfg["profilePath"]))
    token = read_token(token_path)
    state_file = expand(str(cfg.get("stateFile") or DEFAULT_STATE_FILE))
    lock_file = expand(str(cfg.get("lockFile") or DEFAULT_LOCK_FILE))
    etag = None if force else load_state_etag(state_file)

    status, new_etag, body = fetch_servers(base_url, token, etag)
    if status == 304:
        log("gateway 304，配置未变化")
        return
    if body is None or body.get("code") != 0:
        raise SystemExit(f"网关返回异常: {body}")
    servers = body.get("data") or []
    revision = hashlib.sha256(
        json.dumps(servers, sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).hexdigest()
    # 优先使用网关 ETag/响应头中的 revision（若有）
    if new_etag:
        revision = new_etag.strip('"')

    begin, end_marker = block_markers(str(cfg.get("namespace") or "mcp-gateway"))
    block = render_block(servers, token_path, revision, cfg)
    existing = profile_path.read_text(encoding="utf-8") if profile_path.exists() else ""
    updated = replace_managed_block(existing, block, begin, end_marker)
    if dry_run:
        print(block)
        log(f"dry-run: {len(servers)} 个 server，目标 {profile_path}")
        return
    if updated == existing and not force:
        save_state(state_file, new_etag, revision)
        log("DSH 配置未变化")
        return
    lock_file.parent.mkdir(parents=True, exist_ok=True)
    with lock_file.open("a+") as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            atomic_write(profile_path, updated)
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)
    save_state(state_file, new_etag, revision)
    log(f"已同步 {len(servers)} 个 server → {profile_path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default=str(DEFAULT_CONFIG))
    parser.add_argument("--once", action="store_true", help="只同步一次（默认）")
    parser.add_argument("--interval", type=int, help="持续运行，按秒轮询")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--force", action="store_true", help="即使内容相同也重写，用于测试 DSH 热加载")
    args = parser.parse_args()

    cfg = load_config(expand(args.config))

    if args.interval:
        while True:
            try:
                sync_once(cfg, args.dry_run, args.force)
            except SystemExit as exc:
                log(f"同步失败: {exc}")
            except Exception as exc:  # noqa: BLE001
                log(f"同步异常: {exc}")
            time.sleep(max(5, args.interval))
    else:
        sync_once(cfg, args.dry_run, args.force)


if __name__ == "__main__":
    main()
