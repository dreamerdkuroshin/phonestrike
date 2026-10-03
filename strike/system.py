"""Status, health, history, crashlog, config views. No route code."""
import json
import os
import time

from . import config


def status_dict():
    c = config.get()
    return {
        "provider": c.get("provider_name", c.get("provider", "Custom")),
        "model": config.get_model(),
        "telegram_status": "Enabled" if c.get("telegram_enabled") else "Disabled",
        "telegram_enabled": bool(c.get("telegram_enabled")),
        "voice_enabled": bool(c.get("voice_enabled")),
        "os_type": "android",
        "os_name": "Android (Termux)",
        "base_url": config.get_base_url(),
    }


def health_dict():
    """Real component checks. Device-side pieces run in the Android app,
    not here — reported as n/a-device, never fake-ok."""
    import urllib.request as _u

    def http_ok(url, headers=None, timeout=4):
        try:
            req = _u.Request(url, headers=headers or {})
            with _u.urlopen(req, timeout=timeout) as r:
                return 200 <= r.status < 300
        except Exception:
            return False

    llm_headers = {}
    try:
        if config.get_api_key():
            llm_headers = {"Authorization": "Bearer " + config.get_api_key()}
    except Exception:
        pass
    llm_ok = http_ok(config.get_base_url().rstrip("/") + "/models", llm_headers)
    try:
        up = os.path.join(config.WORKSPACE_DIR, "uploads")
        os.makedirs(up, exist_ok=True)
        uploads_ok = os.access(up, os.W_OK)
    except Exception:
        uploads_ok = False
    return {
        "backend": "ok",
        "llm": "ok" if llm_ok else "down",
        "config": "ok" if (config.get_base_url() and config.get_model()) else "bad",
        "uploads": "ok" if uploads_ok else "unwritable",
        "asr": "n/a-device",
        "tts": "n/a-device",
        "mic": "n/a-device",
        "shizuku": "n/a-device",
        "memory": "ok",
    }


def load_conversations():
    try:
        if os.path.exists(config.history_file()):
            with open(config.history_file(), 'r') as f:
                d = json.load(f)
                return d if isinstance(d, list) else []
    except Exception:
        pass
    return []


def save_conversations(data):
    config.ensure_dirs()
    with open(config.history_file(), 'w') as f:
        json.dump(data, f, indent=2)


def purge_history(scope="conversations"):
    """Delete history. Scope 'conversations' (default) clears the server
    conversation store; 'all' additionally truncates crash.log (stacks can
    contain spoken text). Uploads are NEVER touched. Returns what was removed."""
    removed = []
    try:
        hf = config.history_file()
        if os.path.exists(hf):
            os.remove(hf)
            removed.append("conversations")
    except Exception:
        pass
    if scope == "all":
        try:
            cp = os.path.join(config.WORKSPACE_DIR, "agent", "crash.log")
            if os.path.exists(cp):
                open(cp, "w").close()
                removed.append("crash.log")
        except Exception:
            pass
    return removed


def append_crash(app, thread, stack):
    stack = str(stack or '')[:8000]
    if not stack.strip():
        return False, "empty"
    try:
        config.ensure_dirs()
        path = os.path.join(config.WORKSPACE_DIR, "agent", "crash.log")
        with open(path, "a", encoding="utf-8") as f:
            f.write("\n===== %s app=%s thread=%s =====\n%s\n" % (
                time.strftime("%Y-%m-%d %H:%M:%S"),
                str(app or "?")[:40],
                str(thread or "?")[:40],
                stack))
        return True, "logged"
    except Exception as e:
        return False, str(e)


def config_view():
    """Redacted config — never serve the raw key."""
    safe = json.loads(json.dumps(config.get()))
    if safe.get("api_key"):
        safe["api_key"] = "***"
    if isinstance(safe.get("openai"), dict) and safe["openai"].get("api_key"):
        safe["openai"]["api_key"] = "***"
    return safe


def config_replace_local(data, remote_addr):
    """Config writes only from the phone itself (setup.py runs locally)."""
    if remote_addr not in ("127.0.0.1", "::1"):
        return False, "config writes are local-only"
    config.replace(data or {})
    return True, "updated"
