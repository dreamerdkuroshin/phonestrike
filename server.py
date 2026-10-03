#!/usr/bin/env python3
# ==============================================
# POCKETSTRIKE-AI — ULTIMATE JARVIS COMMANDER
# ==============================================
# Thin HTTP layer. All logic lives in strike/:
#   config  load/read/replace configuration
#   llm     the ONLY model client (single + streaming)
#   device  on-device actions (Termux:API, am intents, Shizuku)
#   intents deterministic mobile-control intents
#   shell   gated subprocess execution
#   jarvis  Termux project/model router
#   media   web search, vision, uploads
#   system  status, health, history, crashlog, config views

from flask import Flask, Response, jsonify, render_template, request, stream_with_context
from flask_cors import CORS

from strike import config, device, intents, jarvis, llm, media, system
from strike import executor, provider, research

app = Flask(__name__)
CORS(app)

# --- route guards (kept verbatim from the monolith per endpoint) ---
_JARVIS_API = ("run ", "switch to ", "list ", "launch ", "start ", "stop ", "status", "check ", "help", "?", "draw ", "generate image", "current model", "omniroute ", "restart strike", "open ", "call ", "battery", "torch", "flashlight", "volume", "search ", "google ", "navigate ")
_JARVIS_CHAT = ("run ", "switch to ", "list ", "launch ", "start rehan", "start isair", "stop ", "status", "check ", "help")
_MOB_CHAT = ("run ", "switch to ", "list ", "launch ", "start ", "stop ", "status", "check ", "help", "?", "draw ", "generate image", "current model", "omniroute ", "restart strike", "open ", "call ", "battery", "torch", "flashlight", "volume", "search ", "google ", "navigate ", "time", "date")
_MOB_VOICE = ("run ", "switch to ", "list ", "launch ", "start ", "stop ", "status", "check ", "help", "?", "draw ", "generate image", "current model", "omniroute ", "restart strike", "open ", "call ", "battery", "torch", "flashlight", "volume", "search ", "google ", "navigate ")


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
        mob = intents.handle_mobile_command(user_message)
        if mob is not None and not user_message.lower().startswith(_MOB_CHAT):
            # handle_mobile returns greeting for bare wake word too
            if not mob.startswith("❓"):
                return jsonify({"response": mob})

        # Check for Jarvis commands
        if user_message.lower().startswith(_JARVIS_CHAT):
            response = jarvis.jarvis_commander(user_message)
            # speak short mobile confirmations aloud if voice enabled
            if config.get().get("voice_enabled") and len(response) < 300:
                try:
                    device.mobile_tts_say(response)
                except Exception:
                    pass
            return jsonify({"response": response})

        # Otherwise, send to AI via configured backend (local llama-server or cloud)
        try:
            text = llm.query_llm(user_message)
            return jsonify({"response": text})
        except Exception as e:
            base_url = config.get_base_url()
            return jsonify({"response": f"❌ AI Connection Error: {str(e)}\n🔗 Backend: {base_url}\n💡 Fix: start backend with: ~/llama.cpp/build/bin/llama-server -m ~/models/strike-coder-1.7b-abliterated-q3km.gguf --host 127.0.0.1 --port 8081 -a strike-coder --ctx-size 2048 -t 4"})

    except Exception as e:
        return jsonify({"error": str(e)}), 500


# --- Full Web UI compat endpoints (for static/script.js voice UI) ---
@app.route('/api/status', methods=['GET'])
def api_status():
    return jsonify(system.status_dict())


@app.route('/api/history/load', methods=['GET'])
def api_history_load():
    return jsonify(system.load_conversations())


@app.route('/api/history/sync', methods=['POST'])
def api_history_sync():
    try:
        data = request.json or []
        system.save_conversations(data)
        return jsonify({"status": "synced"})
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route('/api/mcp/list', methods=['GET'])
def api_mcp_list():
    return jsonify([])


