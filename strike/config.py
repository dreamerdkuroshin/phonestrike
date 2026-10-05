"""Configuration. Single owner of the config dict, both flat (setup.py)
and nested (legacy openai) formats. Paths are module attributes so tests
can point them at a tmp dir."""
import json
import os

CONFIG_PATH = os.path.expanduser("~/PocketStrike-AI/config.json")
# ponytail: key was hardcoded in source (leaked into git history — rotate it);
# now env-only. Local llama needs no key at all.
OMNIROUTE_KEY = os.environ.get("OMNIROUTE_KEY", "")
OMNIROUTE_URL = "http://localhost:20128/v1"

WORKSPACE_DIR = os.path.expanduser("~/PocketStrike-AI")

_config = None


def _default():
    return {
        "ai_provider": "openai",
        # A-06: offline-first by default. When true, query_llm refuses any
        # non-loopback backend instead of silently going online.
        "offline_required": True,
        "openai": {
            "api_key": OMNIROUTE_KEY,
            "base_url": OMNIROUTE_URL,
            "model": "qoder/qoder"
        }
    }


def load():
    """Read config.json (or defaults). A corrupt file must never kill the
    server at boot — fall back to defaults and keep running."""
    global _config
    try:
        if os.path.exists(CONFIG_PATH):
            with open(CONFIG_PATH) as f:
                _config = json.load(f)
                return _config
    except Exception:
        pass
    _config = _default()
    return _config


def get():
    return _config if _config is not None else load()


def ensure_dirs():
    os.makedirs(os.path.join(WORKSPACE_DIR, "agent"), exist_ok=True)
    os.makedirs(os.path.join(WORKSPACE_DIR, "uploads"), exist_ok=True)


def history_file():
    return os.path.join(WORKSPACE_DIR, "agent", "conversations.json")


def get_base_url():
    c = get()
    if c.get("base_url"):
        return str(c["base_url"]).rstrip("/")
    if isinstance(c.get("openai"), dict) and c["openai"].get("base_url"):
        return str(c["openai"]["base_url"]).rstrip("/")
    return OMNIROUTE_URL


def get_api_key():
    c = get()
    if c.get("api_key"):
        return c["api_key"]
    if isinstance(c.get("openai"), dict) and c["openai"].get("api_key"):
        return c["openai"]["api_key"]
    return OMNIROUTE_KEY


def get_model():
    c = get()
    if c.get("model"):
        return c["model"]
    if isinstance(c.get("openai"), dict) and c["openai"].get("model"):
        return c["openai"]["model"]
    return "qoder/qoder"


def replace(data):
    """Replace in-memory config AND persist atomically (temp+rename so a
    crash mid-write can never corrupt config.json)."""
    import tempfile
    global _config
    _config = data
    d = os.path.dirname(CONFIG_PATH) or "."
    fd, tmp = tempfile.mkstemp(dir=d, prefix=".tmp-config-", suffix=".json")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(_config, f, indent=2)
        os.replace(tmp, CONFIG_PATH)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return _config


def ensure_file():
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, 'w') as f:
            json.dump(get(), f, indent=2)


def set_model(name):
    """Switch model in both config formats. Returns (ok, message)."""
    try:
        with open(CONFIG_PATH) as f:
            cfg = json.load(f)
        if "model" in cfg or "provider" in cfg:
            cfg['model'] = name
        elif isinstance(cfg.get('openai'), dict):
            cfg['openai']['model'] = name
        else:
            cfg['model'] = name
        replace(cfg)  # atomic persist + in-memory update together
        return True, (f"✅ Switched to model: {name}\n"
                      "🔄 Restart server to apply (or it applies on next chat).")
    except Exception as e:
        return False, f"❌ Error switching model: {str(e)}"
