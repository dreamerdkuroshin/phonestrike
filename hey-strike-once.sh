#!/data/data/com.termux/files/usr/bin/bash
# Hey Strike — single shot listener. Mic popup appears ONLY when this runs.
# Triggered by notification Speak button, widget, or manually.
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
TERMUX_HOME="/data/data/com.termux/files/home"
cd "$TERMUX_HOME/PocketStrike-AI" 2>/dev/null || exit 1

say_clean() {
  local raw="$1"
  local clean=$(echo "$raw" | /data/data/com.termux/files/usr/bin/python -c "
import sys, re
t = sys.stdin.read()
t = re.sub(r'[\U0001F000-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\u2500-\u257F]+', '', t)
for c in ['*','\`','#','>','_','|']:
    t = t.replace(c, '')
t = re.sub(r'\s+', ' ', t).strip()
print(t[:450])
" 2>/dev/null)
  [ -z "$clean" ] && clean="Done."
  termux-tts-speak -e com.google.android.tts -l en -r 1.1 -s MUSIC "$clean" 2>/dev/null || termux-tts-speak "$clean" 2>/dev/null
}
notify() { termux-notification --id hey_strike_reply --title "Hey Strike 🎙️" --content "$1" 2>/dev/null; }

# preset text mode (from notification Direct Reply typing, no mic): --text "battery status"
if [ "$1" = "--text" ]; then
  shift
  cmd="$*"
else
  # mic popup shows ONLY here, on demand
  cmd=$(timeout 30 termux-speech-to-text 2>/dev/null | head -n 20 | tr '\n' ' ' | xargs)
  if [ -z "$cmd" ] || echo "$cmd" | grep -q "ERROR"; then
    notify "Didn't catch that — tap Speak and try again."
    exit 0
  fi
fi

echo "[command] $cmd"
notify "⚙️ $cmd"
resp=$(/data/data/com.termux/files/usr/bin/python - "$cmd" <<'PY' 2>/dev/null
import sys, json, urllib.request
text = sys.argv[1]
data = json.dumps({"text": text}).encode()
try:
    req = urllib.request.Request("http://127.0.0.1:5000/api/voice/command", data=data, headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        print(r.read().decode())
except Exception as e:
    print(json.dumps({"response": f"Sorry, brain unreachable: {e}"}))
PY
)
answer=$(echo "$resp" | /data/data/com.termux/files/usr/bin/python -c "import json,sys; print(json.load(sys.stdin).get('response','Sorry')[:800])" 2>/dev/null)
[ -z "$answer" ] && answer="Sorry, error."
echo "[strike] $answer"
notify "$answer"
say_clean "$answer"
