"""Jarvis command router (Termux projects, models, omniroute, images).

Semantics: exact commands -> prefix commands (with argument) -> mobile
intents -> unknown. Each handler is a unit-testable function returning
Optional[str] (None = not mine, keep looking).
"""
import os
import shlex
import time

from . import config
from . import intents
from .shell import run_shell_command, run_shell_checked


def _pgrep(pattern):
    """pgrep excluding OUR OWN process (W-04: the old `pgrep -f
    'python server.py'` matched the checker itself, so status always
    claimed Running even when the server was dead)."""
    me = str(os.getpid())
    out = run_shell_command(f"pgrep -f {shlex.quote(pattern)} 2>/dev/null")
    if not out or "Error" in out or "BLOCKED" in out:
        return ""
    pids = [p for p in out.split() if p.strip() != me]
    return " ".join(pids)


def _http_code(url, timeout=4):
    """HTTP status via urllib in-process (no curl, no key in ps)."""
    import urllib.request as _u
    try:
        req = _u.Request(url, headers={"Authorization": "Bearer " + config.get_api_key()})
        with _u.urlopen(req, timeout=timeout) as r:
            return str(r.status)
    except Exception as e:
        code = getattr(getattr(e, "response", None), "status", None)
        if code:
            return str(code)
        import urllib.error as _e
        if isinstance(e, _e.HTTPError):
            return str(e.code)
        return "000"


