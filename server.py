#!/usr/bin/env python3
# ==============================================
# POCKETSTRIKE-AI — ULTIMATE JARVIS COMMANDER
# ==============================================

import os
import json
import re
import subprocess
import shlex
import time
from flask import Flask, request, jsonify, render_template, send_from_directory, Response
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

# --- Configuration ---
CONFIG_PATH = os.path.expanduser("~/PocketStrike-AI/config.json")
OMNIROUTE_KEY = "***PURGED-ROTATED***"
OMNIROUTE_URL = "http://localhost:20128/v1"

# --- Default Config ---
def load_config():
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, 'r') as f:
            return json.load(f)
    return {
        "ai_provider": "openai",
        "openai": {
            "api_key": OMNIROUTE_KEY,
            "base_url": OMNIROUTE_URL,
            "model": "qoder/qoder"
        }
    }

config = load_config()

# --- Helpers to support both config formats ---
# setup.py writes flat format: {"provider","base_url","model","api_key",...}
# old code used nested format: {"openai":{"base_url","model","api_key"}}
def get_base_url():
    if config.get("base_url"):
        return str(config["base_url"]).rstrip("/")
    if isinstance(config.get("openai"), dict) and config["openai"].get("base_url"):
        return str(config["openai"]["base_url"]).rstrip("/")
    return OMNIROUTE_URL

def get_api_key():
    if config.get("api_key"):
        return config["api_key"]
    if isinstance(config.get("openai"), dict) and config["openai"].get("api_key"):
        return config["openai"]["api_key"]
    return OMNIROUTE_KEY

def get_model():
    if config.get("model"):
        return config["model"]
    if isinstance(config.get("openai"), dict) and config["openai"].get("model"):
        return config["openai"]["model"]
    return "qoder/qoder"

WORKSPACE_DIR = os.path.expanduser("~/PocketStrike-AI")
HISTORY_FILE = os.path.join(WORKSPACE_DIR, "agent", "conversations.json")
os.makedirs(os.path.join(WORKSPACE_DIR, "agent"), exist_ok=True)
os.makedirs(os.path.join(WORKSPACE_DIR, "uploads"), exist_ok=True)

def query_llm(user_text, system_prompt=None, timeout=120):
    """Send single prompt to configured LLM backend. Returns text."""
    import requests
    base_url = get_base_url()
    model = get_model()
    api_key = get_api_key()
    msgs = []
    if system_prompt:
        msgs.append({"role": "system", "content": system_prompt})
    msgs.append({"role": "user", "content": user_text})
    payload = {"model": model, "messages": msgs, "stream": False, "temperature": 0.7}
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    url = base_url.rstrip("/") + "/chat/completions"
    resp = requests.post(url, json=payload, headers=headers, timeout=timeout)
    if resp.status_code == 200:
        return resp.json().get('choices', [{}])[0].get('message', {}).get('content', "No response")
    raise RuntimeError(f"AI Error {resp.status_code}: {resp.text[:300]}")

