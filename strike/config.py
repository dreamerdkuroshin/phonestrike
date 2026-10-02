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
        "openai": {
            "api_key": OMNIROUTE_KEY,
            "base_url": OMNIROUTE_URL,
            "model": "qoder/qoder"
        }
    }


def load():
    """Read config.json (or defaults). Called lazily on first use."""
    global _config
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, 'r') as f:
            _config = json.load(f)
    else:
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
    """Replace in-memory config AND persist (POST /config)."""
    global _config
    _config = data
    with open(CONFIG_PATH, 'w') as f:
        json.dump(_config, f, indent=2)
    return _config


def ensure_file():
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, 'w') as f:
            json.dump(get(), f, indent=2)


def set_model(name):
    """Switch model in both config formats. Returns (ok, message)."""
    try:
        with open(CONFIG_PATH, 'r') as f:
            cfg = json.load(f)
        if "model" in cfg or "provider" in cfg:
            cfg['model'] = name
        elif isinstance(cfg.get('openai'), dict):
            cfg['openai']['model'] = name
        else:
            cfg['model'] = name
        with open(CONFIG_PATH, 'w') as f:
            json.dump(cfg, f, indent=2)
        global _config
        _config = cfg
        return True, (f"✅ Switched to model: {name}\n"
                      "🔄 Restart server to apply (or it applies on next chat).")
    except Exception as e:
        return False, f"❌ Error switching model: {str(e)}"
