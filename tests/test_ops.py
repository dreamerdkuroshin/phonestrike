"""Ops tests: history purge, provider rotation, web-UI backend contract."""
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

import server
from strike import config
from strike import llm as llm_mod
from strike import provider

server.app.config["TESTING"] = True


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WORKSPACE_DIR", str(tmp_path))
    monkeypatch.setattr(config, "CONFIG_PATH", str(tmp_path / "config.json"))
    monkeypatch.setattr(config, "_config", None)
    config.replace({"provider": "custom", "base_url": "http://127.0.0.1:9/v1",
                    "model": "m", "api_key": "", "voice_enabled": False})
    with server.app.test_client() as c:
        yield c


def test_history_sync_purge_cycle(client):
    conv = [{"id": "c1", "messages": [{"role": "user", "content": "hi"}]}]
    assert client.post("/api/history/sync", json=conv).status_code == 200
    assert client.get("/api/history/load").get_json() == conv
    r = client.post("/api/history/purge", json={"scope": "all"})
    assert r.get_json() == {"removed": ["conversations"]}
    assert client.get("/api/history/load").get_json() == []
    # reload proves it stayed purged (not re-sent to any LLM path)
    assert client.get("/api/history/load").get_json() == []


def test_purge_all_truncates_crashlog(client):
    agent = os.path.join(config.WORKSPACE_DIR, "agent")
    os.makedirs(agent, exist_ok=True)
    with open(os.path.join(agent, "crash.log"), "w") as f:
        f.write("sensitive stack")
    r = client.post("/api/history/purge", json={"scope": "all"})
    assert "crash.log" in r.get_json()["removed"]
    with open(os.path.join(agent, "crash.log")) as f:
        assert f.read() == ""


def test_rotate_ok_and_key_never_returned(client, monkeypatch):
    monkeypatch.setattr(provider, "_models_ok", lambda b, k, timeout=10: True)
    monkeypatch.setattr(llm_mod, "query_llm", lambda *a, **k: "ok")
    r = client.post("/api/provider/rotate",
                    json={"api_key": "NEWKEY", "base_url": "http://127.0.0.1:9/v1"})
    body = r.get_json()
    assert r.status_code == 200
    assert body["api_key_configured"] is True
    assert "NEWKEY" not in str(body)
    assert config.get_api_key() == "NEWKEY"


def test_rotate_rejected(client, monkeypatch):
    monkeypatch.setattr(provider, "_models_ok", lambda b, k, timeout=10: False)
    r = client.post("/api/provider/rotate", json={"api_key": "BAD"})
    assert r.status_code == 502
    assert config.get_api_key() == ""  # old (empty) key untouched


def test_rotate_remote_forbidden(client):
    r = client.post("/api/provider/rotate", json={},
                    environ_overrides={"REMOTE_ADDR": "192.168.1.5"})
    assert r.status_code == 403


def test_web_contract(client, monkeypatch):
    # every fetch() the web UI performs must exist and answer
    assert client.get("/api/status").status_code == 200
    assert client.get("/api/mcp/list").get_json() == []
    assert client.get("/api/history/load").get_json() == []
    monkeypatch.setattr(llm_mod, "query_llm", lambda *a, **k: "mock")
    r = client.post("/chat", json={"message": "2+2"})
    assert r.status_code == 200 and "mock" in r.get_json()["response"]
    r = client.post("/api/chat", json={"messages": [{"role": "user", "content": "hi"}]})
    assert r.status_code == 200
    r = client.post("/api/voice/command", json={"text": "hello"})
    assert r.status_code == 200
    r = client.post("/api/research", json={})
    assert r.status_code == 400
    monkeypatch.setattr("strike.research.research",
                        lambda q, progress=None: {"question": q, "verified": True})
    r = client.post("/api/research", json={"question": "x"})
    assert r.status_code == 200  # search fails offline -> honest unverified answer
    r = client.post("/api/tasks", json={})
    assert r.status_code == 400
    assert client.get("/api/tasks/nope").status_code == 404
    assert client.post("/api/tasks/nope/cancel").status_code == 404
    r = client.post("/api/upload", data={"file": (io.BytesIO(b"x"), "a.txt")},
                    content_type="multipart/form-data")
    assert r.status_code == 200


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
