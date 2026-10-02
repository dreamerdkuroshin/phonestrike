"""On-device actions (Termux:API + Android intents + Shizuku fallback)."""
import shlex

from .shell import run_shell_command

APP_PACKAGES = {
    "youtube": "com.google.android.youtube", "whatsapp": "com.whatsapp",
    "chrome": "com.android.chrome", "gmail": "com.google.android.gm",
    "maps": "com.google.android.apps.maps", "camera": "com.android.camera2",
    "gallery": "com.google.android.apps.photos", "spotify": "com.spotify.music",
    "telegram": "org.telegram.messenger", "instagram": "com.instagram.android",
    "facebook": "com.facebook.katana", "settings": "com.android.settings",
    "play store": "com.android.vending", "clock": "com.google.android.deskclock",
    "calculator": "com.google.android.calculator", "files": "com.google.android.documentsui",
    "gemini": "com.google.android.apps.bard", "assistant": "com.google.android.apps.googleassistant",
}


def mobile_battery():
    out = run_shell_command("termux-battery-status 2>/dev/null || dumpsys battery 2>/dev/null | head -n 20")
    return out or "❌ Battery info unavailable (need Termux:API)."


def mobile_tts_say(text, background=False):
    # Clean for speech: strip markdown/code
    clean = text[:400]
    for ch in ["*", "`", "#", ">"]:
        clean = clean.replace(ch, "")
    cmd = f"termux-tts-speak {shlex.quote(clean)}"
    if background:
        cmd += " &"
    run_shell_command(cmd, timeout=15)
    return True


def mobile_notify(title, content):
    run_shell_command(f"termux-notification --title {shlex.quote(title)} --content {shlex.quote(content[:400])} 2>/dev/null")
    return True


def mobile_open_app(app_name):
    key = app_name.lower().strip()
    pkg = APP_PACKAGES.get(key, key if "." in key else None)
    if not pkg:
        return f"❌ Unknown app '{app_name}'. Try: {', '.join(sorted(APP_PACKAGES.keys())[:12])}..."
    # Primary: let Android resolve MAIN/LAUNCHER for the package (works without Shizuku/root)
    out = run_shell_command(f"am start --user 0 {shlex.quote(pkg)} 2>&1 | head -n 5")
    if "Starting:" in out or "Starting" in out:
        mobile_notify("Hey Strike", f"Opening {app_name}")
        return f"🚀 Opened {app_name}."
    # Fallback 1: explicit MAIN/LAUNCHER intent
    out2 = run_shell_command(f"am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER {shlex.quote(pkg)} 2>&1 | head -n 5")
    if "Starting:" in out2:
        mobile_notify("Hey Strike", f"Opening {app_name}")
        return f"🚀 Opened {app_name}."
    # Fallback 2: Shizuku/rish if paired
    out3 = run_shell_command(f"rish -c 'cmd package resolve-activity --brief {shlex.quote(pkg)}' 2>&1 | head -n 5")
    # Honest failure (don't claim success): probably not installed or blocked
    run_shell_command(f"am start --user 0 -a android.intent.action.VIEW -d {shlex.quote('market://search?q=' + app_name)} 2>/dev/null")
    return (f"❌ Could not open '{app_name}' ({pkg}).\n"
            f"Launch attempt said: {(out + ' ' + out2)[:200]}\n"
            f"Is the app installed? I opened Play Store search for '{app_name}' instead.")
