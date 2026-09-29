#!/data/data/com.termux/files/usr/bin/bash
export HOME=/data/data/com.termux/files/home
export LD_LIBRARY_PATH=/data/data/com.termux/files/home/llama.cpp/build/bin:$LD_LIBRARY_PATH
LOG=/data/data/com.termux/files/home/PocketStrike-AI/llama-8081.log
BIN=/data/data/com.termux/files/home/llama.cpp/build/bin/llama-server
MODEL=/data/data/com.termux/files/home/models/pocket-qwen3-1.7b-abliterated-q3km.gguf
# kill old 8081 server if any (avoid matching self)
pkill -f '[l]lama-server.*8081' 2>/dev/null
sleep 1
rm -f "$LOG"
# start detached
setsid "$BIN" -m "$MODEL" --host 127.0.0.1 --port 8081 -a pocket-qwen3 --ctx-size 2048 -t 4 > "$LOG" 2>&1 < /dev/null &
echo "STARTED $!"
sleep 2
echo "---LOG HEAD---"
head -n 60 "$LOG" 2>&1
