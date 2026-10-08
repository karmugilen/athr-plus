#!/usr/bin/env python3
"""Local commit/push guard. Prints paths/reasons, never matched secret values."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

STORE = Path.home() / ".local/share/atherpro-lab"
JWT = re.compile(rb"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}")
BEARER = re.compile(rb"(?i)\bbearer\s+[A-Za-z0-9_.~+/=-]{24,}")
ASSIGNMENT = re.compile(
    rb'''(?i)["']?(?:access_token|refresh_token|auth_token|ather_token|token|api[_-]?key|client_secret|secret_key)["']?\s*[:=]\s*["'][A-Za-z0-9_.~+/=-]{32,}["']'''
)
API_KEY = re.compile(
    rb"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,}|"
    rb"AIza[A-Za-z0-9_-]{35}|AKIA[A-Z0-9]{16})\b"
)
PRIVATE_KEY = re.compile(rb"-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----")
PRIVATE_NAME = re.compile(
    r"(?:^|/)(?:\.env(?:\..*)?|ather_session\.json|session\.json|pending-login\.json|"
    r"last-response\.json|monitor\.jsonl|charge-limit-state\.json|charge-limit\.lock|"
    r"[^/]*session-seed[^/]*\.json|atherpro-lab)(?:/|$)",
    re.I,
)


def git(*args, input=None):
    return subprocess.run(["git", *args], input=input, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=True).stdout


def known_secrets():
    values = []
    # Refuse to follow a session-file symlink.
    for name in ("session.json", "pending-login.json"):
        path = STORE / name
        if path.is_symlink():
            continue
        try:
            data = json.loads(path.read_text())
        except (OSError, ValueError):
            continue
        for key in ("token", "contact_no", "uuid"):
            if isinstance(data.get(key), str) and len(data[key]) >= 8:
                values.append(data[key].encode())
    return values


def reason(path, content, secrets):
    if path.lower().endswith((".keystore", ".jks", ".p12", ".pfx")):
        return "private signing/key container filename"
    if PRIVATE_NAME.search(path):
        return "private session/environment/capture filename"
    if str(STORE).encode() in content and path not in (
        "scripts/ather-lab.py", "scripts/secret-guard.py", "docs/LOCAL-API-LAB.md",
    ):
        return "reference to this machine's private storage"
    if any(value in content for value in secrets):
        return "matches a locally saved credential or account identifier"
    if JWT.search(content):
        return "JWT-like credential"
    if BEARER.search(content) or ASSIGNMENT.search(content):
        return "literal authentication credential"
    if API_KEY.search(content) or PRIVATE_KEY.search(content):
        return "API credential or private key"
    return None


def scan_tree(tree, secrets, seen):
    problems = []
    raw = git("ls-files", "--stage", "-z") if tree is None else git("ls-tree", "-r", "-z", tree)
    for entry in raw.split(b"\0"):
        if not entry:
            continue
        metadata, name = entry.split(b"\t", 1)
        parts = metadata.split()
        oid = parts[1] if tree is None else parts[2]
        mode = parts[0]
        path = os.fsdecode(name)
        identity = (path, oid)
        if identity in seen:
            continue
        seen.add(identity)
        if mode == b"160000":  # A submodule has its own repository/hooks.
            continue
        content = git("cat-file", "blob", oid.decode())
        failure = reason(path, content, secrets)
        if failure:
            problems.append((path, failure))
    return problems


def scan_push(lines, secrets):
    seen = set()
    problems = []
    for line in lines:
        _, local, _, remote = line.split()
        if set(local) == {"0"}:  # Branch/tag deletion.
            continue
        args = ["rev-list", local]
        if set(remote) != {"0"}:
            # If the remote base is unknown locally, scan all reachable commits.
            try:
                git("cat-file", "-e", remote)
            except subprocess.CalledProcessError:
                pass
            else:
                args.append("^" + remote)
        else:
            # New branches must include every ancestor, even commits pushed earlier.
            pass
        for commit in git(*args).decode().splitlines():
            problems.extend(scan_tree(commit, secrets, seen))
            message = git("show", "-s", "--format=%B", commit)
            failure = reason("commit-message", message, secrets)
            if failure:
                problems.append((f"commit {commit[:12]} message", failure))
    return problems


def install():
    # Hooks remain local Git metadata; installation does not commit or push anything.
    hooks_path = git("config", "--get", "core.hooksPath").decode().strip() if subprocess.run(
        ["git", "config", "--get", "core.hooksPath"], stdout=subprocess.DEVNULL,
    ).returncode == 0 else git("rev-parse", "--git-path", "hooks").decode().strip()
    hooks = Path(hooks_path).resolve()
    hooks.mkdir(parents=True, exist_ok=True)
    script = Path(__file__).resolve()
    interpreter = Path(sys.executable).absolute()
    import shlex
    marker = "# atherpro-local-secret-guard"
    for hook, mode in (("pre-commit", "staged"), ("pre-push", "push")):
        target = hooks / hook
        if target.exists() and marker not in target.read_text():
            raise RuntimeError(f"Existing {hook} hook: refusing to overwrite it.")
        target.write_text(f"#!/bin/sh\n{marker}\nexec {shlex.quote(str(interpreter))} "
                          f"{shlex.quote(str(script))} {mode}\n")
        target.chmod(0o755)
    print("Local pre-commit and pre-push secret guards installed.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("install", "staged", "push", "tree"))
    args = parser.parse_args()
    if args.mode == "install":
        install()
        return
    secrets = known_secrets()
    if args.mode == "push":
        problems = scan_push(sys.stdin, secrets)
    else:
        problems = scan_tree("HEAD" if args.mode == "tree" else None, secrets, set())
    if problems:
        print("Blocked: possible private data in Git. Remove it before committing/pushing.", file=sys.stderr)
        for path, failure in sorted(set(problems)):
            print(f"  {path}: {failure}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, OSError, ValueError, RuntimeError):
        print("Secret guard could not complete; blocking the operation.", file=sys.stderr)
        sys.exit(1)
