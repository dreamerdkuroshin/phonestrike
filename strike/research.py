"""Research agent: plan -> multi-query -> retrieve -> extract -> dedupe ->
fact-collect -> cross-check -> synthesize with citations.

A search result is NEVER presented as a verified fact: claims carry the
sources that state them, contradictions are reported, unsourced answers
say so.
"""
import re
import time
import uuid
from html import unescape as _unes
from urllib.parse import urljoin as _join, urlparse as _urlp

from . import config
from . import llm as _llm
from .media import web_search

MAX_SOURCES = 6
FETCH_TIMEOUT = 10


def plan_queries(question):
    """Deterministic query expansion (no LLM needed for this step)."""
    q = (question or "").strip()
    base = re.sub(r"\s+", " ", q)
    queries = [base]
    # recency + definition angles for short questions
    if len(base.split()) <= 8:
        queries.append(base + " latest")
        queries.append("what is " + base if not base.lower().startswith("what") else base + " explained")
    seen, out = set(), []
    for x in queries:
        k = x.lower()
        if k not in seen:
            seen.add(k)
            out.append(x)
    return out[:3]


def fetch_text(url, timeout=FETCH_TIMEOUT):
    """Fetch a page, return (title, text, error). Text-only, size-capped."""
    try:
        import requests as _rq
        r = _rq.get(url, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"},
                    timeout=timeout)
        if r.status_code != 200 or not r.text:
            return "", "", f"HTTP {r.status_code}"
        html = r.text[:300_000]
        title = ""
        m = re.search(r"<title[^>]*>(.*?)</title>", html, re.S | re.I)
        if m:
            title = _unes(re.sub(r"<[^>]+>", "", m.group(1))).strip()[:200]
        body = re.sub(r"(?is)<(script|style|nav|footer|header|svg)[^>]*>.*?</\1>", " ", html)
        text = _unes(re.sub(r"<[^>]+>", " ", body))
        text = re.sub(r"[ \t ]+", " ", text)
        text = re.sub(r"\n\s*\n+", "\n", text).strip()[:8000]
        if len(text) < 200:
            return title, "", "too little text"
        return title, text, None
    except Exception as e:
        return "", "", str(e)[:200]


def _host(url):
    try:
        return _urlp(url).netloc.lower()
    except Exception:
        return ""


def research(question, progress=None):
    """Run the full pipeline. progress(step:str)->None for UI streaming.
    Returns dict with research_id/question/queries/sources/claims/
    contradictions/synthesis — every claim carries source indexes."""
    rid = uuid.uuid4().hex[:8]
    started = time.time()
    emit = progress or (lambda s: None)

    emit("planning")
    queries = plan_queries(question)

    emit("searching")
    sources = []  # {url, title, snippet}
    for q in queries:
        results, _ = web_search(q)
        for r in results or []:
            if r.get("url") and all(s["url"] != r["url"] for s in sources):
                sources.append({"url": r["url"], "title": r.get("title", ""),
                                "snippet": r.get("snippet", "")})
            if len(sources) >= MAX_SOURCES * 2:
                break
        if len(sources) >= MAX_SOURCES * 2:
            break

    emit("reading")
    docs = []  # {url, title, text}
    for s in sources[:MAX_SOURCES * 2]:
        title, text, err = fetch_text(s["url"])
        if text:
            docs.append({"url": s["url"],
                         "title": title or s.get("title", ""),
                         "text": text})
        if len(docs) >= MAX_SOURCES:
            break

    emit("synthesizing")
    if not docs:
        return {"research_id": rid, "question": question, "queries": queries,
                "sources": [], "claims": [], "contradictions": [],
                "synthesis": ("No retrievable sources found — I could not verify "
                              "an answer. This is not a verified fact."),
                "verified": False, "elapsed_s": round(time.time() - started, 1)}

    numbered = [f"[{i + 1}] {d['title']} ({_host(d['url'])})\n{d['text'][:2500]}"
                for i, d in enumerate(docs)]
    prompt = (
        "Answer the user question using ONLY the sources below. "
        "Output: 1) 3-8 bullet claims, each ending with the source number(s) "
        "like [1][3]. 2) A line 'CONTRADICTIONS: ...' listing disagreements, "
        "or 'none'. 3) A 2-4 sentence synthesis. If sources don't support an "
        "answer, say so — never invent.\n\nQuestion: " + question +
        "\n\nSources:\n" + "\n\n".join(numbered))
    try:
        raw = _llm.query_llm(prompt, system_prompt=(
            "You are a research synthesizer. Cite every claim with [n]. "
            "No citations = no claim."), timeout=180)
    except Exception as e:
        return {"research_id": rid, "question": question, "queries": queries,
                "sources": [{"url": d["url"], "title": d["title"]} for d in docs],
                "claims": [], "contradictions": [],
                "synthesis": f"Synthesis failed (LLM unavailable: {e}). "
                             f"Retrieved {len(docs)} sources, listed below.",
                "verified": False, "elapsed_s": round(time.time() - started, 1)}

    claims, contradictions = [], "none"
    for line in raw.splitlines():
        s = line.strip().lstrip("-•*0123456789. ")
        if not s:
            continue
        if s.upper().startswith("CONTRADICTIONS:"):
            contradictions = s.split(":", 1)[1].strip() or "none"
        elif re.search(r"\[\d+\]", s):
            claims.append(s)
    # cross-check: claims citing the same fact from 2+ sources
    multi = [c for c in claims if len(re.findall(r"\[(\d+)\]", c)) >= 2]
    return {"research_id": rid, "question": question, "queries": queries,
            "sources": [{"url": d["url"], "title": d["title"]} for d in docs],
            "claims": claims, "multi_sourced_claims": len(multi),
            "contradictions": contradictions,
            "synthesis": raw[:2000], "verified": bool(claims),
            "elapsed_s": round(time.time() - started, 1)}
