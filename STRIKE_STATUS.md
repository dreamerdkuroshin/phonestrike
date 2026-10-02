# STRIKE_STATUS Ã¢â‚¬â€ engineering status matrix

Rule: nothing is green without evidence. Evidence codes:
`T` automated test Ã‚Â· `D` on-device verified Ã‚Â· `C` code-reviewed Ã‚Â· `?` unknown.

| Component | Implemented | Build | Runtime | Integration | Tests | Bugs | Security | Status |
|---|---|---|---|---|---|---|---|---|
| Backend (Flask, now `strike/` pkg) | Ã¢Å“â€¦ | Ã¢Å“â€¦ T | Ã¢Å“â€¦ T | Ã¢Å“â€¦ T | 23/23 pytest | none open | see Security | Ã¢Å“â€¦ |
| Local LLM (llama :8081) | Ã¢Å“â€¦ | n/a | Ã¢Å“â€¦ D | Ã¢Å“â€¦ D | smoke D | none open | local-only | Ã¢Å“â€¦ |
| Chat + follow-up context | Ã¢Å“â€¦ | Ã¢Å“â€¦ T | Ã¢Å“â€¦ D | Ã¢Å“â€¦ D | T | fixed v2.5.3 | Ã¢â‚¬â€ | Ã¢Å“â€¦ |
| Memory (mem + turns + topic) | Ã¢Å“â€¦ | Ã¢Å“â€¦ | Ã¢Å“â€¦ D | Ã°Å¸Å¸Â¡ weak integration | unit | none open | local-only | Ã°Å¸Å¸Â¡ |
| ASR (Vosk en/hi, sherpa en) | Ã¢Å“â€¦ | Ã¢Å“â€¦ | ? | ? | Ã¢â‚¬â€ | wake-deaf Ã°Å¸â€Â´ open | local-only | Ã°Å¸â€Â´ |
| VAD/endpointing | Ã¢Å“â€¦ | Ã¢Å“â€¦ | ? | ? | Ã¢â‚¬â€ | none open | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| TTS (sentence streaming) | Ã¢Å“â€¦ | Ã¢Å“â€¦ | ? HI voice | Ã°Å¸Å¸Â¡ | Ã¢â‚¬â€ | cut-off fixed v2.5.2 | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| Voice session / states | Ã¢Å“â€¦ | Ã¢Å“â€¦ | Ã¢Å“â€¦ C | Ã°Å¸Å¸Â¡ | Ã¢â‚¬â€ | none open | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| Barge-in | Ã¢Å“â€¦ | Ã¢Å“â€¦ | ? on-device | ? | Ã¢â‚¬â€ | none open | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| Wake word | Ã¢Å“â€¦ | Ã¢Å“â€¦ | Ã¢ÂÅ’ user reports deaf | ? | self-test added, awaiting evidence | grammar fix shipped, unverified | Ã¢â‚¬â€ | Ã°Å¸â€Â´ |
| Android services (6) | Ã¢Å“â€¦ | Ã¢Å“â€¦ T | Ã¢Å“â€¦ D partial | Ã°Å¸Å¸Â¡ | 14/14 unit | crash guards v2.5.2 | perms declared | Ã°Å¸Å¸Â¡ |
| Shizuku | fallback only | Ã¢Å“â€¦ | ? | ? | Ã¢â‚¬â€ | none open | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| Accessibility (tap/type/verify) | Ã¢Å“â€¦ | Ã¢Å“â€¦ | ? E2E | ? | Ã¢â‚¬â€ | exact-match fixed | user-gated | Ã°Å¸Å¸Â¡ |
| Screen/camera/vision | Ã¢Å“â€¦ | Ã¢Å“â€¦ | ? E2E | ? | Ã¢â‚¬â€ | Device buttons fixed v2.5.4 | consent-gated | Ã°Å¸Å¸Â¡ |
| Termux scripts | legacy loop | Ã¢Å“â€¦ | Ã¢Å“â€¦ D (server) | Ã°Å¸Å¸Â¡ | Ã¢â‚¬â€ | superseded by app | blocklist T | Ã°Å¸Å¸Â¡ |
| App launching (PackageManager) | Ã¢Å“â€¦ | Ã¢Å“â€¦ | ? | ? | unit | verify added | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| Browser web UI | legacy, fixed this cycle | OK JS-syntax | ? live | ? | - | timers/barge-in/stop/race fixed, awaiting live test | LAN-open | TBD |
| Research (DDG 5) | basic | Ã¢Å“â€¦ T | ? live | ? | Ã¢â‚¬â€ | none open | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| Tasks (card + screen + stop) | Ã¢Å“â€¦ | Ã¢Å“â€¦ T | ? E2E | ? | Ã¢â‚¬â€ | none open | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| Agent (plan/act/verify/HITL) | OK | OK | ? full E2E | ? | jarvis dispatch T | empty-type fixed | confirm tiers | TBD |
| Security | partial | Ã¢Å“â€¦ T | Ã¢Å“â€¦ T | Ã¢â‚¬â€ | T | see below | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| UI (nav/tasks/device/orb) | Ã¢Å“â€¦ | Ã¢Å“â€¦ T | ? post-install | ? | Ã¢â‚¬â€ | orb fixed, awaiting install | Ã¢â‚¬â€ | Ã°Å¸Å¸Â¡ |
| CI/CD (GH Actions) | Ã¢Å“â€¦ | ? not run this cycle | Ã¢â‚¬â€ | Ã¢â‚¬â€ | Ã¢â‚¬â€ | none open | key in history | Ã°Å¸Å¸Â¡ |

## Security detail
- Critical: OmniRoute key in git history (`7fb1acf`Ã¢â‚¬â€œ`2eecc95`) Ã¢â‚¬â€ rotate at provider, then purge needs force-push consent. Working tree clean (key Ã¢â€ â€™ env, `.pyc` untracked).
- High: LAN unauthenticated endpoints (chat/voice/search/vision) Ã¢â‚¬â€ localhost-only enforced for POST /config; rest accepted on trusted LAN by design.
- Medium: browser legacy UI timers; Termux `shell=True` confined by blocklist + test.
- Done: `/config` GET redacted, dead `/api/mobile/*` deleted (404 verified), uploads uuid + traversal-tested, `/api/health` real checks.

## Performance (Galaxy F23 target)
APK 45.5 MB Ã‚Â· no new deps Ã‚Â· builds ~2 min Ã‚Â· tests <5 s + 23 pytest Ã‚Â· findNode bounded Ã‚Â· TTS drain Ã¢â€°Â¤60 s Ã‚Â· SSE disconnect closes upstream.
Unused legacy: `chat_history.py`, `app_commands.py`, `ollama_proxy.py`, `mem0_integration.py` have zero importers Ã¢â‚¬â€ kept (may run manually), marked deprecated.

## Next priority
1. Wake-word deafness: user runs Settings Ã¢â€ â€™ Mic self-test, returns verdict line.
2. Install v2.5.6, exercise orb + Tasks + Device screens.
3. E2E "open WhatsApp and message Beru" with contact + HITL confirm.
4. Secrets history purge (consent) + rotation.
5. Research mode / Settings reorg (queued).
