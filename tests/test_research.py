"""Research agent tests. Network + LLM are mocked; pipeline logic is real."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

from strike import research


def test_plan_queries():
    qs = research.plan_queries("Bitcoin regulation India")
    assert qs and qs[0] == "Bitcoin regulation India"
    assert len(qs) <= 3
    assert len(set(qs)) == len(qs)


def test_research_no_sources(monkeypatch):
    monkeypatch.setattr(research, "web_search", lambda q: ([], None))
    out = research.research("something unfindable xyz")
    assert out["verified"] is False
    assert out["sources"] == [] and out["claims"] == []
    assert "not a verified fact" in out["synthesis"]
    assert out["research_id"] and out["elapsed_s"] is not None


def test_research_full_path(monkeypatch):
    monkeypatch.setattr(research, "web_search", lambda q: (
        [{"url": "http://a.test/x", "title": "A", "snippet": "s"},
         {"url": "http://b.test/y", "title": "B", "snippet": "s"}], None))
    monkeypatch.setattr(research, "fetch_text",
                        lambda u, timeout=10: ("T-" + u, "long enough body text " * 30, None))
    seen = {}

    def fake_llm(prompt, system_prompt=None, timeout=180):
        seen["prompt"] = prompt
        return ("- Delhi is the capital [1][2]\n"
                "- Population is large [2]\n"
                "CONTRADICTIONS: none")
    monkeypatch.setattr("strike.research._llm.query_llm", fake_llm)
    out = research.research("capital question")
    assert out["verified"] is True
    assert len(out["claims"]) == 2
    assert out["multi_sourced_claims"] == 1
    assert out["contradictions"] == "none"
    assert len(out["sources"]) == 2
    # regression: phone LLM runs ctx 2048 — evidence must fit with room to answer
    assert len(seen["prompt"]) <= 2600


def test_research_llm_down(monkeypatch):
    monkeypatch.setattr(research, "web_search", lambda q: (
        [{"url": "http://a.test/x", "title": "A", "snippet": "s"}], None))
    monkeypatch.setattr(research, "fetch_text",
                        lambda u, timeout=10: ("T", "body " * 60, None))

    def boom(*a, **k):
        raise RuntimeError("no backend")
    monkeypatch.setattr("strike.research._llm.query_llm", boom)
    out = research.research("q")
    assert out["verified"] is False
    assert len(out["sources"]) == 1  # retrieved sources still reported
    assert "LLM unavailable" in out["synthesis"]


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