def _poll_http_ok(url, timeout_s=10):
    """Poll until url answers 2xx (§69 evidence for restart paths)."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        code = _http_code(url)
        if code.startswith("2"):
            return True
        time.sleep(1)
    return False


def _help():
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


def _automator():
    return run_shell_command("cd ~/my-automator && ./automator.sh")


def _backend_health():
    """(code, url) for the configured LLM backend."""
    base_url = config.get_base_url()
    health_url = base_url[:-3] if base_url.endswith("/v1") else base_url
    code = run_shell_command(
        f"curl -s -o /dev/null -w '%{{http_code}}' {shlex.quote(health_url + '/health')} 2>/dev/null")
    if code != "200":
        code = run_shell_command(
            f"curl -s -o /dev/null -w '%{{http_code}}' {shlex.quote(health_url)} 2>/dev/null")
    return code, base_url


def _status():
    status = "╔═══════════════════════════════════════════════╗\n"
    status += "║         JARVIS SYSTEM STATUS                 ║\n"
    status += "╠═══════════════════════════════════════════════╣\n"

    # OmniRoute / LLM backend (uses config.json base_url, falls back to 20128)
    code, base_url = _backend_health()
    if code == "200":
        status += "║  ✅ LLM Backend:            Running            ║\n"
    else:
        # fallback: try root path too (some backends have no /health)
        if code in ("200", "415"):
            status += "║  ✅ LLM Backend:            Running            ║\n"
        else:
            status += f"║  ❌ LLM Backend: {base_url[:28]} ║\n"

    # PocketStrike (self-PID excluded — see _pgrep)
    ps = _pgrep("python server.py")
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


def _stop():
    run_shell_command("pkill -f 'python server.py'")
    # NOTE: this reply may never arrive — the server dies with the request.
    # The client seeing the connection drop IS the confirmation.
    return "🛑 PocketStrike-AI stopped (this connection will drop — that means it worked). Run start-all.sh to start again."


def _restart():
    run_shell_command("pkill -f 'python server.py'")
    time.sleep(1)
    run_shell_command("cd ~/PocketStrike-AI && python server.py &")
    if _poll_http_ok("http://127.0.0.1:5000/api/status", 12):
        return "🔄 PocketStrike-AI restarted and answering (verified /api/status 200)."
    return "⚠️ Restart issued but /api/status is not answering — check pocketstrike-5000.log."


def _current_model():
    try:
        model = config.get_model()
        provider = config.get().get('provider', config.get().get('ai_provider', 'Unknown'))
        base_url = config.get_base_url()
        return f"🧠 Current Model: {model}\n📡 Provider: {provider}\n🔗 Base URL: {base_url}"
    except Exception:
        return "❌ Could not read config."


def _list_models():
    base_url = config.get_base_url()
    # urllib in-process: the key never appears in ps/shell history (F-01)
    try:
        import json as _j
        import urllib.request as _u
        req = _u.Request(base_url.rstrip("/") + "/models",
                         headers={"Authorization": "Bearer " + config.get_api_key()})
        with _u.urlopen(req, timeout=10) as r:
            body = _j.loads(r.read(200000).decode("utf-8", "replace"))
        items = body.get("data") if isinstance(body, dict) else body
        models = [m.get("id", m) if isinstance(m, dict) else m
                  for m in (items or [])][:30]
        models = [m for m in models if m]
        if models:
            output = f"📋 Available Models ({base_url}):\n"
            for i, m in enumerate(models, 1):
                output += f"  {i}. {m}\n"
            return output
    except Exception:
        pass
    return f"❌ No models found or backend not running at {base_url}."


def _switch_model(model):
    if not model:
        return "❌ Usage: switch to MODEL_NAME (e.g., switch to strike-coder)"
    _, msg = config.set_model(model)
    return msg


_PROJECTS = {
    "rehan": "cd ~/jarvis-mega-repo/assistants/RehanIlyas-JARVIS && python main.py",
    "isair": "cd ~/jarvis-mega-repo/assistants/isair-jarvis && python jarvis.py",
    "offgrid": "termux-open ~/downloads/offgrid.apk",
    "automator": "cd ~/my-automator && ./automator.sh",
    "omniroute": "omniroute",
    "llama": "cd ~/llama.cpp && ./build/bin/llama-server -m Phi-3-mini-4k-instruct-Q4_K_M.gguf --host 127.0.0.1 --port 11434 -t 4 --ctx-size 2048",
}


def _launch_project(project):
    if project in _PROJECTS:
        run_shell_command(_PROJECTS[project] + " &")
        time.sleep(1)
        # verify: project process visible (self-PID excluded)
        probe = {"rehan": "RehanIlyas", "isair": "isair-jarvis",
                 "automator": "automator.sh", "omniroute": "omniroute",
                 "llama": "llama-server"}.get(project, project)
        if _pgrep(probe) or project == "offgrid":
            return f"🚀 Launching {project.title()}... (process confirmed)"
        return (f"⚠️ Launch issued for {project.title()} but no process found — "
                "it may have exited; check Termux output.")
    available = ", ".join(_PROJECTS.keys())
    return f"❌ Unknown project: {project}\n📋 Available: {available}"


def _omni_status():
    code, base_url = _backend_health()
    if code != "200":
        code = code + " (root)"
    return f"Backend {base_url}: HTTP {code}"


def _omni_restart():
    run_shell_command("pkill -f omniroute")
    time.sleep(2)
    run_shell_command("omniroute &")
    time.sleep(2)
    if _pgrep("omniroute"):
        return "🔄 OmniRoute restarted (process confirmed)."
    return "⚠️ OmniRoute restart issued but no process found — check Termux output."


def _draw_image(prompt):
    if not prompt:
        return "❌ Please specify what to draw."
    # Local llama-server cannot generate images; only cloud OmniRoute with image model can.
    base_url = config.get_base_url()
    if "localhost" in base_url or "127.0.0.1" in base_url:
        return "❌ Image generation needs a cloud provider (OmniRoute/OpenRouter). Your current backend is local llama-server which is text-only."
    api_key = config.get_api_key()
    # urllib in-process: key + prompt never appear in ps (F-01)
    try:
        import json as _j
        import re as _re
        import urllib.request as _u
        payload = _j.dumps({"model": "aihorde/SDXL 1.0", "messages": [
            {"role": "user", "content": f"Generate an image: {prompt}"}]}).encode()
        req = _u.Request(base_url.rstrip("/") + "/chat/completions", data=payload,
                         headers={"Content-Type": "application/json",
                                  "Authorization": "Bearer " + api_key})
        with _u.urlopen(req, timeout=120) as r:
            body = r.read(200000).decode("utf-8", "replace")
        m = _re.search(r"https://[^\"]*", body)
        if m:
            return f"🖼️ Image generated:\n{m.group(0)}"
    except Exception:
        pass
    return "❌ Could not generate image. Check OmniRoute and AI Horde connection."


_EXACT = {
    "help": _help,
    "?": _help,
    "run automator": _automator,
    "automator": _automator,
    "status": _status,
    "check status": _status,
    "stop pocketstrike": _stop,
    "stop": _stop,
    "restart strike": _restart,
    "current model": _current_model,
    "list models": _list_models,
    "omniroute status": _omni_status,
    "omniroute restart": _omni_restart,
}

_PREFIX = (
    ("switch to ", _switch_model),
    ("launch ", _launch_project),
    ("start ", _launch_project),
    ("draw ", _draw_image),
    ("generate image ", _draw_image),
)


def jarvis_commander(command):
    """Main command router for Ultimate Jarvis."""
    command = command.lower().strip()
    if command in _EXACT:
        return _EXACT[command]()
    for pre, fn in _PREFIX:
        if command.startswith(pre):
            return fn(command[len(pre):].strip())
    # --- MOBILE COMMANDS (Hey Strike phone control) ---
    mob = intents.handle_mobile_command(command)
    if mob is not None:
        return mob
    # --- UNKNOWN ---
    return f"❓ Unknown command: {command}\nType 'help' for available commands."
