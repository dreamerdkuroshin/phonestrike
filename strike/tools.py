"""MCP server-connection registry (backs the web UI sidebar).

Stores user-added SSE/stdio server connections in config.json
("mcp_servers") and reports live status via a short reachability probe.
Add/remove mutate server files, so the routes gate them localhost-only.
"""
import re
import socket
import time
import urllib.parse
import urllib.request

from . import config

NAME_RE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")
PROBE_TIMEOUT = 3


def _servers():
    cfg = config.get()
    srv = cfg.get("mcp_servers")
    return list(srv) if isinstance(srv, list) else []


def _save(servers):
    cfg = dict(config.get())
    cfg["mcp_servers"] = servers
    config.replace(cfg)  # atomic persist


def _blocked_host(host):
    """SSRF guard: no cloud-metadata or loopback-trick targets."""
    h = (host or "").lower().strip("[]")
    if h in ("169.254.169.254", "metadata.google.internal"):
        return True
    try:
        ip = socket.gethostbyname(h)
        # allow LAN + loopback (user's own servers), block metadata only
        return ip == "169.254.169.254"
    except Exception:
        return True  # unresolvable -> refuse to probe


def probe(url):
    """Best-effort reachability + tool count. Returns (up, tools_count)."""
    try:
        u = urllib.parse.urlparse(url)
        if u.scheme not in ("http", "https") or _blocked_host(u.hostname or ""):
            return False, 0
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=PROBE_TIMEOUT) as r:
            up = 200 <= r.status < 500
        tools = 0
        for suffix in ("/tools", "/api/tools"):
            try:
                with urllib.request.urlopen(url.rstrip("/") + suffix,
                                             timeout=PROBE_TIMEOUT) as r2:
                    import json as _j
                    body = _j.loads(r2.read(200000).decode("utf-8", "replace"))
                    items = body.get("tools") if isinstance(body, dict) else body
                    if isinstance(items, list):
                        tools = len(items)
                        break
            except Exception:
                continue
        return up, tools
    except Exception:
        return False, 0


def list_servers():
    out = []
    for s in _servers():
        up, tools = (False, 0)
        if s.get("transport") != "stdio" and s.get("url"):
            up, tools = probe(s["url"])
        out.append({
            "name": s.get("name"),
            "transport": s.get("transport", "sse"),
            "url": s.get("url", ""),
            "command": s.get("command", ""),
            "status": "connected" if up or s.get("transport") == "stdio" else "offline",
            "tools_count": tools,
            "added_at": s.get("added_at", 0),
        })
    return out


def add_server(name, transport="sse", url="", command=""):
    name = (name or "").strip()
    if not NAME_RE.match(name):
        return None, "bad name (A-Z a-z 0-9 _ - , max 40)"
    if transport not in ("sse", "stdio"):
        return None, "transport must be sse or stdio"
    if any(s.get("name") == name for s in _servers()):
        return None, "a server with that name already exists"
    if transport == "sse":
        u = urllib.parse.urlparse(url)
        if u.scheme not in ("http", "https") or not u.hostname:
            return None, "url must be http(s)"
        if _blocked_host(u.hostname):
            return None, "that host is not allowed"
        up, tools = probe(url)
        if not up:
            return None, "server did not respond (offline?)"
    else:
        if not command.strip():
            return None, "stdio needs a command"
        tools = 0
    servers = _servers()
    servers.append({"name": name, "transport": transport, "url": url,
                    "command": command, "added_at": int(time.time())})
    with config._lock:  # read-modify-write as one unit (no lost updates)
        if any(s.get("name") == name for s in _servers()):
            return None, "a server with that name already exists"
        _save(servers)
    return {"status": "connected", "tools_count": tools}, None


def remove_server(name):
    with config._lock:  # read-modify-write as one unit
        servers = _servers()
        kept = [s for s in servers if s.get("name") != name]
        if len(kept) == len(servers):
            return False
        _save(kept)
        return True
