"""Autonomous clone -> inspect -> build -> fix -> rebuild executor.

No unrestricted shell: every action is a typed capability with timeout,
jailed working directory, captured output, cancellation and verification.
GOAL -> PLAN -> ACT -> OBSERVE -> VERIFY -> RECOVER -> COMPLETE.
"""
import os
import shutil
import subprocess
import threading
import time
import uuid

from . import llm as _llm
from .shell import SAFE_WORKSPACE, run_shell_command

# States: CREATED PLANNING RUNNING OBSERVING VERIFYING RECOVERING
#         PAUSED CANCELLED FAILED COMPLETED
TERMINAL = ("CANCELLED", "FAILED", "COMPLETED")

TASKS = {}
_lock = threading.Lock()


def _task_dir(task_id):
    from . import config
    d = os.path.join(config.WORKSPACE_DIR, "tasks", task_id)
    os.makedirs(d, exist_ok=True)
    return d


def _jailed(path, root):
    """Absolute path must stay inside root (no .., no symlinks out)."""
    real = os.path.realpath(os.path.abspath(path))
    if real != os.path.realpath(root) and not real.startswith(os.path.realpath(root) + os.sep):
        raise ValueError(f"path escapes workspace: {path}")
    return real


def _log(t, msg):
    t["log"].append(f"{time.strftime('%H:%M:%S')} {msg}")
    t["updated_at"] = time.time()


def _run(t, argv, cwd, timeout, step):
    """Run an argv list (never shell=True with LLM text). Returns rc."""
    _log(t, f"[{step}] $ {' '.join(argv)} (cwd={os.path.basename(cwd)})")
    try:
        p = subprocess.Popen(argv, cwd=cwd, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True)
    except FileNotFoundError as e:
        _log(t, f"[{step}] missing tool: {e}")
        return 127, str(e)
    t["proc"] = p
    out_lines = []
    try:
        start = time.time()
        while True:
            if t["cancel"].is_set():
                p.kill()
                _log(t, f"[{step}] cancelled")
                return -1, "\n".join(out_lines)
            if p.poll() is not None:
                break
            if time.time() - start > timeout:
                p.kill()
                _log(t, f"[{step}] timeout after {timeout}s")
                return 124, "\n".join(out_lines)
            time.sleep(0.5)
        rest = p.communicate()[0] or ""
        out_lines.append(rest)
    finally:
        t["proc"] = None
    out = "\n".join(out_lines)[-6000:]
    _log(t, f"[{step}] exit={p.returncode}\n{out[-1500:]}")
    return p.returncode, out


# ---------- typed capabilities ----------
def cap_git_clone(t, url):
    if not (url.startswith("https://") or url.startswith("http://") or url.startswith("file://")):
        return False, "only http(s)/file URLs"
    dest = os.path.join(_task_dir(t["id"]), "repo")
    if os.path.exists(dest):
        shutil.rmtree(dest, ignore_errors=True)
    return _ok(*_run(t, ["git", "clone", "--depth", "1", url, dest], _task_dir(t["id"]), 300, "clone"))


def cap_fs_list(t, rel="."):
    root = os.path.join(_task_dir(t["id"]), "repo")
    d = _jailed(os.path.join(root, rel), root)
    if not os.path.isdir(d):
        return False, "not a directory"
    return True, sorted(os.listdir(d))[:100]


def cap_fs_read(t, rel, limit=4000):
    root = os.path.join(_task_dir(t["id"]), "repo")
    f = _jailed(os.path.join(root, rel), root)
    if not os.path.isfile(f):
        return False, "not a file"
    with open(f, encoding="utf-8", errors="replace") as fh:
        return True, fh.read(limit)


def cap_fs_write(t, rel, content, limit=200_000):
    root = os.path.join(_task_dir(t["id"]), "repo")
    f = _jailed(os.path.join(root, rel), root)
    if len(content) > limit:
        return False, "content too large"
    os.makedirs(os.path.dirname(f) or root, exist_ok=True)
    with open(f, "w", encoding="utf-8") as fh:
        fh.write(content)
    return True, f"wrote {len(content)} bytes to {rel}"


def _ok(rc, out):
    return (rc == 0, out)


def detect_build(repo_dir):
    if os.path.exists(os.path.join(repo_dir, "settings.gradle")) or \
            os.path.exists(os.path.join(repo_dir, "settings.gradle.kts")) or \
            os.path.exists(os.path.join(repo_dir, "build.gradle")):
        return "gradle"
    for f in ("pytest.ini", "pyproject.toml", "requirements.txt", "setup.py"):
        if os.path.exists(os.path.join(repo_dir, f)):
            return "pytest"
    return None


