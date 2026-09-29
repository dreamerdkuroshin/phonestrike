# PocketStrike-AI — Ultimate Jarvis Commander

A fully-featured AI assistant with Web UI, Voice Control, and OmniRoute integration.

## Capabilities
- Web UI — Iron Man themed chat interface
- Voice Control — Hey Strike wake word
- AI Gateway — OmniRoute integration (300+ providers)
- Model Switching — One-click model change via dropdown
- Image Generation — draw PROMPT command
- System Control — Run automator, status, restart
- Project Launch — Rehan, isair, Off Grid, OmniRoute
- Self-Healing — Auto-restart on crash
- Multi-Model Switcher — UI dropdown for instant switching

## Quick Start
cd ~/PocketStrike-AI && python server.py &
Open http://127.0.0.1:5000

One-command start (recommended — idempotent, also reposts voice button):
bash ~/PocketStrike-AI/start-all.sh

License: MIT

---

## ✅ Verified working status (checked live: 2026-09-29, on-device)

| # | Check | Result | Evidence |
|---|-------|--------|----------|
| 1 | Local LLM backend `:8081` | ✅ WORKING | `GET /health` → `{"status":"ok"}`; `/v1/models` → `pocket-qwen3` (llama-server, 897MB Q3, ctx 2048) |
| 2 | Strike server `:5000` | ✅ WORKING | `/api/status` → provider Custom, model pocket-qwen3, voice_enabled true |
| 3 | Chat AI (`/chat`, `/api/chat`) | ✅ WORKING | `hello` → `Hi, how are you?`; `2+2` → `4` (was ❌ 20128 Connection refused before fix) |
| 4 | Voice command API | ✅ WORKING | `battery status` → `🔋 15%`; `who are you` → Strike intro; joke request answered |
| 5 | Real app launch | ✅ WORKING | `open settings` → `Opened settings`; `open whatsapp` → `Opened whatsapp` (via `am start --user 0 <pkg>`) |
| 6 | Old bug: fake success | ✅ FIXED | `mobile_open_app` used missing `monkey` + wrong `.MainActivity`, always claimed success. Now verifies `Starting:` + honest error + Play Store fallback |
| 7 | Web voice UI | ✅ WORKING | Full `index.html` restored: mic buttons, Siri HUD overlay, TTS read-back |
| 8 | Tap-to-speak (shade button) | ✅ WORKING | `hey-strike-tap.sh` posts ongoing button; `hey-strike-once.sh --text` tested, no mic loop |
| 9 | Termux TTS / STT | ✅ WORKING | `termux-tts-speak` exit 0 (Samsung + Google engines); `termux-speech-to-text` returned live speech |
| 10 | HeyStrike APK | 🟡 SCAFFOLDED, NOT compiled | Full source in `HeyStrike/` + CI workflow; no JDK/SDK on phone so build via GitHub Actions |
| 11 | True always-on hotword (Siri-like, screen-off, no popup) | ❌ NOT POSSIBLE in Termux | Android reserves background mic + lockscreen hotword for system assistant; see explanation below |

> Battery was 15% during checks — keep phone charging; Llama + servers stop if the phone dies.

---

## 🎙️ How "Hey Siri" / "Hey Google" instant activation actually works (OS level)

It is NOT one big listener. It is a **3-stage pipeline**, each stage cheaper than the next:

**Stage 1 — Always-on DSP keyword spotter (milliwatts).**
A tiny neural net (~200KB–2MB) runs on the **low-power DSP/audio hub** (Snapdragon "Sound Trigger", Apple "Always-On Processor"), NOT the main CPU. The mic feeds this chip 24/7 at ~16kHz while the phone sleeps. It only detects sound energy + phoneme pattern of "Hey Siri". It knows ~nothing else. This is why it sips battery (~1–5mW) and why only the OS vendor can install one (needs DSP firmware signing + `CAPTURE_AUDIO_HOTWORD` system permission).

**Stage 2 — AP-side verifier (CPU wakes briefly).**
On DSP match, the main processor wakes for ~200ms and runs a bigger verifier model (speaker verification + anti-spoof: it checks it is YOUR voice texture and a live human, rejecting TV recordings). iOS does this in the Secure Enclave; Android in `SoundTrigger` HAL + `HotwordDetector`.

**Stage 3 — Full assistant (only now).**
Screen lights with Siri/Google orb UI, full speech-to-text streams (on-device first, cloud for hard queries), LLM answers, TTS speaks, actions execute with system privileges (open apps, calls — no hacks needed, it IS the OS).

Enrollment ("Say Hey Siri 5 times") trains stages 1–2 on your voice. Turning it off = DSP gate closed = zero mic processing (that's the privacy answer).

**Why Termux `termux-speech-to-text` loop can never match this:**
Google's popup STT runs full cloud recognition on the big CPU every listen (~watts, not milliwatts), shows a popup each time, is blocked in background on Android 9+, and can't run on the DSP. It is a *command capturer*, not a *hotword gate*. Our tap-to-speak design accepts this: popup only on tap.

---

## 🔨 Build the same always-on system yourself (all pieces)

**A. Closest without root (what `HeyStrike/` APK already does):**
1. Foreground mic service (`VoiceService.kt`) + in-app `SpeechRecognizer` — no popup, survives screen-off while service runs.
2. Offline spotter for the gate: add **openWakeWord** (free, on-device, ~50MB) or **Picovoice Porcupine** (needs free access key) trained on "hey strike". Keep Google STT ONLY for stage 3 (actual command). This mirrors DSP→AP staging in software (~more battery, but works).
3. Siri orb overlay (`OverlayService.kt` — done): `TYPE_APPLICATION_OVERLAY` + `SHOW_WHEN_LOCKED`, glow animation, auto-hide.
4. Brain: local `pocket-qwen3` via llama-server (done, offline) or cloud.
5. Actions via `PackageManager.getLaunchIntentForPackage()` (done) — no root/Shizuku needed for launching apps.
6. Battery: request `IGNORE_BATTERY_OPTIMIZATIONS`, hold partial wake lock, accept ~5–10%/hr extra drain (software gate ≫ DSP gate).

**B. True OS-level (like Google/Siri, needs system work):**
- Android: implement `VoiceInteractionService` + declare `ROLE_ASSISTANT`, register `SoundTrigger` keyphrase via `AlwaysOnHotwordDetector` (OEM-signed sound model required — this is the wall for indie devs; practically only Google/Samsung pass it).
- iOS: impossible for third parties (SiriKit only, no custom hotword).
- Alternative route many DIY builds use: **Home Assistant Assist + Wyoming protocol** (openWakeWord satellite → HA pipeline → phone webhook) — real always-on room listening, phone just renders the orb.

**C. What is missing in THIS repo vs that vision:**
- [ ] Offline spotter lib wired into `VoiceService` (currently cloud STT loop → popup + battery cost)
- [ ] `VoiceInteractionService`/assistant-role manifest (system-grade lockscreen takeover)
- [ ] Compiled APK (source ready; build via Actions: repo → Actions → download `HeyStrike-debug-apk`)
- [x] Everything else: brain, voice API, mobile actions, orb UI, one-command start, autostart
