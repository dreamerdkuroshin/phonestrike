"""Strike API regression tests. Every fixed bug gets a test.
Run:  python -m pytest tests/   (from repo root)
Unit + API level; LLM/network paths are mocked or avoided — the one live
integration check (real backend) runs on-device via /api/health."""
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

import server
from strike import config, intents
from strike import llm as llm_mod
from strike.shell import run_shell_command

server.app.config["TESTING"] = True


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WORKSPACE_DIR", str(tmp_path))
    monkeypatch.setattr(config, "CONFIG_PATH", str(tmp_path / "config.json"))
    monkeypatch.setattr(config, "_config", None)
    config.replace({"provider": "custom", "base_url": "http://127.0.0.1:9/v1",
                    "model": "test-model", "api_key": "", "voice_enabled": False})
    with server.app.test_client() as c:
        yield c


def test_health_shape(client):
    r = client.get("/api/health")
    body = r.get_json()
    assert r.status_code == 200
    assert body["backend"] == "ok"
    assert body["llm"] == "down"  # nothing listens on :9
    assert body["asr"] == "n/a-device"  # never fake-ok
    assert "uploads" in body


def test_config_redacts_key(client):
    config.replace({"provider": "x", "api_key": "SECRET-KEY-123",
                    "openai": {"api_key": "NESTED-SECRET"}})
    body = client.get("/config").get_json()
    assert body["api_key"] == "***"
    assert body["openai"]["api_key"] == "***"
    assert "SECRET-KEY-123" not in json.dumps(body)
    assert "NESTED-SECRET" not in json.dumps(body)


def test_config_post_local_only(client):
    r = client.post("/config", json={"provider": "y"})
    assert r.status_code == 200
    r = client.post("/config", json={"provider": "z"},
                    environ_overrides={"REMOTE_ADDR": "192.168.1.5"})
    assert r.status_code == 403


def test_upload_uuid_and_traversal(client):
    data = {"file": (io.BytesIO(b"hello"), "../../evil.txt")}
    r = client.post("/api/upload", data=data, content_type="multipart/form-data")
    body = r.get_json()
    assert r.status_code == 200
    assert body["filename"] != "evil.txt"
    assert body["original"] == "../../evil.txt"
    assert "/" not in body["filename"] and "\\" not in body["filename"]
    assert body["filename"].endswith(".txt")


def test_build_chat_messages_history():
    msgs = [{"role": "user", "content": "What is the capital of India?"},
            {"role": "assistant", "content": "New Delhi."},
            {"role": "user", "content": "What about its weather?"}]
    last, out = llm_mod.build_chat_messages(msgs, "sys")
    assert last == "What about its weather?"
    assert out[0] == {"role": "system", "content": "sys"}
    assert any(m["content"] == "New Delhi." for m in out)  # context travels
    assert out[-1] == {"role": "user", "content": "What about its weather?"}


def test_intent_compound_guard():
    # "open X and ..." is NOT one app — must not misreport as unknown app
    assert intents.handle_mobile_command("open whatsapp and message beru") is None
    assert intents.handle_mobile_command("open whatsapp then message beru") is None
    assert intents.handle_mobile_command("open youtube") is not None


def test_voice_chain_compound(client):
    # opener runs (and honestly fails off-device); remainder goes to LLM path
    r = client.post("/api/voice/command", json={"text": "open whatsapp and message beru"})
    body = r.get_json()
    assert body["type"] == "chain"
    assert "whatsapp" in body["response"].lower()


def test_voice_chain_question_stays_single(client, monkeypatch):
    seen = {}
    monkeypatch.setattr(llm_mod, "query_llm",
                        lambda p, system_prompt=None, timeout=120: seen.setdefault("q", p) or "mock")
    r = client.post("/api/voice/command", json={"text": "cats and dogs"})
    body = r.get_json()
    assert body["type"] == "single"
    assert seen["q"] == "cats and dogs"


def test_shell_blocklist():
    assert "BLOCKED" in run_shell_command("rm -rf /")
    assert "BLOCKED" in run_shell_command("mkfs.ext4 /dev/sda")
    assert "BLOCKED" not in run_shell_command("cd ~/PocketStrike-AI && ls")


def test_search_empty_q(client):
    body = client.get("/api/search").get_json()
    assert body["error"] == "q required"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
