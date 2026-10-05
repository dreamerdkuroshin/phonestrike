#!/data/data/com.termux/files/usr/bin/bash
# Hey Strike tap-to-speak — persistent notification, ZERO mic popups until you tap.
# Usage: bash hey-strike-tap.sh        # post notification
#        bash hey-strike-tap.sh --stop # remove notification
export PATH="/data/data/com.termux/files/usr/bin:$PATH"
TERMUX_HOME="/data/data/com.termux/files/home"
ONCE="$TERMUX_HOME/PocketStrike-AI/hey-strike-once.sh"

if [ "$1" = "--stop" ]; then
  termux-notification-remove hey_strike_tap 2>/dev/null
  termux-notification-remove hey_strike 2>/dev/null
  echo "🔕 Tap notification removed."
  exit 0
fi

chmod +x "$ONCE" 2>/dev/null
termux-wake-lock 2>/dev/null
termux-notification \
  --id hey_strike_tap \
  --title "Hey Strike 🎙️ — tap to speak (works on lockscreen)" \
  --content "Tap Speak even with screen locked. Unlock if asked, then talk." \
  --ongoing \
  --priority max \
  --button1 "🎙️ Speak" \
  --button1-action "bash $ONCE" \
  --button2 "⌨️ Type" \
  --button2-action "bash $ONCE --text \"\$REPLY\"" \
  --button3 "🔕 Hide" \
  --button3-action "termux-notification-remove hey_strike_tap" \
  2>/dev/null
echo "🔔 Tap-to-speak notification posted (lockscreen-visible, max priority)."
