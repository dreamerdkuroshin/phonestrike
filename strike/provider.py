"""Provider credentials: validate -> persist -> test. Never logs or
returns the key itself — only whether one is configured."""
from . import config
from . import llm as _llm


def _models_ok(base_url, api_key, timeout=10):
    import requests as _rq
    headers = {}
    if api_key:
        headers["Authorization"] = "Bearer " + api_key
    r = _rq.get(base_url.rstrip("/") + "/models", headers=headers, timeout=timeout)
    return r.status_code == 200


def rotate(api_key=None, base_url=None, model=None):
    """Rotate provider credentials. Returns (result_dict, error)."""
    cur = dict(config.get())
    new_base = (base_url or "").strip() or config.get_base_url()
    new_model = (model or "").strip() or config.get_model()
    # empty-string key means "keep existing"; a provided value replaces it
    new_key = cur.get("api_key", "") if api_key is None else api_key
    try:
        if not _models_ok(new_base, new_key):
            return None, f"backend rejected the credential (GET {new_base}/models failed)"
    except Exception as e:
        return None, f"backend unreachable: {e}"
    cur["base_url"] = new_base
    cur["model"] = new_model
    if api_key is not None:
        cur["api_key"] = api_key
    config.replace(cur)
    try:
        probe = _llm.query_llm("Reply with exactly: ok", timeout=60)
    except Exception as e:
        return None, f"saved, but test query failed: {e}"
    return {"base_url": new_base, "model": new_model,
            "api_key_configured": bool(config.get_api_key()),
            "test": (probe or "")[:120]}, None
