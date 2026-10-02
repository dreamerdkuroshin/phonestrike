#!/data/data/com.termux/files/usr/bin/bash
export HOME=/data/data/com.termux/files/home
cd /data/data/com.termux/files/home/PocketStrike-AI || exit 1
# kill old server (avoid self-match)
pkill -f '[s]erver\.py' 2>/dev/null
sleep 2
rm -f pocketstrike-5000.log
setsid /data/data/com.termux/files/usr/bin/python server.py > pocketstrike-5000.log 2>&1 < /dev/null &
echo "STARTED $!"
sleep 3
echo "---LOG---"
head -n 40 pocketstrike-5000.log 2>&1
echo "---CURL /---"
/data/data/com.termux/files/usr/bin/curl -s -m 5 -o /dev/null -w "HTTP:%{http_code}\n" http://127.0.0.1:5000/ 2>&1
