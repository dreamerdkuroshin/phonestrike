"""Web search, vision, uploads. No route code here — pure functions."""
import os
import re
import uuid

from . import config


def web_search(q):
    """DuckDuckGo HTML search. Returns (results, error)."""
    q = (q or "").strip()[:200]
    if not q:
        return [], "q required"
    try:
        import requests as _rq
        from html import unescape as _unes
        from urllib.parse import unquote as _unq, urlparse as _urlp, parse_qs as _pq
        r = _rq.post("https://html.duckduckgo.com/html/", data={"q": q},
                     headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"}, timeout=8)
        titles = re.findall(r'class="result__a"[^>]*>(.*?)</a>', r.text, re.S)
        hrefs = re.findall(r'class="result__a"[^>]*href="([^"]+)"', r.text)
        snips = re.findall(r'class="result__snippet"[^>]*>(.*?)</(?:a|div)>', r.text, re.S)
        out = []
        for i in range(min(5, len(titles))):
            t = re.sub(r'<[^>]+>', '', titles[i]).strip()
            s = re.sub(r'<[^>]+>', '', snips[i]).strip() if i < len(snips) else ""
            if t:
                # DDG hides the real link in ?uddg= redirect — extract for citations
                u = ""
                if i < len(hrefs):
                    href = hrefs[i]
                    if href.startswith("//"):
                        href = "https:" + href
                    if "uddg=" in href:
                        try:
                            u = _unq(_pq(_urlp(href).query).get("uddg", [""])[0])
                        except Exception:
                            u = ""
                    elif href.startswith("http"):
                        u = href
                out.append({"title": _unes(t), "snippet": _unes(s), "url": u})
        return out, None
    except Exception as e:
        return [], str(e)


def ask_vision(img, question):
    """Vision model Q&A. Returns (answer, error)."""
    img = str(img or '')[:6_000_000]
    q = str(question or 'Describe what you see.')[:400]
    if not img:
        return None, "no image provided"
    c = config.get()
    vision_model = c.get("vision_model")
    if not vision_model and isinstance(c.get("openai"), dict):
        vision_model = c["openai"].get("vision_model")
    if not vision_model:
        return None, ('no vision model — set "vision_model" in '
                      '~/PocketStrike-AI/config.json (e.g. llava)')
    import requests as _rq
    b64 = img.split(",", 1)[-1] if img.startswith("data:") else img
    payload = {
        "model": vision_model,
        "messages": [{"role": "user", "content": [
            {"type": "text", "text": q},
            {"type": "image_url", "image_url": {
                "url": "data:image/jpeg;base64," + b64}}
        ]}],
        "stream": False,
        "max_tokens": 300,
    }
    headers = {"Content-Type": "application/json"}
    if config.get_api_key():
        headers["Authorization"] = f"Bearer {config.get_api_key()}"
    r = _rq.post(config.get_base_url().rstrip("/") + "/chat/completions",
                 json=payload, headers=headers, timeout=60)
    if r.status_code != 200:
        return None, f"vision backend {r.status_code}: {r.text[:200]}"
    ans = r.json().get("choices", [{}])[0].get("message", {}).get("content", "")
    return ans, None


def save_upload(filestorage):
    """Store an upload under a server-generated uuid name.
    Returns (info_dict, error). Original name kept as metadata only."""
    try:
        if filestorage is None:
            return None, "No file"
        if getattr(filestorage, "content_length", None) and \
                filestorage.content_length > 10 * 1024 * 1024:
            return None, "File >10MB"
        import os.path as _osp
        ext = _osp.splitext(getattr(filestorage, "filename", "") or "")[1].lower()
        if not re.fullmatch(r"\.[a-z0-9]{1,7}", ext or ""):
            ext = ".bin"
        name = uuid.uuid4().hex + ext
        updir = os.path.join(config.WORKSPACE_DIR, "uploads")
        os.makedirs(updir, exist_ok=True)
        dest = os.path.join(updir, name)
        filestorage.save(dest)
        size_kb = round(os.path.getsize(dest) / 1024, 1)
        return {"filename": name,
                "original": (getattr(filestorage, "filename", "") or "")[:120],
                "size_kb": size_kb}, None
    except Exception as e:
        return None, str(e)
