#!/data/data/com.termux/files/usr/bin/bash
# Hey Strike v2 — Siri-like always-on voice assistant (local-only)
# Usage:
#   bash hey-strike.sh              # foreground test
#   bash hey-strike.sh --daemon     # background
#   bash hey-strike.sh --stop       # stop
#   bash hey-strike.sh --test "open youtube"  # test pipeline without mic

STRIKE_URL="http://127.0.0.1:5000/api/voice/command"
TERMUX_HOME="/data/data/com.termux/files/home"
LOG="$TERMUX_HOME/PocketStrike-AI/hey-strike.log"

if [ "$1" = "--stop" ]; then
  pkill -f '[h]ey-strike\.sh' 2>/dev/null
  pkill -f '[t]ermux-speech-to-text' 2>/dev/null
  termux-notification-remove hey_strike 2>/dev/null
  echo "🛑 Hey Strike stopped."
  exit 0
fi

if [ "$1" = "--daemon" ]; then
  SCRIPT_ABS="$TERMUX_HOME/PocketStrike-AI/hey-strike.sh"
  setsid /data/data/com.termux/files/usr/bin/bash "$SCRIPT_ABS" run > "$LOG" 2>&1 < /dev/null &
  echo "🎙️ Hey Strike background started (PID $!). Log: $LOG"
  echo "Just say 'Hey Strike' — like Siri."
  exit 0
fi

termux-wake-lock 2>/dev/null

# Siri-like voice: slightly faster, natural English (Google TTS, music stream)
say_clean() {
  local raw="$1"
  # strip emojis, markdown, box chars for clean speech
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
notify() { termux-notification --id hey_strike --title "Hey Strike 🎙️" --content "$1" 2>/dev/null; }
vibrate() { termux-vibrate -d 80 2>/dev/null; }

GREETINGS=("Hey, I'm listening." "Yes?" "Go ahead." "Hmm?" "I'm here. What do you need?")
GOODBYES=("Goodbye!" "Anytime!" "Happy to help!")

pick() { local arr=("$@"); echo "${arr[$RANDOM % ${#arr[@]}]}"; }

strike_query() {
  local text="$1"
  /data/data/com.termux/files/usr/bin/python - "$text" <<'PY' 2>/dev/null
import sys, json, urllib.request
text = sys.argv[1]
data = json.dumps({"text": text}).encode()
try:
    req = urllib.request.Request("http://127.0.0.1:5000/api/voice/command", data=data, headers={"Content-Type":"application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        print(r.read().decode())
except Exception as e:
    print(json.dumps({"response": f"Sorry, I had trouble reaching my brain: {e}"}))
PY
}

get_answer() {
  /data/data/com.termux/files/usr/bin/python -c "import json,sys; print(json.load(sys.stdin).get('response','Sorry, error.')[:800])" 2>/dev/null
}

handle_command() {
  local cmd="$1"
  echo "[command] $cmd"
  notify "⚙️ $cmd"
  local resp_json=$(strike_query "$cmd")
  local answer=$(echo "$resp_json" | get_answer)
  [ -z "$answer" ] && answer="Sorry, I didn't catch that."
  echo "[strike] $answer"
  notify "$answer"
  say_clean "$answer"
  # return 1 if conversation should end (bye/thanks)
  if echo "$cmd" | tr '[:upper:]' '[:lower:]' | grep -q -E "^(bye|goodbye|stop|thank you|thanks|that's all|bas)$"; then
    return 1
  fi
  if echo "$answer" | grep -q "Goodbye! Say Hey Strike"; then
    return 1
  fi
  return 0
}

if [ "$1" = "--test" ]; then
  shift
  handle_command "$*"
  exit 0
fi

# server check
if ! /data/data/com.termux/files/usr/bin/curl -s -m 3 http://127.0.0.1:5000/api/status > /dev/null 2>&1; then
  echo "⚠️ Starting PocketStrike..."
  bash "$TERMUX_HOME/PocketStrike-AI/restart-strike.sh" 2>&1 | tail -n 3
fi
if ! /data/data/com.termux/files/usr/bin/curl -s -m 3 http://127.0.0.1:8081/health > /dev/null 2>&1; then
  echo "⚠️ Starting Llama..."
  bash "$TERMUX_HOME/PocketStrike-AI/start-llama.sh" 2>&1 | tail -n 5
fi

echo "🎙️ Hey Strike (Siri-mode) listening... just say 'Hey Strike + command'"
notify "Hey Strike online — just say Hey Strike"
# opening greeting, Siri style
say_clean "Hey Strike online."

while true; do
  heard=$(timeout 60 termux-speech-to-text 2>/dev/null | head -n 20 | tr '\n' ' ')
  [ -z "$heard" ] && continue
  # ignore STT errors silently
  if echo "$heard" | grep -q "ERROR"; then
    echo "[heard] $heard (ignored)"
    continue
  fi
  low=$(echo "$heard" | tr '[:upper:]' '[:lower:]' | xargs)
  echo "[heard] $heard"

  wake=0
  # forgiving wake: any "strike/stripe/strick" with or without hey/hello/ok/hi
  if echo "$low" | grep -q -E "strike|stripe|strick|stike|hey siri"; then
    wake=1
  # Siri-feel: bare common commands also wake (user forgets Hey Strike)
  elif echo "$low" | grep -q -E "^(open |launch |battery|what time|time|date|call |torch|flashlight|volume|search |google|navigate|who are you|how are you|thank|hello|hi)$"; then
    wake=1
    echo "[bare-command wake]"
  else
    # STT often drops "Hey Strike" prefix ("Hey Strike how are you" -> "how are you").
    # If speech is substantial, treat as implicit command so mic feels alive.
    words=$(echo "$low" | wc -w)
    if [ "$words" -ge 2 ]; then
      echo "[implicit command, no wake heard — processing anyway]"
      handle_command "$heard"
      continue
    fi
  fi
  if [ "$wake" -eq 1 ]; then
    vibrate
    echo "⚡ Wake!"
    # single-shot: "hey strike open youtube" -> extract after wake word, else use full phrase without wake
    cmd_after=$(echo "$low" | sed -E 's/.*(hey strike|hello strike|ok strike|hi strike|hey stripe|hey strick|hey siri|ok google)[ ,.!?]*//')
    # if no hey-prefix, just remove bare "strike" word (e.g. "strike open youtube")
    if [ "$cmd_after" = "$low" ]; then
      cmd_after=$(echo "$low" | sed -E 's/(hey|hello|ok|hi)[ ,]*//; s/strike|stripe|strick|stike//g' | xargs)
    fi
    if [ -n "$cmd_after" ] && [ ${#cmd_after} -gt 2 ]; then
      echo "[single-shot] $cmd_after"
      handle_command "$cmd_after"
      continue
    fi
    # two-step: greet then listen, Siri style
    greet=$(pick "${GREETINGS[@]}")
    echo "[greet] $greet"
    say_clean "$greet"
    notify "Listening..."
    # conversation mode: stay active ~30s for follow-ups without wake word
    convo_end=$((SECONDS + 30))
    while [ $SECONDS -lt $convo_end ]; do
      cmd=$(timeout 25 termux-speech-to-text 2>/dev/null | head -n 20 | tr '\n' ' ')
      [ -z "$cmd" ] && continue
      # allow wake repeat inside convo (strip it)
      handle_command "$cmd"
      rc=$?
      if [ $rc -eq 1 ]; then
        break
      fi
      # extend window after each successful command (like Siri)
      convo_end=$((SECONDS + 20))
      echo "...listening for follow-up (no wake needed)..."
    done
  fi
done
