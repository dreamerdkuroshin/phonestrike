# Hey Strike APK — Siri-style voice app for PocketStrike

In-app mic (SpeechRecognizer, **no system popup**), floating Siri orb over any
screen incl. lockscreen, speaks answers, opens apps via PackageManager
(reliable — no am/monkey hacks), everything else asked from your Termux
PocketStrike brain (`/api/voice/command`, local pocket-qwen3).

## Permissions you grant (all requested in-app)
Mic, Display-over-apps (orb), Notifications, Phone/SMS optional.

## Build (no PC needed theory, easiest via cloud)
1. Push this folder to GitHub (`PocketStrike-AI` repo).
2. Actions tab → **Build HeyStrike APK** → download `HeyStrike-debug-apk`.
3. Install APK, open it, tap 1→4, set server `http://127.0.0.1:5000`.
4. In Termux keep servers up: `bash ~/PocketStrike-AI/start-all.sh`.

Local build alternative: Android Studio → Open `HeyStrike/` → Run.
(I can't compile APK on this phone: no JDK/Android SDK here.)
