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

    out = run_shell_checked(command, timeout)
    return out[1]


def run_shell_checked(command, timeout=60):
    """Same gate, but returns (returncode, output) so callers can VERIFY
    instead of claiming success (§69 NO FALSE SUCCESS). -1 = blocked/error."""
    if RISKY_RE.search(command) and not SAFE_WORKSPACE.search(command):
        return (-1, "BLOCKED high-risk command (shell safety): " + command[:120] +
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
        out = result.stdout.strip() if result.stdout else ""
        if result.returncode != 0 and result.stderr:
            out = (out + "\n" if out else "") + "⚠️ Error: " + result.stderr.strip()
        if not out:
            out = "✅ Command executed (no output)"
        return (result.returncode, out)
    except subprocess.TimeoutExpired:
        return (-1, "⏰ Command timed out after 60 seconds")
    except Exception as e:
        return (-1, f"❌ Error: {str(e)}")
