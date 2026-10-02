"""Jarvis command router (Termux projects, models, omniroute, images)."""
import json
import os
import shlex
import time

from . import config
from . import intents
from .shell import run_shell_command


def jarvis_commander(command):
    """Main command router for Ultimate Jarvis."""
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
║    switch to MODEL        - Switch AI model   ║
║    list models            - Show models       ║
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
        base_url = config.get_base_url()
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
            model = config.get_model()
            status += f"║  🧠 Current Model:        {model[:30]} ║\n"
        except Exception:
            status += "║  🧠 Current Model:        Unknown            ║\n"

        # Automator
        if os.path.exists(os.path.expanduser("~/my-automator/automator.sh")):
            status += "║  ✅ Automator:             Available          ║\n"
        else:
            status += "║  ❌ Automator:             Not found          ║\n"

        # OmniRoute key
        status += f"║  🔑 OmniRoute Key:        {'✅ Set' if config.OMNIROUTE_KEY else '❌ Missing'} ║\n"

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
            model = config.get_model()
            provider = config.get().get('provider', config.get().get('ai_provider', 'Unknown'))
            base_url = config.get_base_url()
            return f"🧠 Current Model: {model}\n📡 Provider: {provider}\n🔗 Base URL: {base_url}"
        except Exception:
            return "❌ Could not read config."

    # --- LIST MODELS ---
    if command == "list models":
        base_url = config.get_base_url()
        api_key = config.get_api_key()
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
        _, msg = config.set_model(model)
        return msg

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
        base_url = config.get_base_url()
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
        base_url = config.get_base_url()
        if "localhost" in base_url or "127.0.0.1" in base_url:
            return "❌ Image generation needs a cloud provider (OmniRoute/OpenRouter). Your current backend is local llama-server which is text-only."
        api_key = config.get_api_key()
        result = run_shell_command(
            f"curl -s -X POST {shlex.quote(base_url)}/chat/completions -H 'Content-Type: application/json' -H 'Authorization: Bearer {api_key}' -d '{{\"model\":\"aihorde/SDXL 1.0\",\"messages\":[{{\"role\":\"user\",\"content\":\"Generate an image: {prompt}\"}}]}}' | grep -o 'https://[^\"]*' | head -1"
        )
        if result and result.startswith("http"):
            return f"🖼️ Image generated:\n{result}"
        return "❌ Could not generate image. Check OmniRoute and AI Horde connection."

    # --- MOBILE COMMANDS (Hey Strike phone control) ---
    mob = intents.handle_mobile_command(command)
    if mob is not None:
        return mob

    # --- UNKNOWN ---
    return f"❓ Unknown command: {command}\nType 'help' for available commands."