@app.route('/api/upload', methods=['POST'])
def api_upload():
    if 'file' not in request.files:
        return jsonify({"error": "No file"}), 400
    info, err = media.save_upload(request.files['file'])
    if err:
        return jsonify({"error": err}), 400
    return jsonify(info)


@app.route('/api/chat', methods=['POST'])
def api_chat():
    """Full-UI chat: {messages:[{role,content}...], raw?} -> plain text stream."""
    try:
        data = request.json or {}
        messages = data.get('messages', [])
        if not messages:
            return "Empty message", 400
        raw = bool(data.get("raw"))
        sys_prompt = None
        if config.get().get("voice_enabled"):
            sys_prompt = "You are Strike, a phone voice assistant like Gemini. Keep spoken answers short, natural, under 60 words unless asked for detail. No markdown."
        last_user, msgs = llm.build_chat_messages(messages, sys_prompt)
        if not last_user:
            return "Empty message", 400
        if not raw:
            # mobile first
            mob = intents.handle_mobile_command(last_user)
            if mob is not None and not last_user.lower().startswith(_MOB_VOICE):
                if not mob.startswith("❓"):
                    if config.get().get("voice_enabled"):
                        try:
                            device.mobile_tts_say(mob)
                        except Exception:
                            pass
                    return Response(mob, mimetype='text/plain')
            # jarvis commands
            if last_user.lower().startswith(_JARVIS_API):
                resp_text = jarvis.jarvis_commander(last_user)
                if config.get().get("voice_enabled") and len(resp_text) < 300:
                    try:
                        device.mobile_tts_say(resp_text)
                    except Exception:
                        pass
                return Response(resp_text, mimetype='text/plain')

        # LLM with short voice-friendly system prompt when voice enabled (STREAMED raw for live UI)
        def _gen():
            try:
                for tok in llm.iter_tokens(msgs):
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
        mob = intents.handle_mobile_command(p)
        if mob is not None and not mob.startswith("❓"):
            return mob
        if p.lower().startswith(_JARVIS_API):
            return jarvis.jarvis_commander(p)
        sys_prompt = "You are Strike, a phone voice assistant like Gemini. Keep answers short and speakable, under 60 words unless detail requested. No markdown."
        return llm.query_llm(p, system_prompt=sys_prompt)
    try:
        data = request.json or {}
        text = str(data.get('text', '')).strip()
        if not text:
            return jsonify({"response": "I didn't hear anything."})
        import re as _re
        # chained tasks: "open whatsapp and message beru" -> run each, join.
        # plain "and"/"aur" also splits, but only when the first step is an
        # action — "cats and dogs" stays one LLM call.
        parts = _re.split(r'\s+(?:and then|and now|then|and after that|phir|and|aur)\s+', text, flags=_re.IGNORECASE)
        parts = [p for p in (x.strip() for x in parts) if p]
        if len(parts) > 1 and not parts[0].lower().startswith(("open ", "launch ", "start ", "call ", "message ", "text ", "tap ", "go ", "turn ", "switch ", "run ")):
            parts = [text]
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
        mob = intents.handle_mobile_command(p)
        if mob is not None and not mob.startswith("❓"):
            return mob
        if p.lower().startswith(_JARVIS_API):
            return jarvis.jarvis_commander(p)
        return None  # needs LLM streaming
    text_in = str((request.json or {}).get('text', '')).strip()

    @stream_with_context
    def gen():
        import json as _js
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
            sys_prompt = "You are Strike, a phone voice assistant like Gemini. Keep answers short and speakable, under 60 words unless detail requested. No markdown."
            msgs = [{"role": "system", "content": sys_prompt}, {"role": "user", "content": text}]
            for tok in llm.iter_tokens(msgs):
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
    results, err = media.web_search(q)
    return jsonify({"q": q, "results": results, "error": err} if err else {"q": q, "results": results})


