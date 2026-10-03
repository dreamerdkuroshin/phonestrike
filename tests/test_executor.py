"""Executor tests on local fixture repos (file:// clone, real pytest)."""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

from strike import executor


def _git(cmd, cwd, **kw):
    env = dict(os.environ, GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t",
               GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@t")
    return subprocess.run(cmd, cwd=cwd, capture_output=True, env={**env, **kw})


def _fixture_repo(path, files):
    src = os.path.join(path, "src")
    os.makedirs(src)
    for name, content in files.items():
        with open(os.path.join(src, name), "w") as f:
            f.write(content)
    _git(["git", "init", "-q"], src)
    _git(["git", "add", "."], src)
    _git(["git", "-c", "user.email=t@t", "-c", "user.name=t",
          "commit", "-qm", "init"], src)
    return src


def _wait(tid, timeout=180):
    for _ in range(int(timeout * 2)):
        t = executor.get_task(tid)
        if t["state"] in executor.TERMINAL:
            return t
        time.sleep(0.5)
    raise TimeoutError(executor.get_task(tid))


def test_clone_build_pass(tmp_path):
    src = _fixture_repo(str(tmp_path), {"test_ok.py": "def test_x():\n    assert True\n"})
    tid = executor.create_task(repo="file://" + src, build="pytest", fix=False)
    t = _wait(tid)
    assert t["state"] == "COMPLETED", t["log"][-3:]
    assert any(s["step"] == "clone" and s["ok"] for s in t["steps"])


def test_fail_fix_rebuild(tmp_path):
    src = _fixture_repo(str(tmp_path), {"test_ok.py": "def test_x():\n    assert False\n"})

    def fix(t, out):
        return {"test_ok.py": "def test_x():\n    assert True\n"}
    tid = executor.create_task(repo="file://" + src, build="pytest", fix_fn=fix)
    t = _wait(tid)
    assert t["state"] == "COMPLETED", t["result"]
    assert sum(1 for s in t["steps"] if s["step"].startswith("build#")) == 2


def test_fail_no_fix(tmp_path):
    src = _fixture_repo(str(tmp_path), {"test_ok.py": "def test_x():\n    assert False\n"})
    tid = executor.create_task(repo="file://" + src, build="pytest", fix=False)
    t = _wait(tid)
    assert t["state"] == "FAILED"


def test_jail():
    t = {"id": "x"}
    with pytest.raises(ValueError):
        executor.cap_fs_write(t, "../../evil", "x")


def test_detect_build(tmp_path):
    assert executor.detect_build(str(tmp_path)) is None
    open(os.path.join(str(tmp_path), "requirements.txt"), "w").close()
    assert executor.detect_build(str(tmp_path)) == "pytest"


def test_cancel(tmp_path):
    src = _fixture_repo(str(tmp_path),
                        {"test_slow.py": "import time\ndef test_s():\n    time.sleep(60)\n"})
    tid = executor.create_task(repo="file://" + src, build="pytest", fix=False)
    time.sleep(8)  # let clone finish + pytest start
    assert executor.cancel_task(tid) is True
    t = executor.get_task(tid)
    assert t["state"] == "CANCELLED"


def test_unknown_task():
    assert executor.get_task("nope") is None
    assert executor.cancel_task("nope") is False


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-q"]))
