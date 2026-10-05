"""Deterministic mobile-control intents. Returns a response string,
or None when the text is not a device intent (LLM handles it)."""
import json
import shlex

from . import device
from .shell import run_shell_command, run_shell_checked


def _verify_poll(check, timeout_s=5, interval_s=0.5):
    """Poll check() until truthy or timeout (§69 evidence, not claims)."""
    import time as _t
    deadline = _t.time() + timeout_s
    while _t.time() < deadline:
        try:
            if check():
                return True
        except Exception:
            pass
        _t.sleep(interval_s)
    return False


def handle_mobile_command(text):
    """Detect mobile-control intents. Returns response string or None if not mobile."""
    t = text.lower().strip()
    # strip wake words anywhere (supports single-shot "hey strike open youtube")
    for w in ["hey strike", "hello strike", "ok strike", "hi strike", "hey jarvis", "hey siri"]:
        if w in t:
            # keep text after wake word if present, else before (e.g. "open youtube hey strike")
            parts = t.split(w, 1)
            after = parts[1].strip(" ,.!?") if len(parts) > 1 else ""
            before = parts[0].strip(" ,.!?") if len(parts) > 0 else ""
            t = after if after else before
            break
    if not t:
        return "Hey! I'm listening. Ask me anything, or try open youtube, battery status, what time is it."
    # --- Siri-like small talk (instant, no LLM delay) ---
    if t in ("who are you", "what is your name", "your name", "introduce yourself", "about yourself"):
        return "I'm Strike, your offline phone assistant. I can open apps, check battery, tell time, search, and answer questions — just say Hey Strike."
    if t in ("how are you", "how are you doing", "how is it going", "are you ok"):
        return "I'm running great and fully offline. How can I help you?"
    if t in ("thank you", "thanks", "thankyou", "shukriya", "thanks a lot"):
        return "You're welcome! Anything else?"
    if t in ("bye", "goodbye", "see you", "good night", "stop", "that's all", "bas"):
        return "Goodbye! Say Hey Strike whenever you need me."
    if t in ("good morning", "good afternoon", "good evening", "hello", "hi", "hey", "salam", "assalam"):
        import datetime as _dt
        h = _dt.datetime.now().hour
        daypart = "morning" if 5 <= h < 12 else "afternoon" if 12 <= h < 17 else "evening"
        return f"Good {daypart}! I'm Strike. How can I help?"
    if t in ("i love you", "do you love me"):
        return "I appreciate that! I'm always here to help you."
    # --- Unlock-by-voice is impossible by Android security: refuse + guide ---
    if ("password" in t or "passcode" in t or "pin" in t) and any(k in t for k in ["unlock", "open my phone", "open the phone", "unlock my phone", "lock khol"]):
        return ("I can't unlock your phone by voice — Android blocks every app, including me, from unlocking. "
                "Never speak your password aloud, anyone nearby would hear it. "
                "Instead use Extend Unlock: trusted place or watch keeps it unlocked. "
                "Say 'open security settings' and I'll take you there. Meanwhile, tell me the task after you unlock — e.g. open whatsapp.")

    # battery
    if any(k in t for k in ["battery", "charge level", "how much battery"]):
        info = device.mobile_battery()
        try:
            j = json.loads(info) if info.strip().startswith("{") else None
            if j:
                pct = j.get("percentage", "?")
                status = j.get("status", "")
                return f"🔋 Battery: {pct}% ({status})"
        except Exception:
            pass
        return f"🔋 Battery info:\n{info[:600]}"

    # open / launch app
    if t.startswith(("open ", "launch ", "start ")):
        app = t.split(" ", 1)[1].strip()
        # avoid clash with project launcher (rehan/isair/...) – let jarvis handle those first
        if app in ("rehan", "isair", "offgrid", "automator", "omniroute", "llama"):
            return None
        # compound task ("open whatsapp and message beru") is NOT one app name —
        # never misreport it as unknown; let the LLM answer honestly instead
        if " and " in app or " then " in app:
            return None
        return device.mobile_open_app(app)

    # call — verify the phone actually entered a call state
    if t.startswith("call "):
        target = t[5:].strip()
        rc, out = run_shell_checked(f"am start -a android.intent.action.CALL -d tel:{shlex.quote(target)} 2>&1 | head -n 3")
        if rc != 0 or "Starting:" not in out:
            return f"❌ Could not place the call ({out[:160]})."
        connected = _verify_poll(lambda: (
            "CALL_STATE_OFFHOOK" in run_shell_command("termux-telephony-device-state 2>&1") or
            "CALL_STATE_RINGING" in run_shell_command("termux-telephony-device-state 2>&1")))
        device.mobile_notify("Hey Strike", f"Calling {target}")
        if connected:
            return f"📞 Calling {target}..."
        return f"⚠️ Dialer opened for {target} but no active call detected — check the screen."

    # torch on/off — termux-torch prints nothing on success, errors on failure
    if "torch on" in t or "flashlight on" in t or "turn on flash" in t:
        rc, out = run_shell_checked("termux-torch on 2>&1")
        if rc == 0 and "error" not in out.lower():
            return "🔦 Flashlight ON."
        return f"❌ Flashlight did not turn on ({out[:160]})."
    if "torch off" in t or "flashlight off" in t or "turn off flash" in t:
        rc, out = run_shell_checked("termux-torch off 2>&1")
        if rc == 0 and "error" not in out.lower():
            return "🔦 Flashlight OFF."
        return f"❌ Flashlight did not turn off ({out[:160]})."

    # volume — read back the actual level as evidence
    if "volume up" in t or "increase volume" in t:
        run_shell_checked("termux-volume music up 2>&1 || settings put system volume_music 10 2>&1")
        lvl = run_shell_command("termux-volume 2>&1 | grep -o '\"music_volume\"[^,]*' | head -n 1")
        return f"🔊 Volume up. ({lvl[:60]})" if lvl and "Error" not in lvl else "🔊 Volume up."
    if "volume down" in t or "decrease volume" in t or "mute" in t:
        run_shell_checked("termux-volume music down 2>&1")
        lvl = run_shell_command("termux-volume 2>&1 | grep -o '\"music_volume\"[^,]*' | head -n 1")
        return f"🔉 Volume down. ({lvl[:60]})" if lvl and "Error" not in lvl else "🔉 Volume down."

    # time / date
    if t in ("what time is it", "time", "current time", "date", "what's the time"):
        return run_shell_command("date '+🕒 %I:%M %p, %a %b %d'")

    # search — verify am actually started something
    if t.startswith(("search ", "google ")):
        q = t.split(" ", 1)[1]
        import urllib.parse
        url = "https://www.google.com/search?q=" + urllib.parse.quote(q)
        rc, out = run_shell_checked(f"am start -a android.intent.action.VIEW -d {shlex.quote(url)} 2>&1 | head -n 3")
        if rc == 0 and "Starting:" in out:
            return f"🔎 Searching Google for '{q}' on your phone..."
        return f"❌ Could not start the search ({out[:160]})."

    # navigate — same verification
    if t.startswith(("navigate to ", "directions to ")):
        dest = t.split(" to ", 1)[1] if " to " in t else t
        import urllib.parse
        url = "google.navigation:q=" + urllib.parse.quote(dest)
        rc, out = run_shell_checked(f"am start --user 0 -a android.intent.action.VIEW -d {shlex.quote(url)} 2>&1 | head -n 3")
        if rc == 0 and "Starting:" in out:
            return f"🗺️ Navigating to {dest}..."
        return f"❌ Could not start navigation ({out[:160]})."

    # security settings (Extend Unlock / Smart Lock lives here)
    if any(k in t for k in ["security settings", "smart lock", "extend unlock", "trust agent"]):
        out = run_shell_command("am start --user 0 -a android.settings.SECURITY_SETTINGS 2>&1 | head -n 3")
        if "Starting:" in out:
            return "🔐 Security settings opened. Look for Extend Unlock / Smart Lock → trusted place or watch."
        return "❌ Could not open Security settings."

    return None
