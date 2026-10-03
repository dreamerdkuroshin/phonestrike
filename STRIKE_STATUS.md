# STRIKE_STATUS ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â engineering status matrix

Rule: nothing is green without evidence. Evidence codes:
`T` automated test Ãƒâ€šÃ‚Â· `D` on-device verified Ãƒâ€šÃ‚Â· `C` code-reviewed Ãƒâ€šÃ‚Â· `?` unknown.

| Component | Implemented | Build | Runtime | Integration | Tests | Bugs | Security | Status |
|---|---|---|---|---|---|---|---|---|
| Backend (Flask, now `strike/` pkg) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | 23/23 pytest | none open | see Security | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ |
| Local LLM (llama :8081) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | n/a | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ D | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ D | smoke D | none open | local-only | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ |
| Chat + follow-up context | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ D | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ D | T | fixed v2.5.3 | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ |
| Memory (mem + turns + topic) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ D | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ weak integration | unit | none open | local-only | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| ASR (Vosk en/hi, sherpa en) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ? | ? | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | wake-deaf ÃƒÂ°Ã…Â¸Ã¢â‚¬ÂÃ‚Â´ open | local-only | ÃƒÂ°Ã…Â¸Ã¢â‚¬ÂÃ‚Â´ |
| VAD/endpointing | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ? | ? | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | none open | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| TTS (sentence streaming) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ? HI voice | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | cut-off fixed v2.5.2 | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Voice session / states | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ C | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | none open | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Barge-in | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ? on-device | ? | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | none open | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Wake word | grammar [unk]-stripped, mic self-test + Start/Stop toggle shipped | OK build | ? device evidence | self-test pending | diagnosing | - | TBD |
| Android services (6) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ D partial | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ | 14/14 unit | crash guards v2.5.2 | perms declared | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Shizuku | fallback only | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ? | ? | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | none open | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Accessibility (tap/type/verify) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ? E2E | ? | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | exact-match fixed | user-gated | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Screen/camera/vision | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ? E2E | ? | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | Device buttons fixed v2.5.4 | consent-gated | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Termux scripts | legacy loop | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ D (server) | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | superseded by app | blocklist T | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| App launching (PackageManager) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ? | ? | unit | verify added | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Browser web UI | legacy, fixed this cycle | OK JS-syntax | ? live | ? | - | timers/barge-in/stop/race fixed, awaiting live test | LAN-open | TBD |
| Research (DDG 5) | basic | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | ? live | ? | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | none open | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Tasks (card + screen + stop) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | ? E2E | ? | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | none open | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| Agent (plan/act/verify/HITL) | OK | OK | ? full E2E | ? | jarvis dispatch T | empty-type fixed | confirm tiers | TBD |
| Security | partial | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | T | see below | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| UI (nav/tasks/device/orb) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ T | ? post-install | ? | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | orb fixed, awaiting install | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |
| CI/CD (GH Actions) | ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦ | ? not run this cycle | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â | none open | key in history | ÃƒÂ°Ã…Â¸Ã…Â¸Ã‚Â¡ |

## Security detail
- Critical: OmniRoute key in git history (`7fb1acf`ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Å“`2eecc95`) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â rotate at provider, then purge needs force-push consent. Working tree clean (key ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ env, `.pyc` untracked).
- High: LAN unauthenticated endpoints (chat/voice/search/vision) ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â localhost-only enforced for POST /config; rest accepted on trusted LAN by design.
- Medium: browser legacy UI timers; Termux `shell=True` confined by blocklist + test.
- Done: `/config` GET redacted, dead `/api/mobile/*` deleted (404 verified), uploads uuid + traversal-tested, `/api/health` real checks.

## Performance (Galaxy F23 target)
APK 45.5 MB Ãƒâ€šÃ‚Â· no new deps Ãƒâ€šÃ‚Â· builds ~2 min Ãƒâ€šÃ‚Â· tests <5 s + 23 pytest Ãƒâ€šÃ‚Â· findNode bounded Ãƒâ€šÃ‚Â· TTS drain ÃƒÂ¢Ã¢â‚¬Â°Ã‚Â¤60 s Ãƒâ€šÃ‚Â· SSE disconnect closes upstream.
Unused legacy: `chat_history.py`, `app_commands.py`, `ollama_proxy.py`, `mem0_integration.py` have zero importers ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â kept (may run manually), marked deprecated.

## Next priority
1. Wake-word deafness: user runs Settings ÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢ Mic self-test, returns verdict line.
2. Install v2.5.6, exercise orb + Tasks + Device screens.
3. E2E "open WhatsApp and message Beru" with contact + HITL confirm.
4. Secrets history purge (consent) + rotation.
5. Research mode / Settings reorg (queued).