# --- Mobile integration (Termux:API + Android intents + Shizuku) ---
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
        info = mobile_battery()
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
        return mobile_open_app(app)

    # call
    if t.startswith("call "):
        target = t[5:].strip()
        run_shell_command(f"am start -a android.intent.action.CALL -d tel:{shlex.quote(target)} 2>&1 | head -n 3")
        mobile_notify("Hey Strike", f"Calling {target}")
        return f"📞 Calling {target}..."

    # torch on/off
    if "torch on" in t or "flashlight on" in t or "turn on flash" in t:
        run_shell_command("termux-torch on 2>&1")
        return "🔦 Flashlight ON."
    if "torch off" in t or "flashlight off" in t or "turn off flash" in t:
        run_shell_command("termux-torch off 2>&1")
        return "🔦 Flashlight OFF."

    # volume
    if "volume up" in t or "increase volume" in t:
        run_shell_command("termux-volume music up 2>&1 || settings put system volume_music 10 2>&1")
        return "🔊 Volume up."
    if "volume down" in t or "decrease volume" in t or "mute" in t:
        run_shell_command("termux-volume music down 2>&1")
        return "🔉 Volume down."

    # time / date
    if t in ("what time is it", "time", "current time", "date", "what's the time"):
        return run_shell_command("date '+🕒 %I:%M %p, %a %b %d'")

    # search
    if t.startswith(("search ", "google ")):
        q = t.split(" ", 1)[1]
        import urllib.parse
        url = "https://www.google.com/search?q=" + urllib.parse.quote(q)
        run_shell_command(f"am start -a android.intent.action.VIEW -d {shlex.quote(url)} 2>&1 | head -n 3")
        return f"🔎 Searching Google for '{q}' on your phone..."

    # navigate
    if t.startswith(("navigate to ", "directions to ")):
        dest = t.split(" to ", 1)[1] if " to " in t else t
        import urllib.parse
        url = "google.navigation:q=" + urllib.parse.quote(dest)
        run_shell_command(f"am start --user 0 -a android.intent.action.VIEW -d {shlex.quote(url)} 2>&1 | head -n 3")
        return f"🗺️ Navigating to {dest}..."

    # security settings (Extend Unlock / Smart Lock lives here)
    if any(k in t for k in ["security settings", "smart lock", "extend unlock", "trust agent"]):
        out = run_shell_command("am start --user 0 -a android.settings.SECURITY_SETTINGS 2>&1 | head -n 3")
        if "Starting:" in out:
            return "🔐 Security settings opened. Look for Extend Unlock / Smart Lock → trusted place or watch."
        return "❌ Could not open Security settings."

    return None

# --- Command Execution ---
# Spec 60 — shell safety: catastrophic commands are refused outright, not
# passed to the LLM's judgment. Workspace-scoped rm is allowed for dev flows.
RISKY_RE = re.compile(
    r"\brm\s+(-[a-z]+\s+)*(?:/|~|\$HOME|(?<![\w./-])\*)(?:\s|$)"
    r"|\bmkfs(?:\.\w+)?\b"
    r"|\bdd\s+[^|;&]*\bof=/dev/"
    r"|:\(\)\s*\{"
    r"|\bchmod\s+-R\s+777\s+(?:/|~)"
    r"|>\s*/dev/sd",
    re.I,
)
SAFE_WORKSPACE = re.compile(r"PocketStrike-AI|my-automator|/tmp/", re.I)

def run_shell_command(command, timeout=60):
    """Execute a shell command and return output."""
    if RISKY_RE.search(command) and not SAFE_WORKSPACE.search(command):
        return ("BLOCKED high-risk command (shell safety): " + command[:120] +
                ". Not running it — if you really mean it, run it manually in Termux.")
    try:
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            executable="/data/data/com.termux/files/usr/bin/bash"
        )
        if result.stdout:
            return result.stdout.strip()
        elif result.stderr:
            return f"⚠️ Error: {result.stderr.strip()}"
        else:
            return "✅ Command executed (no output)"
    except subprocess.TimeoutExpired:
        return "⏰ Command timed out after 60 seconds"
    except Exception as e:
        return f"❌ Error: {str(e)}"

