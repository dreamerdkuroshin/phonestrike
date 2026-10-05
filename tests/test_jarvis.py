"""Jarvis router regression tests. Shell commands fail safely off-device
(missing termux bash -> caught error strings), so branch routing is fully
deterministic here; live execution is covered by on-device smoke."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

from strike import config
from strike import jarvis


@pytest.fixture(autouse=True)
def tmpconfig(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "WORKSPACE_DIR", str(tmp_path))
    monkeypatch.setattr(config, "CONFIG_PATH", str(tmp_path / "config.json"))
    monkeypatch.setattr(config, "_config", None)
    config.replace({"provider": "custom", "base_url": "http://127.0.0.1:9/v1",
                    "model": "m", "api_key": "", "voice_enabled": False})


def test_help():
    assert "JARVIS COMMANDER" in jarvis.jarvis_commander("help")
    assert "JARVIS COMMANDER" in jarvis.jarvis_commander("?")


def test_status_shape():
    out = jarvis.jarvis_commander("status")
    assert "JARVIS SYSTEM STATUS" in out
    assert "Current Model" in out


def test_stop_restart_fixed_messages():
    assert "stopped" in jarvis.jarvis_commander("stop pocketstrike")
    # restart verifies /api/status: on a live phone "restarted and answering",
    # in CI (no server) the honest not-answering message — both beat "Restarting..."
    out = jarvis.jarvis_commander("restart strike")
    assert "restarted and answering" in out or "not answering" in out


def test_current_model():
    out = jarvis.jarvis_commander("current model")
    assert "Current Model: m" in out
    assert "127.0.0.1:9" in out


def test_switch_model(tmp_path):
    out = jarvis.jarvis_commander("switch to strike-coder")
    assert "Switched to model: strike-coder" in out
    assert config.get_model() == "strike-coder"


def test_switch_model_empty():
    # bare verb never reaches the handler (strip); unit-test it directly
    assert "Usage" in jarvis._switch_model("")
    assert jarvis.jarvis_commander("switch to").startswith("❓")


def test_list_models_shape():
    out = jarvis.jarvis_commander("list models")
    assert "Available Models" in out or "No models found" in out


def test_launch_known_and_unknown():
    # verified launch ("process confirmed") on-device; honest no-process
    # message in CI — both name the project
    out = jarvis.jarvis_commander("launch rehan")
    assert "Rehan" in out
    assert "Unknown project" in jarvis.jarvis_commander("launch nosuchthing")


def test_omni_status():
    assert "Backend http://127.0.0.1:9/v1: HTTP" in jarvis.jarvis_commander("omniroute status")


def test_draw_local_refusal():
    out = jarvis.jarvis_commander("draw a cat")
    assert "needs a cloud provider" in out


def test_draw_empty():
    assert "specify" in jarvis._draw_image("")


def test_mobile_passthrough():
    out = jarvis.jarvis_commander("battery")
    assert out.startswith("🔋")


def test_unknown():
    out = jarvis.jarvis_commander("frobnicate the wobbler")
    assert out.startswith("❓ Unknown command")


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
