"""LLM client. The ONLY module that talks to the model backend."""
from . import config


def query_llm(user_text, system_prompt=None, timeout=120, max_tokens=None):
    """Send single prompt to configured LLM backend. Returns text."""
    import requests
    base_url = config.get_base_url()
    model = config.get_model()
    api_key = config.get_api_key()
    msgs = []
    if system_prompt:
        msgs.append({"role": "system", "content": system_prompt})
    msgs.append({"role": "user", "content": user_text})
    payload = {"model": model, "messages": msgs, "stream": False, "temperature": 0.7}
    if max_tokens:
        payload["max_tokens"] = max_tokens
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    url = base_url.rstrip("/") + "/chat/completions"
    resp = requests.post(url, json=payload, headers=headers, timeout=timeout)
    if resp.status_code == 200:
        return resp.json().get('choices', [{}])[0].get('message', {}).get('content', "No response")
    raise RuntimeError(f"AI Error {resp.status_code}: {resp.text[:300]}")


def build_chat_messages(messages, sys_prompt=None):
    """Split a web-UI history into (last_user, model_messages).

    Follow-up context fix: up to 3 recent exchanges travel with the latest
    question so "what about tomorrow?" still knows the topic.
    """
    last_user = ""
    for m in reversed(messages or []):
        if isinstance(m, dict) and m.get('role') == 'user':
            last_user = str(m.get('content', ''))[:2000]
            break
    hist = []
    for m in (messages or []):
        if not isinstance(m, dict) or m.get("role") not in ("user", "assistant"):
            continue
        c = str(m.get("content", "")).strip()
        if c and c != last_user:
            hist.append({"role": m["role"], "content": c[:300]})
    hist = hist[-6:]
    msgs = ([{"role": "system", "content": sys_prompt}] if sys_prompt else []) + \
        hist + [{"role": "user", "content": last_user}]
    return last_user, msgs


def iter_tokens(messages, timeout=120):
    """Yield content tokens from a streaming chat completion.

    Raises RuntimeError on backend errors. Closes the upstream connection
    when the caller stops iterating (client disconnect).
    """
    import requests
    base_url = config.get_base_url()
    model = config.get_model()
    api_key = config.get_api_key()
    payload = {"model": model, "messages": messages, "stream": True}
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    r = requests.post(base_url.rstrip("/") + "/chat/completions",
                      json=payload, headers=headers, timeout=timeout, stream=True)
    try:
        if r.status_code != 200:
            raise RuntimeError(f"AI Error {r.status_code}: {r.text[:200]}")
        for line in r.iter_lines():
            if not line:
                continue
            s = line.decode('utf-8', 'replace').strip()
            if not s.startswith("data:"):
                continue
            body = s[5:].strip()
            if body == "[DONE]":
                break
            try:
                import json as _js
                tok = _js.loads(body)["choices"][0].get("delta", {}).get("content", "")
            except Exception:
                tok = ""
            if tok:
                yield tok
    finally:
        try:
            r.close()
        except Exception:
            pass