# --- Jarvis Commander ---
def jarvis_commander(command):
    """Main command router for Ultimate Jarvis."""
    global config
    command = command.lower().strip()
    
    # --- HELP ---
    if command in ["help", "?"]:
        return """
╔═══════════════════════════════════════════════╗
║         JARVIS COMMANDER — HELP               ║
╠═══════════════════════════════════════════════╣
║  🔹 SYSTEM CONTROL                            ║
║    run automator          - Open automator    ║
║    status / check status  - Show system      ║
║    stop pocketstrike      - Stop Strike      ║
║    restart strike         - Restart Strike   ║
║                                               ║
║  🔹 MODEL CONTROL                             ║
║    switch to MODEL        - Switch AI model  ║
║    list models            - Show models      ║
║    current model          - Show current     ║
║                                               ║
║  🔹 LAUNCH PROJECTS                           ║
║    launch rehan           - RehanIlyas       ║
║    launch isair           - isair-jarvis     ║
║    launch offgrid         - Off Grid APK     ║
║    launch automator       - Automator        ║
║    launch omniroute       - OmniRoute        ║
║                                               ║
║  🔹 OMNROUTE                                  ║
║    omniroute status       - Check OmniRoute  ║
║    omniroute restart      - Restart          ║
║                                               ║
║  🔹 MOBILE + VOICE (Hey Strike)              ║
║    open youtube          - Launch app        ║
║    call NUMBER           - Phone call        ║
║    battery status        - Battery %         ║
║    torch on/off         - Flashlight        ║
║    search QUERY         - Google search     ║
║    navigate to PLACE    - Maps navigation   ║
║                                               ║
║  🔹 GENERAL                                   ║
║    help / ?               - Show this menu   ║
╚═══════════════════════════════════════════════╝
"""
    
    # --- AUTOMATOR ---
    if command == "run automator" or command == "automator":
        return run_shell_command("cd ~/my-automator && ./automator.sh")
    
    # --- STATUS ---
    if command in ["status", "check status"]:
        status = "╔═══════════════════════════════════════════════╗\n"
        status += "║         JARVIS SYSTEM STATUS                 ║\n"
        status += "╠═══════════════════════════════════════════════╣\n"
        
        # OmniRoute / LLM backend (uses config.json base_url, falls back to 20128)
        base_url = get_base_url()
        # derive host root for health check, e.g. http://127.0.0.1:8081/v1 -> http://127.0.0.1:8081
        health_url = base_url[:-3] if base_url.endswith("/v1") else base_url
        omniroute = run_shell_command(f"curl -s -o /dev/null -w '%{{http_code}}' {shlex.quote(health_url + '/health')} 2>/dev/null")
        if omniroute == "200":
            status += "║  ✅ LLM Backend:            Running            ║\n"
        else:
            # fallback: try root path too (some backends have no /health)
            omniroute2 = run_shell_command(f"curl -s -o /dev/null -w '%{{http_code}}' {shlex.quote(health_url)} 2>/dev/null")
            if omniroute2 in ("200", "415"):
                status += "║  ✅ LLM Backend:            Running            ║\n"
            else:
                status += f"║  ❌ LLM Backend: {base_url[:28]} ║\n"
        
        # PocketStrike
        ps = run_shell_command("pgrep -f 'python server.py'")
        if ps:
            status += f"║  ✅ PocketStrike-AI:       Running (PID: {ps.strip()}) ║\n"
        else:
            status += "║  ❌ PocketStrike-AI:       Not running        ║\n"
        
        # Current Model
        try:
            model = get_model()
            status += f"║  🧠 Current Model:        {model[:30]} ║\n"
        except:
            status += "║  🧠 Current Model:        Unknown            ║\n"
        
        # Automator
        if os.path.exists(os.path.expanduser("~/my-automator/automator.sh")):
            status += "║  ✅ Automator:             Available          ║\n"
        else:
            status += "║  ❌ Automator:             Not found          ║\n"
        
        # OmniRoute key
        status += f"║  🔑 OmniRoute Key:        {'✅ Set' if OMNIROUTE_KEY else '❌ Missing'} ║\n"
        
        status += "╚═══════════════════════════════════════════════╝"
        return status
    
    # --- STOP POCKETSTRIKE ---
    if command == "stop pocketstrike" or command == "stop":
        run_shell_command("pkill -f 'python server.py'")
        return "🛑 PocketStrike-AI stopped. Run 'restart strike' to start again."
    
    # --- RESTART POCKETSTRIKE ---
    if command == "restart strike":
        run_shell_command("pkill -f 'python server.py'")
        time.sleep(1)
        run_shell_command("cd ~/PocketStrike-AI && python server.py &")
        return "🔄 Restarting PocketStrike-AI..."
    
    # --- CURRENT MODEL ---
    if command == "current model":
        try:
            model = get_model()
            provider = config.get('provider', config.get('ai_provider', 'Unknown'))
            base_url = get_base_url()
            return f"🧠 Current Model: {model}\n📡 Provider: {provider}\n🔗 Base URL: {base_url}"
        except:
            return "❌ Could not read config."
    
    # --- LIST MODELS ---
    if command == "list models":
        base_url = get_base_url()
        api_key = get_api_key()
        result = run_shell_command(
            f"curl -s {shlex.quote(base_url)}/models -H 'Authorization: Bearer {api_key}' | grep -o '\"id\":\"[^\"]*\"' | head -30 | sed 's/\"id\":\"//g' | sed 's/\"//g'"
        )
        if result:
            models = result.split('\n')
            output = f"📋 Available Models ({base_url}):\n"
            for i, m in enumerate(models, 1):
                if m:
                    output += f"  {i}. {m}\n"
            return output
        return f"❌ No models found or backend not running at {base_url}."
    
    # --- SWITCH MODEL ---
    if command.startswith("switch to "):
        model = command.replace("switch to", "").strip()
        if not model:
            return "❌ Usage: switch to MODEL_NAME (e.g., switch to strike-coder)"
        
        try:
            with open(CONFIG_PATH, 'r') as f:
                cfg = json.load(f)
            # Support both flat (setup.py) and nested (legacy openai) formats
            if "model" in cfg or "provider" in cfg:
                cfg['model'] = model
            elif isinstance(cfg.get('openai'), dict):
                cfg['openai']['model'] = model
            else:
                cfg['model'] = model
            with open(CONFIG_PATH, 'w') as f:
                json.dump(cfg, f, indent=2)
            # Update in-memory config too
            config = cfg
            
            return f"✅ Switched to model: {model}\n🔄 Restart server to apply (or it applies on next chat)."
        except Exception as e:
            return f"❌ Error switching model: {str(e)}"
    
    # --- LAUNCH PROJECTS ---
    if command.startswith("launch ") or command.startswith("start "):
        project = command.replace("launch", "").replace("start", "").strip()
        
        projects = {
            "rehan": "cd ~/jarvis-mega-repo/assistants/RehanIlyas-JARVIS && python main.py",
            "isair": "cd ~/jarvis-mega-repo/assistants/isair-jarvis && python jarvis.py",
            "offgrid": "termux-open ~/downloads/offgrid.apk",
            "automator": "cd ~/my-automator && ./automator.sh",
            "omniroute": "omniroute",
            "llama": "cd ~/llama.cpp && ./build/bin/llama-server -m Phi-3-mini-4k-instruct-Q4_K_M.gguf --host 127.0.0.1 --port 11434 -t 4 --ctx-size 2048",
        }
        
        if project in projects:
            run_shell_command(projects[project] + " &")
            return f"🚀 Launching {project.title()}..."
        else:
            available = ", ".join(projects.keys())
            return f"❌ Unknown project: {project}\n📋 Available: {available}"
    
    # --- OMNROUTE STATUS ---
    if command == "omniroute status":
        base_url = get_base_url()
        health_url = base_url[:-3] if base_url.endswith("/v1") else base_url
        code = run_shell_command(f"curl -s -o /dev/null -w '%{{http_code}}' {shlex.quote(health_url + '/health')} 2>/dev/null")
        if code != "200":
            code = run_shell_command(f"curl -s -o /dev/null -w '%{{http_code}}' {shlex.quote(health_url)} 2>/dev/null") + " (root)"
        return f"Backend {base_url}: HTTP {code}"
    
    # --- OMNROUTE RESTART ---
    if command == "omniroute restart":
        run_shell_command("pkill -f omniroute")
        time.sleep(2)
        run_shell_command("omniroute &")
        return "🔄 Restarting OmniRoute..."
    
    # --- GENERATE IMAGE ---
    if command.startswith("draw ") or command.startswith("generate image "):
        prompt = command.replace("draw", "").replace("generate image", "").strip()
        if not prompt:
            return "❌ Please specify what to draw."
        # Local llama-server cannot generate images; only cloud OmniRoute with image model can.
        base_url = get_base_url()
        if "localhost" in base_url or "127.0.0.1" in base_url:
            return "❌ Image generation needs a cloud provider (OmniRoute/OpenRouter). Your current backend is local llama-server which is text-only."
        api_key = get_api_key()
        result = run_shell_command(
            f"curl -s -X POST {shlex.quote(base_url)}/chat/completions -H 'Content-Type: application/json' -H 'Authorization: Bearer {api_key}' -d '{{\"model\":\"aihorde/SDXL 1.0\",\"messages\":[{{\"role\":\"user\",\"content\":\"Generate an image: {prompt}\"}}]}}' | grep -o 'https://[^\"]*' | head -1"
        )
        if result and result.startswith("http"):
            return f"🖼️ Image generated:\n{result}"
        return "❌ Could not generate image. Check OmniRoute and AI Horde connection."
    
    # --- MOBILE COMMANDS (Hey Strike phone control) ---
    mob = handle_mobile_command(command)
    if mob is not None:
        return mob

    # --- UNKNOWN ---
    return f"❓ Unknown command: {command}\nType 'help' for available commands."

