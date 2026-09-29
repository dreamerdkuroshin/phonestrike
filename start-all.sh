#!/data/data/com.termux/files/usr/bin/bash
# Strike Start-All — one command, idempotent. No setup every time.
# Usage: bash ~/PocketStrike-AI/start-all.sh
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
TERMUX_HOME="/data/data/com.termux/files/home"
DIR="$TERMUX_HOME/PocketStrike-AI"
QUIET=0
[ "$1" = "--quiet" ] && QUIET=1
log() { [ "$QUIET" -eq 0 ] && echo "$1"; }
log() { if [ "$QUIET" -eq 0 ]; then echo "$1"; else echo "$(date '+%H:%M:%S') $1" >> "$DIR/start-all.log"; fi; }

CURL="/data/data/com.termux/files/usr/bin/curl"

# 0. Hold CPU wake lock so servers survive screen-off / lock (released on reboot)
termux-wake-lock 2>/dev/null

# 1. Llama backend :8081
if $CURL -s -m 3 http://127.0.0.1:8081/health 2>/dev/null | grep -q ok; then
  log "✅ Llama :8081 already running."
else
  log "🚀 Starting Llama (pocket-qwen3, ~15s load)..."
  /data/data/com.termux/files/usr/bin/bash "$DIR/start-llama.sh" >> "$DIR/start-all.log" 2>&1
  for i in $(seq 1 40); do
    if $CURL -s -m 3 http://127.0.0.1:8081/health 2>/dev/null | grep -q ok; then
      log "✅ Llama online."
      break
    fi
    sleep 3
  done
fi

# 2. PocketStrike :5000
if $CURL -s -m 3 http://127.0.0.1:5000/api/status 2>/dev/null | grep -q pocket-qwen3; then
  log "✅ Strike :5000 already running."
else
  log "🚀 Starting Strike server..."
  /data/data/com.termux/files/usr/bin/bash "$DIR/restart-strike.sh" >> "$DIR/start-all.log" 2>&1
  sleep 4
  if $CURL -s -m 3 http://127.0.0.1:5000/api/status 2>/dev/null | grep -q pocket; then
    log "✅ Strike online."
  else
    log "⚠️ Strike may still be starting — check: tail $DIR/pocketstrike-5000.log"
  fi
fi

# 3. Tap-to-speak notification (no mic loop, popup only on tap)
log "🔔 Posting tap-to-speak button..."
/data/data/com.termux/files/usr/bin/bash "$DIR/hey-strike-tap.sh" >> "$DIR/start-all.log" 2>&1
log ""
log "🎉 READY — open http://127.0.0.1:5000"
log "   Pull notification shade → 🎙️ Speak to talk."
