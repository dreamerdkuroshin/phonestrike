# STRIKE_STATUS — engineering status matrix

Rule: nothing is green without evidence. Evidence codes:
`T` automated test · `D` on-device verified · `C` code-reviewed · `?` unknown.

| Component | Implemented | Build | Runtime | Integration | Tests | Bugs | Security | Status |
|---|---|---|---|---|---|---|---|---|
| Backend (Flask, now `strike/` pkg) | ✅ | ✅ T | ✅ T | ✅ T | 10/10 pytest | none open | see Security | ✅ |
| Local LLM (llama :8081) | ✅ | n/a | ✅ D | ✅ D | smoke D | none open | local-only | ✅ |
| Chat + follow-up context | ✅ | ✅ T | ✅ D | ✅ D | T | fixed v2.5.3 | — | ✅ |
| Memory (mem + turns + topic) | ✅ | ✅ | ✅ D | 🟡 weak integration | unit | none open | local-only | 🟡 |
| ASR (Vosk en/hi, sherpa en) | ✅ | ✅ | ? | ? | — | wake-deaf 🔴 open | local-only | 🔴 |
| VAD/endpointing | ✅ | ✅ | ? | ? | — | none open | — | 🟡 |
| TTS (sentence streaming) | ✅ | ✅ | ? HI voice | 🟡 | — | cut-off fixed v2.5.2 | — | 🟡 |
| Voice session / states | ✅ | ✅ | ✅ C | 🟡 | — | none open | — | 🟡 |
| Barge-in | ✅ | ✅ | ? on-device | ? | — | none open | — | 🟡 |
| Wake word | ✅ | ✅ | ❌ user reports deaf | ? | self-test added, awaiting evidence | grammar fix shipped, unverified | — | 🔴 |
| Android services (6) | ✅ | ✅ T | ✅ D partial | 🟡 | 14/14 unit | crash guards v2.5.2 | perms declared | 🟡 |
| Shizuku | fallback only | ✅ | ? | ? | — | none open | — | 🟡 |
| Accessibility (tap/type/verify) | ✅ | ✅ | ? E2E | ? | — | exact-match fixed | user-gated | 🟡 |
| Screen/camera/vision | ✅ | ✅ | ? E2E | ? | — | Device buttons fixed v2.5.4 | consent-gated | 🟡 |
| Termux scripts | legacy loop | ✅ | ✅ D (server) | 🟡 | — | superseded by app | blocklist T | 🟡 |
| App launching (PackageManager) | ✅ | ✅ | ? | ? | unit | verify added | — | 🟡 |
| Browser web UI | legacy | ? | ? | ? | — | early-submit timers known | LAN-open | 🔴 known |
| Research (DDG 5) | basic | ✅ T | ? live | ? | — | none open | — | 🟡 |
| Tasks (card + screen + stop) | ✅ | ✅ T | ? E2E | ? | — | none open | — | 🟡 |
| Agent (plan/act/verify/HITL) | ✅ | ✅ | ? full E2E | ? | — | empty-type fixed | confirm tiers | 🟡 |
| Security | partial | ✅ T | ✅ T | — | T | see below | — | 🟡 |
| UI (nav/tasks/device/orb) | ✅ | ✅ T | ? post-install | ? | — | orb fixed, awaiting install | — | 🟡 |
| CI/CD (GH Actions) | ✅ | ? not run this cycle | — | — | — | none open | key in history | 🟡 |

## Security detail
- Critical: OmniRoute key in git history (`7fb1acf`–`2eecc95`) — rotate at provider, then purge needs force-push consent. Working tree clean (key → env, `.pyc` untracked).
- High: LAN unauthenticated endpoints (chat/voice/search/vision) — localhost-only enforced for POST /config; rest accepted on trusted LAN by design.
- Medium: browser legacy UI timers; Termux `shell=True` confined by blocklist + test.
- Done: `/config` GET redacted, dead `/api/mobile/*` deleted (404 verified), uploads uuid + traversal-tested, `/api/health` real checks.

## Performance (Galaxy F23 target)
APK 45.5 MB · no new deps · builds ~2 min · tests <5 s · findNode bounded · TTS drain ≤60 s · SSE disconnect closes upstream.

## Next priority
1. Wake-word deafness: user runs Settings → Mic self-test, returns verdict line.
2. Install v2.5.6, exercise orb + Tasks + Device screens.
3. E2E "open WhatsApp and message Beru" with contact + HITL confirm.
4. Secrets history purge (consent) + rotation.
5. Research mode / Settings reorg (queued).