# --- ROUTES ---

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/chat', methods=['POST'])
def chat():
    try:
        data = request.json or {}
        user_message = str(data.get('message', '')).strip()
        
        if not user_message:
            return jsonify({"error": "Empty message"}), 400

        # Mobile intents first (open app, battery, torch, call, search...)
        mob = handle_mobile_command(user_message)
        if mob is not None and not user_message.lower().startswith(("run ", "switch to ", "list ", "launch ", "start rehan", "start isair", "stop ", "status", "check ", "help", "?", "draw ", "generate image", "current model", "omniroute ", "restart strike")):
            # handle_mobile returns greeting for bare wake word too
            if not mob.startswith("❓"):
                return jsonify({"response": mob})
        
        # Check for Jarvis commands
        if user_message.lower().startswith(("run ", "switch to ", "list ", "launch ", "start ", "stop ", "status", "check ", "help", "?", "draw ", "generate image", "current model", "omniroute ", "restart strike", "open ", "call ", "battery", "torch", "flashlight", "volume", "search ", "google ", "navigate ", "time", "date")):
            response = jarvis_commander(user_message)
            # speak short mobile confirmations aloud if voice enabled
            if config.get("voice_enabled") and len(response) < 300:
                try:
                    mobile_tts_say(response)
                except Exception:
                    pass
            return jsonify({"response": response})
        
        # Otherwise, send to AI via configured backend (local llama-server or cloud)
        try:
            text = query_llm(user_message)
            return jsonify({"response": text})
        except Exception as e:
            base_url = get_base_url()
            return jsonify({"response": f"❌ AI Connection Error: {str(e)}\n🔗 Backend: {base_url}\n💡 Fix: start backend with: ~/llama.cpp/build/bin/llama-server -m ~/models/strike-coder-1.7b-abliterated-q3km.gguf --host 127.0.0.1 --port 8081 -a strike-coder --ctx-size 2048 -t 4"})

    except Exception as e:
        return jsonify({"error": str(e)}), 500

