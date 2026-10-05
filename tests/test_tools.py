"""MCP registry tests: validation, SSRF guard, add/list/remove round-trip."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import server
from strike import config
from strike import tools as tools_mod

server.app.config["TESTING"] = True


def _iso(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WORKSPACE_DIR", str(tmp_path))
    monkeypatch.setattr(config, "CONFIG_PATH", str(tmp_path / "config.json"))
    monkeypatch.setattr(config, "_config", None)
    # fresh config written atomically by replace()
    config.replace(config._default())
    return server.app.test_client()


def test_add_rejects_bad_name(tmp_path, monkeypatch):
    c = _iso(tmp_path, monkeypatch)
    r = c.post("/api/mcp/add", json={"name": "a/b;rm", "url": "http://x/"})
    assert r.status_code == 400


def test_add_rejects_metadata_host(tmp_path, monkeypatch):
    c = _iso(tmp_path, monkeypatch)
    r = c.post("/api/mcp/add", json={"name": "evil", "url": "http://169.254.169.254/"})
    assert r.status_code == 400


def test_add_rejects_unreachable(tmp_path, monkeypatch):
    c = _iso(tmp_path, monkeypatch)
    r = c.post("/api/mcp/add", json={"name": "dead", "url": "http://127.0.0.1:9/"})
    assert r.status_code == 400


def test_add_duplicate_rejected(tmp_path, monkeypatch):
    _iso(tmp_path, monkeypatch)
    assert tools_mod.add_server("dup", "stdio", "", "echo hi") == (
        {"status": "connected", "tools_count": 0}, None)
    res, err = tools_mod.add_server("dup", "stdio", "", "echo hi")
    assert res is None and "already exists" in err


def test_stdio_roundtrip(tmp_path, monkeypatch):
    c = _iso(tmp_path, monkeypatch)
    r = c.post("/api/mcp/add", json={"name": "local", "transport": "stdio",
                                     "command": "echo hi"})
    assert r.status_code == 200
    assert c.get("/api/mcp/list").get_json()[0]["status"] == "connected"
    assert c.post("/api/mcp/remove", json={"name": "local"}).status_code == 200
    assert c.get("/api/mcp/list").get_json() == []
    assert c.post("/api/mcp/remove", json={"name": "local"}).status_code == 404


def test_history_sync_local_only(tmp_path, monkeypatch):
    _iso(tmp_path, monkeypatch)
    c = server.app.test_client()
    # test client defaults to 127.0.0.1 -> allowed; spoof LAN -> 403
    r = c.post("/api/history/sync", json=[],
               environ_overrides={"REMOTE_ADDR": "192.168.1.5"})
    assert r.status_code == 403
    r2 = c.post("/api/history/purge", json={"scope": "conversations"},
                environ_overrides={"REMOTE_ADDR": "192.168.1.5"})
    assert r2.status_code == 403


def test_csp_header_present(tmp_path, monkeypatch):
    c = _iso(tmp_path, monkeypatch)
    r = c.get("/api/status")
    assert "default-src 'self'" in r.headers.get("Content-Security-Policy", "")
    assert r.headers.get("X-Content-Type-Options") == "nosniff"
