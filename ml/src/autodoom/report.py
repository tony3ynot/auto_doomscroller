"""Run records for result folders, so every committed number can be traced to a script and a commit."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path


def git_commit() -> str:
    """Short hash of HEAD, with ``-dirty`` when tracked files have uncommitted changes."""
    try:
        head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True,
                              check=True).stdout.strip()
        dirty = subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"], capture_output=True,
                               text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"
    return f"{head}-dirty" if dirty else head


def write_run(out: Path, script: str, export: Path, **details) -> dict:
    """Write ``run.json``: which script, commit and export produced the folder, plus settings."""
    run = {"script": script, "git_commit": git_commit(), "export": Path(export).name, **details}
    (out / "run.json").write_text(json.dumps(run, indent=2, default=str) + "\n")
    return run