@app.route('/api/vision', methods=['POST'])
def api_vision():
    """Spec 11/12 — {image: base64 jpeg, question} -> {answer}.
    Vision model comes from config.json "vision_model" (e.g. a llava-class
    model on the same OpenAI-compatible backend). Honest error if unset."""
    try:
        data = request.json or {}
        ans, err = media.ask_vision(data.get('image', ''), data.get('question', 'Describe what you see.'))
    except Exception as e:
        return jsonify({"error": str(e)}), 500
    if err:
        # legacy codes: missing image is 400, everything else rides HTTP 200
        # (Android reads inputStream — a 4xx would mask the honest message)
        if err == "no image provided":
            return jsonify({"error": err}), 400
        return jsonify({"error": err})
    return jsonify({"answer": ans})


@app.route('/api/crashlog', methods=['POST'])
def api_crashlog():
    """Uncaught Android stack traces (v2.5.1+). Termux cannot read other-uid
    logcat, so the app POSTs crashes here into agent/crash.log for debugging."""
    data = request.json or {}
    ok, msg = system.append_crash(data.get("app"), data.get("thread"), data.get("stack", ""))
    if not ok:
        return jsonify({"error": msg}), 400 if msg == "empty" else 500
    return jsonify({"status": msg})


@app.route('/api/health', methods=['GET'])
def api_health():
    return jsonify(system.health_dict())


@app.route('/api/research', methods=['POST'])
def api_research():
    """Research agent: {question} -> claims + contradictions + synthesis."""
    data = request.json or {}
    q = str(data.get('question', '')).strip()[:500]
    if not q:
        return jsonify({"error": "question required"}), 400
    steps = []

    def progress(s):
        steps.append(s)
    try:
        out = research.research(q, progress=progress)
        out["steps"] = steps
        return jsonify(out)
    except Exception as e:
        return jsonify({"error": str(e)}), 500


@app.route('/api/tasks', methods=['POST'])
def api_tasks_create():
    """Autonomous executor: {repo, build?, fix?} -> {task_id}."""
    data = request.json or {}
    repo = str(data.get('repo', '')).strip()[:500]
    if not repo:
        return jsonify({"error": "repo required"}), 400
    tid = executor.create_task(repo=repo, build=str(data.get('build', 'auto'))[:20],
                               fix=bool(data.get('fix', True)))
    return jsonify({"task_id": tid})


@app.route('/api/tasks/<tid>', methods=['GET'])
def api_tasks_get(tid):
    t = executor.get_task(tid)
    if not t:
        return jsonify({"error": "unknown task"}), 404
    return jsonify(t)


@app.route('/api/tasks/<tid>/cancel', methods=['POST'])
def api_tasks_cancel(tid):
    if not executor.cancel_task(tid):
        return jsonify({"error": "unknown or finished task"}), 404
    return jsonify({"status": "cancelled"})


@app.route('/api/history/purge', methods=['POST'])
def api_history_purge():
    """Delete history. {scope: conversations|all}. Uploads never touched."""
    data = request.json or {}
    removed = system.purge_history(str(data.get('scope', 'conversations'))[:20])
    return jsonify({"removed": removed})


@app.route('/api/provider/rotate', methods=['POST'])
def api_provider_rotate():
    """Rotate provider credentials. Localhost only (same rule as /config).
    {api_key?, base_url?, model?} -> validation + test result, never the key."""
    if request.remote_addr not in ("127.0.0.1", "::1"):
        return jsonify({"error": "provider rotation is local-only"}), 403
    data = request.json or {}
    res, err = provider.rotate(data.get('api_key'), data.get('base_url'), data.get('model'))
    if err:
        return jsonify({"error": err}), 502
    return jsonify(res)


@app.route('/config', methods=['GET'])
def get_config():
    return jsonify(system.config_view())


@app.route('/config', methods=['POST'])
def update_config():
    data = request.json
    ok, msg = system.config_replace_local(data, request.remote_addr)
    if not ok:
        return jsonify({"error": msg}), 403
    return jsonify({"status": msg})


if __name__ == '__main__':
    # Ensure config exists
    config.ensure_file()

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
