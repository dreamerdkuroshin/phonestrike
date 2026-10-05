"""Audit round-2 tests: offline gate, purge scopes, export, tasks journal,
jarvis verification helpers, shell checked-runner."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import server
from strike import config
from strike import executor as exec_mod
from strike import llm as llm_mod
from strike.shell import run_shell_checked

server.app.config["TESTING"] = True


def _iso(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WORKSPACE_DIR", str(tmp_path))
    monkeypatch.setattr(config, "CONFIG_PATH", str(tmp_path / "config.json"))
    monkeypatch.setattr(config, "_config", None)
    config.replace(config._default())
    return server.app.test_client()


def test_offline_blocks_cloud(tmp_path, monkeypatch):
    _iso(tmp_path, monkeypatch)
    cfg = dict(config.get())
    cfg["openai"] = dict(cfg.get("openai", {}))
    cfg["openai"]["base_url"] = "https://cloud.example.com/v1"
    config.replace(cfg)
    out = llm_mod.query_llm("hi")
    assert "OFFLINE_REQUIRED" in out


def test_offline_allows_loopback(tmp_path, monkeypatch):
    _iso(tmp_path, monkeypatch)
    assert llm_mod._offline_block() is None


def test_offline_opt_out(tmp_path, monkeypatch):
    _iso(tmp_path, monkeypatch)
    cfg = dict(config.get())
    cfg["openai"] = dict(cfg.get("openai", {}))
    cfg["openai"]["base_url"] = "https://cloud.example.com/v1"
    cfg["offline_required"] = False
    config.replace(cfg)
    assert llm_mod._offline_block() is None


def test_purge_scopes(tmp_path, monkeypatch):
    c = _iso(tmp_path, monkeypatch)
    # seed stores
    hf = config.history_file()
    os.makedirs(os.path.dirname(hf), exist_ok=True)
    open(hf, "w").write("{}")
    tp = os.path.join(str(tmp_path), "tasks", "tasks.jsonl")
    os.makedirs(os.path.dirname(tp), exist_ok=True)
    open(tp, "w").write("{}\n")
    assert set(c.post("/api/history/purge",
                       json={"scope": "tasks"}).get_json()["removed"]) == {"tasks.jsonl"}
    assert os.path.exists(hf)  # conversations untouched
    assert set(c.post("/api/history/purge",
                       json={"scope": "all"}).get_json()["removed"]) >= {"conversations"}


def test_export_zip(tmp_path, monkeypatch):
    c = _iso(tmp_path, monkeypatch)
    r = c.get("/api/export")
    assert r.status_code == 200
    assert r.headers["Content-Type"] == "application/zip"
    assert r.data[:4] == b"PK\x03\x04"
    import io
    import zipfile
    names = zipfile.ZipFile(io.BytesIO(r.data)).namelist()
    assert "config.redacted.json" in names


def test_export_local_only(tmp_path, monkeypatch):
    c = _iso(tmp_path, monkeypatch)
    r = c.get("/api/export", environ_overrides={"REMOTE_ADDR": "192.168.1.5"})
    assert r.status_code == 403


def test_tasks_list(tmp_path, monkeypatch):
    c = _iso(tmp_path, monkeypatch)
    assert c.get("/api/tasks").get_json() == []


def test_journal_survives(tmp_path, monkeypatch):
    _iso(tmp_path, monkeypatch)
    t = {"id": "abc123", "state": "CREATED", "log": ["hi"],
         "updated_at": 1.0, "cancel": None, "proc": None, "fix_fn": None}
    exec_mod._journal(t)
    found = [s for s in exec_mod.list_tasks() if s["id"] == "abc123"]
    assert found and found[0]["archived"] is True


def test_shell_checked_rc(tmp_path, monkeypatch):
    _iso(tmp_path, monkeypatch)
    # BLOCKED path is platform-independent; echo needs the Termux bash
    rc, out = run_shell_checked("rm -rf / --no-preserve-root", timeout=5)
    assert rc == -1 and "BLOCKED" in out
    import os as _os
    if _os.path.exists("/data/data/com.termux/files/usr/bin/bash"):
        rc, _ = run_shell_checked("echo hi")
        assert rc == 0
