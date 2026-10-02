"""Shell execution with the high-risk blocklist. Everything that runs a
subprocess goes through run_shell_command — the LLM never builds raw shell."""
import re
import subprocess

# Spec 60 — shell safety: catastrophic commands are refused outright, not
# passed to the LLM's judgment. Workspace-scoped rm is allowed for dev flows.
RISKY_RE = re.compile(
    r"\brm\s+(-[a-z]+\s+)*(?:/|~|\$HOME|(?<![\w./-])\*)(?:\s|$)"
    r"|\bmkfs(?:\.\w+)?\b"
    r"|\bdd\s+[^|;&]*\bof=/dev/"
    r"|:\(\)\s*\{"
    r"|\bchmod\s+-R\s+777\s+(?:/|~)"
    r"|>\s*/dev/sd",
    re.I,
)
SAFE_WORKSPACE = re.compile(r"PocketStrike-AI|my-automator|/tmp/", re.I)


def run_shell_command(command, timeout=60):
    """Execute a shell command and return output."""
    if RISKY_RE.search(command) and not SAFE_WORKSPACE.search(command):
        return ("BLOCKED high-risk command (shell safety): " + command[:120] +
                ". Not running it — if you really mean it, run it manually in Termux.")
    try:
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=timeout,
            executable="/data/data/com.termux/files/usr/bin/bash"
        )
        if result.stdout:
            return result.stdout.strip()
        elif result.stderr:
            return f"⚠️ Error: {result.stderr.strip()}"
        else:
            return "✅ Command executed (no output)"
    except subprocess.TimeoutExpired:
        return "⏰ Command timed out after 60 seconds"
    except Exception as e:
        return f"❌ Error: {str(e)}"