def cap_build(t, kind, repo_dir, timeout=600):
    if kind == "gradle":
        gradlew = os.path.join(repo_dir, "gradlew")
        if os.path.isfile(gradlew):
            argv = ["sh", gradlew, "assembleDebug", "--no-daemon"]
        else:
            argv = ["gradle", "assembleDebug", "--no-daemon"]
        rc, out = _run(t, argv, repo_dir, timeout, "build-gradle")
        if rc == 0:
            apks = []
            for dp, _, fns in os.walk(repo_dir):
                apks += [os.path.join(dp, f) for f in fns if f.endswith(".apk")]
            return True, f"build ok; APKs: {apks[:5]}"
        return False, out
    if kind == "pytest":
        import sys as _sys
        rc, out = _run(t, [_sys.executable, "-m", "pytest", "-q"], repo_dir, timeout, "build-pytest")
        return (rc == 0), out
    return False, f"unknown build kind: {kind}"


def _llm_fix(t, build_output):
    """Ask the LLM for a minimal patch. Returns {path: content} or {}."""
    try:
        import json as _js
        resp = _llm.query_llm(
            "The build/test failed. Reply ONLY with JSON {\"path\": \"full file content\"} "
            "for each file to rewrite (max 2 files), fixing the failure. No other text.\n\n"
            f"FAILURE:\n{build_output[-3000:]}",
            system_prompt="You output only JSON file patches.", timeout=180)
        start, end = resp.find("{"), resp.rfind("}")
        if start < 0 or end <= start:
            return {}
        patch = _js.loads(resp[start:end + 1])
        if not isinstance(patch, dict):
            return {}
        return {k: v for k, v in list(patch.items())[:2] if isinstance(v, str)}
    except Exception as e:
        _log(t, f"[fix] LLM unavailable: {e}")
        return {}


# ---------- task lifecycle ----------
def create_task(repo=None, build="auto", fix=True, fix_fn=None):
    tid = uuid.uuid4().hex[:8]
    t = {"id": tid, "goal": f"clone {repo} + build", "repo": repo,
         "build": build, "fix": fix, "fix_fn": fix_fn,
         "state": "CREATED", "log": [], "steps": [], "result": None,
         "cancel": threading.Event(), "proc": None,
         "started_at": time.time(), "updated_at": time.time()}
    with _lock:
        TASKS[tid] = t
    th = threading.Thread(target=_drive, args=(t,), daemon=True)
    th.start()
    return tid


def get_task(tid):
    with _lock:
        t = TASKS.get(tid)
        if not t:
            return None
        return {k: v for k, v in t.items() if k not in ("cancel", "proc", "fix_fn")}


def cancel_task(tid):
    with _lock:
        t = TASKS.get(tid)
        if not t or t["state"] in TERMINAL:
            return False
        t["cancel"].set()
        t["state"] = "CANCELLED"
        t["updated_at"] = time.time()
        return True


def _drive(t):
    try:
        t["state"] = "PLANNING"
        repo_dir = os.path.join(_task_dir(t["id"]), "repo")
        if t["repo"]:
            t["state"] = "RUNNING"
            ok, out = cap_git_clone(t, t["repo"])
            t["steps"].append({"step": "clone", "ok": ok})
            if not ok:
                return _finish(t, "FAILED", f"clone failed: {out[-500:]}")
        kind = t["build"] if t["build"] != "auto" else detect_build(repo_dir)
        if not kind:
            files = cap_fs_list(t)[1] if os.path.isdir(repo_dir) else []
            return _finish(t, "FAILED", f"no recognized build system; top level: {files}")
        _log(t, f"detected build: {kind}")
        for attempt in (1, 2, 3):
            if t["cancel"].is_set():
                return _finish(t, "CANCELLED", "cancelled by user")
            t["state"] = "RUNNING"
            ok, out = cap_build(t, kind, repo_dir)
            t["steps"].append({"step": f"build#{attempt}", "ok": ok})
            if ok:
                t["state"] = "VERIFYING"
                return _finish(t, "COMPLETED", f"build ok ({kind}): {out[-500:]}")
            # RECOVER: LLM-proposed minimal patch, then rebuild
            if not t["fix"] or attempt >= 3:
                return _finish(t, "FAILED", f"build failed ({kind}): {out[-800:]}")
            t["state"] = "RECOVERING"
            patch = (t["fix_fn"] or _llm_fix)(t, out)
            if not patch:
                return _finish(t, "FAILED", f"build failed, no patch proposed: {out[-800:]}")
            for rel, content in patch.items():
                okw, msg = cap_fs_write(t, rel, content)
                _log(t, f"[fix] {msg}")
    except Exception as e:
        _finish(t, "FAILED", f"executor error: {e}")


def _finish(t, state, result):
    t["state"] = state
    t["result"] = (result or "")[:2000]
    t["updated_at"] = time.time()
    _log(t, f"[{state}] {t['result'][:300]}")
