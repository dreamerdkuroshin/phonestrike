"""Threaded-server stress: 1 slow LLM stream + ~15 concurrent instant
requests. Proves instant commands stay sub-second while inference holds
a worker. Run ON the phone:  python stress_test.py"""
import json
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:5000"
ROUNDS = 12
WORKERS = 15

rec = {"health": [], "chat": [], "voice": [], "cfg_write": [], "cfg_read": []}
errs = {"count": 0, "zero": 0, "non200": 0}
rec_lock = threading.Lock()
stream_info = {}


def req(method, path, payload=None, timeout=120):
    data = json.dumps(payload).encode() if payload is not None else None
    r = urllib.request.Request(BASE + path, data=data,
                               headers={"Content-Type": "application/json"},
                               method=method)
    t0 = time.time()
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            body = resp.read(300000)
            return time.time() - t0, resp.status, body
    except Exception as e:
        dt = time.time() - t0
        with rec_lock:
            errs["count"] += 1
            if "urlopen error" in str(e) or "timed out" in str(e):
                errs["zero"] += 1
        return dt, 0, str(e).encode()


def note(key, dt, status):
    with rec_lock:
        rec[key].append(dt)
        if status != 200:
            errs["non200"] += 1


def llm_stream():
    """Hold one worker + llama inference with a long story. Slow-consume."""
    payload = {"messages": [{"role": "user",
                             "content": "Write a long adventure story, at least 600 words."}]}
    r = urllib.request.Request(BASE + "/api/chat", data=json.dumps(payload).encode(),
                               headers={"Content-Type": "application/json"})
    t0 = time.time()
    first = None
    n = 0
    try:
        with urllib.request.urlopen(r, timeout=300) as resp:
            for line in resp:
                if first is None:
                    first = time.time() - t0
                n += 1
                if time.time() - t0 > 100:
                    break
                time.sleep(0.05)  # slow consumer holds the worker
    except Exception as e:
        stream_info["error"] = str(e)[:100]
    stream_info["first_token_s"] = round(first or -1, 2)
    stream_info["chunks"] = n
    stream_info["held_s"] = round(time.time() - t0, 1)


def worker(wid, model):
    for _ in range(ROUNDS):
        dt, st, _ = req("GET", "/api/health", timeout=30)
        note("health", dt, st)
        dt, st, _ = req("POST", "/chat", {"message": "battery status"}, timeout=30)
        note("chat", dt, st)
        dt, st, _ = req("POST", "/api/voice/command", {"text": "what time is it"},
                        timeout=30)
        note("voice", dt, st)
        # lock exercise: same-value model switch + config read (no effective change)
        dt, st, _ = req("POST", "/api/chat", {"messages": [
            {"role": "user", "content": "switch to " + model}]}, timeout=60)
        note("cfg_write", dt, st)
        dt, st, _ = req("GET", "/config", timeout=30)
        note("cfg_read", dt, st)


def pct(xs, p):
    if not xs:
        return -1
    s = sorted(xs)
    return round(s[min(len(s) - 1, int(len(s) * p / 100))], 3)


def main():
    # current model (hammer switches back to the SAME value — no effective change)
    _, _, body = req("GET", "/config", timeout=15)
    try:
        model = json.loads(body).get("openai", {}).get("model", "qoder/qoder")
    except Exception:
        model = "qoder/qoder"
    print(f"model under hammer: {model}", flush=True)

    st = threading.Thread(target=llm_stream, daemon=True)
    st.start()
    time.sleep(5)  # let inference start and occupy its worker
    print("stream started; firing workers...", flush=True)

    ths = [threading.Thread(target=worker, args=(i, model)) for i in range(WORKERS)]
    t0 = time.time()
    for t in ths:
        t.start()
    for t in ths:
        t.join()
    wall = round(time.time() - t0, 1)
    st.join(timeout=60)

    total = sum(len(v) for v in rec.values())
    print(f"\n=== {total} requests in {wall}s wall ===")
    for k, v in rec.items():
        print(f"{k:10s} n={len(v):3d} p50={pct(v, 50)}s max={round(max(v), 3) if v else -1}s")
    print(f"errors={errs['count']} conn-fail={errs['zero']} non200={errs['non200']}")
    print(f"stream: first_token={stream_info.get('first_token_s')}s "
          f"chunks={stream_info.get('chunks')} held={stream_info.get('held_s')}s "
          f"{stream_info.get('error', '')}")
    inst = rec["health"] + rec["chat"] + rec["voice"]
    slow = [x for x in inst if x >= 1.0]
    print(f"instant-sub-second: {len(inst) - len(slow)}/{len(inst)} "
          f"({'PASS' if not slow and not errs['count'] else 'FAIL'})")


if __name__ == "__main__":
    main()