# --- Full Web UI compat endpoints (for static/script.js voice UI) ---
@app.route('/api/status', methods=['GET'])
def api_status():
    return jsonify({
        "provider": config.get("provider_name", config.get("provider", "Custom")),
        "model": get_model(),
        "telegram_status": "Enabled" if config.get("telegram_enabled") else "Disabled",
        "telegram_enabled": bool(config.get("telegram_enabled")),
        "voice_enabled": bool(config.get("voice_enabled")),
        "os_type": "android",
        "os_name": "Android (Termux)",
        "base_url": get_base_url(),
    })

def _load_conversations():
    try:
        if os.path.exists(HISTORY_FILE):
            with open(HISTORY_FILE, 'r') as f:
                d = json.load(f)
                return d if isinstance(d, list) else []
    except Exception:
        pass
    return []

@app.route('/api/history/load', methods=['GET'])
def api_history_load():
    return jsonify(_load_conversations())

@app.route('/api/history/sync', methods=['POST'])
def api_history_sync():
    try:
        data = request.json or []
        with open(HISTORY_FILE, 'w') as f:
            json.dump(data, f, indent=2)
        return jsonify({"status": "synced"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/mcp/list', methods=['GET'])
def api_mcp_list():
    return jsonify([])

@app.route('/api/upload', methods=['POST'])
def api_upload():
    try:
        if 'file' not in request.files:
            return jsonify({"error": "No file"}), 400
        f = request.files['file']
        if f.content_length and f.content_length > 10*1024*1024:
            return jsonify({"error": "File >10MB"}), 400
        dest = os.path.join(WORKSPACE_DIR, "uploads", f.filename)
        f.save(dest)
        size_kb = round(os.path.getsize(dest)/1024, 1)
        return jsonify({"filename": f.filename, "size_kb": size_kb})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/chat', methods=['POST'])
def api_chat():
    """Full-UI chat: {messages:[{role,content}...]} -> plain text stream."""
    try:
        data = request.json or {}
        messages = data.get('messages', [])
        if not messages:
            return "Empty message", 400
        last_user = ""
        for m in reversed(messages):
            if m.get('role') == 'user':
                last_user = str(m.get('content', ''))[:2000]
                break
        if not last_user:
            return "Empty message", 400
        # mobile first
        mob = handle_mobile_command(last_user)
        if mob is not None and not last_user.lower().startswith(("run ", "switch to ", "list ", "launch ", "start rehan", "stop ", "status", "check ", "help")):
            if not mob.startswith("❓"):
                if config.get("voice_enabled"):
                    try: mobile_tts_say(mob)
                    except Exception: pass
                return Response(mob, mimetype='text/plain')
        # jarvis commands
        if last_user.lower().startswith(("run ", "switch to ", "list ", "launch ", "start ", "stop ", "status", "check ", "help", "?", "draw ", "generate image", "current model", "omniroute ", "restart strike", "open ", "call ", "battery", "torch", "flashlight", "volume", "search ", "google ", "navigate ")):
            resp_text = jarvis_commander(last_user)
            if config.get("voice_enabled") and len(resp_text) < 300:
                try: mobile_tts_say(resp_text)
                except Exception: pass
            return Response(resp_text, mimetype='text/plain')
        # LLM with short voice-friendly system prompt when voice enabled (STREAMED raw for live UI)
        sys_prompt = None
        if config.get("voice_enabled"):
            sys_prompt = "You are Strike, a phone voice assistant like Gemini. Keep spoken answers short, natural, under 60 words unless asked for detail. No markdown."
        def _gen():
            try:
                import requests as _rq2
                base_url = get_base_url(); model = get_model(); api_key = get_api_key()
                msgs = ([{"role": "system", "content": sys_prompt}] if sys_prompt else []) + [{"role": "user", "content": last_user}]
                payload = {"model": model, "messages": msgs, "stream": True}
                headers = {"Content-Type": "application/json"}
                if api_key: headers["Authorization"] = f"Bearer {api_key}"
                r = _rq2.post(base_url.rstrip("/") + "/chat/completions", json=payload, headers=headers, timeout=120, stream=True)
                if r.status_code != 200:
                    yield f"AI Error {r.status_code}: {r.text[:200]}"
                    return
                for line in r.iter_lines():
                    if not line:
                        continue
                    s = line.decode('utf-8', 'replace').strip()
                    if not s.startswith("data:"):
                        continue
                    body = s[5:].strip()
                    if body == "[DONE]":
                        break
                    try:
                        import json as _js3
                        tok = _js3.loads(body)["choices"][0].get("delta", {}).get("content", "")
                    except Exception:
                        tok = ""
                    if tok:
                        yield tok
            except Exception as e:
                yield f"AI Connection Error: {e}"
        return Response(_gen(), mimetype='text/plain')
    except Exception as e:
        return str(e), 500

@app.route('/api/voice/command', methods=['POST'])
def api_voice_command():
    """Background Termux listener: {text} -> {response}. Handles mobile + LLM."""
    def run_single(piece):
        p = piece.strip()
        if not p:
            return None
        mob = handle_mobile_command(p)
        if mob is not None and not mob.startswith("❓"):
            return mob
        if p.lower().startswith(("run ", "switch to ", "list ", "launch ", "start ", "stop ", "status", "check ", "help", "?", "draw ", "generate image", "current model", "omniroute ", "restart strike", "open ", "call ", "battery", "torch", "flashlight", "volume", "search ", "google ", "navigate ")):
            return jarvis_commander(p)
        sys_prompt = "You are Strike, a phone voice assistant like Gemini. Keep answers short and speakable, under 60 words unless detail requested. No markdown."
        return query_llm(p, system_prompt=sys_prompt)
    try:
        data = request.json or {}
        text = str(data.get('text', '')).strip()
        if not text:
            return jsonify({"response": "I didn't hear anything."})
        import re as _re
        # chained tasks: "open whatsapp and then tell me the time" -> run each, join
        parts = _re.split(r'\s+(?:and then|and now|then|and after that|phir)\s+', text, flags=_re.IGNORECASE)
        parts = [p for p in (x.strip() for x in parts) if p]
        if len(parts) > 1:
            outs = []
            for p in parts[:5]:
                try:
                    r = run_single(p)
                    if r:
                        outs.append(str(r)[:500])
                except Exception as e:
                    outs.append(f"Step failed: {e}")
            return jsonify({"response": " ✅ ".join(outs)[:1500], "type": "chain"})
        try:
            return jsonify({"response": run_single(text), "type": "single"})
        except Exception as e:
            return jsonify({"response": f"AI Connection Error: {e}"}), 502
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/voice/stream', methods=['POST'])
def api_voice_stream():
    """Live token streaming (ChatGPT-style): SSE `data: {"token":"..."}` + `data: [DONE]`.
    Instant intents (mobile/jarvis) return immediately as one token."""
    def run_single(piece):
        p = (piece or "").strip()
        if not p:
            return None
        mob = handle_mobile_command(p)
        if mob is not None and not mob.startswith("❓"):
            return mob
        if p.lower().startswith(("run ", "switch to ", "list ", "launch ", "start ", "stop ", "status", "check ", "help", "?", "draw ", "generate image", "current model", "omniroute ", "restart strike", "open ", "call ", "battery", "torch", "flashlight", "volume", "search ", "google ", "navigate ")):
            return jarvis_commander(p)
        return None  # needs LLM streaming
    text_in = str((request.json or {}).get('text', '')).strip()
    from flask import stream_with_context

    @stream_with_context
    def gen():
        import json as _js
        import requests as _rq
        try:
            text = text_in
            if not text:
                yield 'data: {"token":"I didn\'t hear anything."}\n\n'
                yield 'data: [DONE]\n\n'
                return
            instant = run_single(text)
            if instant is not None:
                yield 'data: ' + _js.dumps({"token": instant}) + '\n\n'
                yield 'data: [DONE]\n\n'
                return
            base_url = get_base_url(); model = get_model(); api_key = get_api_key()
            sys_prompt = "You are Strike, a phone voice assistant like Gemini. Keep answers short and speakable, under 60 words unless detail requested. No markdown."
            payload = {"model": model, "messages": [{"role": "system", "content": sys_prompt}, {"role": "user", "content": text}], "stream": True}
            headers = {"Content-Type": "application/json"}
            if api_key:
                headers["Authorization"] = f"Bearer {api_key}"
            r = _rq.post(base_url.rstrip("/") + "/chat/completions", json=payload, headers=headers, timeout=120, stream=True)
            if r.status_code != 200:
                yield 'data: ' + _js.dumps({"token": f"AI Error {r.status_code}"}) + '\n\n'
                yield 'data: [DONE]\n\n'
                return
            for line in r.iter_lines():
                if not line:
                    continue
                s = line.decode('utf-8', 'replace').strip()
                if not s.startswith("data:"):
                    continue
                body = s[5:].strip()
                if body == "[DONE]":
                    break
                try:
                    tok = _js.loads(body)["choices"][0].get("delta", {}).get("content", "")
                except Exception:
                    tok = ""
                if tok:
                    yield 'data: ' + _js.dumps({"token": tok}) + '\n\n'
            yield 'data: [DONE]\n\n'
        except Exception as e:
            import json as _js2
            yield 'data: ' + _js2.dumps({"token": f"Stream error: {e}"}) + '\n\n'
            yield 'data: [DONE]\n\n'
    return Response(gen(), mimetype='text/event-stream')

@app.route('/api/search', methods=['GET'])
def api_search():
    """Tiny live web search (DuckDuckGo HTML, no API key) for recency questions."""
    q = str(request.args.get('q', '')).strip()[:200]
    if not q:
        return jsonify({"q": "", "results": [], "error": "q required"})
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
        return jsonify({"q": q, "results": out})
    except Exception as e:
        return jsonify({"q": q, "results": [], "error": str(e)})

@app.route('/api/vision', methods=['POST'])
def api_vision():
    """Spec 11/12 — {image: base64 jpeg, question} -> {answer}.
    Vision model comes from config.json "vision_model" (e.g. a llava-class
    model on the same OpenAI-compatible backend). Honest error if unset."""
    try:
        data = request.json or {}
        img = str(data.get('image', ''))[:6_000_000]
        q = str(data.get('question', 'Describe what you see.'))[:400]
        if not img:
            return jsonify({"error": "no image provided"}), 400
        vision_model = config.get("vision_model")
        if not vision_model and isinstance(config.get("openai"), dict):
            vision_model = config["openai"].get("vision_model")
        if not vision_model:
            return jsonify({"error": "no vision model — set \"vision_model\" in "
                                     "~/PocketStrike-AI/config.json (e.g. llava)"})
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
        if get_api_key():
            headers["Authorization"] = f"Bearer {get_api_key()}"
        r = _rq.post(get_base_url().rstrip("/") + "/chat/completions",
                     json=payload, headers=headers, timeout=60)
        if r.status_code != 200:
            return jsonify({"error": f"vision backend {r.status_code}: {r.text[:200]}"})
        ans = r.json().get("choices", [{}])[0].get("message", {}).get("content", "")
        return jsonify({"answer": ans})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/api/mobile/battery', methods=['GET'])
def api_mobile_battery():
    return jsonify({"info": mobile_battery()})

@app.route('/api/mobile/speak', methods=['POST'])
def api_mobile_speak():
    data = request.json or {}
    mobile_tts_say(str(data.get('text', ''))[:500])
    return jsonify({"status": "spoken"})

@app.route('/api/mobile/notify', methods=['POST'])
def api_mobile_notify():
    data = request.json or {}
    mobile_notify(str(data.get('title', 'Hey Strike')), str(data.get('message', '')))
    return jsonify({"status": "notified"})

@app.route('/api/mobile/open', methods=['POST'])
def api_mobile_open():
    data = request.json or {}
    return jsonify({"response": mobile_open_app(str(data.get('app', '')))})

@app.route('/api/crashlog', methods=['POST'])
def api_crashlog():
    """Uncaught Android stack traces (v2.5.1+). Termux cannot read other-uid
    logcat, so the app POSTs crashes here into agent/crash.log for debugging."""
    try:
        data = request.json or {}
        stack = str(data.get('stack', ''))[:8000]
        if not stack.strip():
            return jsonify({"error": "empty"}), 400
        path = os.path.join(WORKSPACE_DIR, "agent", "crash.log")
        with open(path, "a", encoding="utf-8") as f:
            f.write("\n===== %s app=%s thread=%s =====\n%s\n" % (
                time.strftime("%Y-%m-%d %H:%M:%S"),
                str(data.get("app", "?"))[:40],
                str(data.get("thread", "?"))[:40],
                stack))
        return jsonify({"status": "logged"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

@app.route('/config', methods=['GET'])
def get_config():
    return jsonify(config)

@app.route('/config', methods=['POST'])
def update_config():
    global config
    data = request.json
    config = data
    with open(CONFIG_PATH, 'w') as f:
        json.dump(config, f, indent=2)
    return jsonify({"status": "updated"})

if __name__ == '__main__':
    # Ensure config exists
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, 'w') as f:
            json.dump(config, f, indent=2)
    
    print("""
╔═══════════════════════════════════════════════╗
║         ULTIMATE JARVIS COMMANDER              ║
╠═══════════════════════════════════════════════╣
║  🚀 Strike is now your full commander!        ║
║  📋 Type 'help' in the chat for commands      ║
║  🎙️  Voice: "Hey Strike" + command            ║
║                                               ║
║  🌐 Web UI: http://127.0.0.1:5000             ║
╚═══════════════════════════════════════════════╝
""")
    app.run(host='0.0.0.0', port=5000, debug=False)
