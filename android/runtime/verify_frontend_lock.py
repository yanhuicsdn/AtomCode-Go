#!/usr/bin/env python3
"""Offline artifact-pin preflight; does not fetch or authenticate registry content."""

import argparse
import base64
import binascii
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit


DEFAULT_LOCK = Path(__file__).resolve().parents[2] / "webui" / "package-lock.json"
REGISTRY_HOSTS = {"registry.npmjs.org", "registry.npmmirror.com"}


class LockError(ValueError):
    """A controlled diagnostic that never includes an artifact URL or digest."""


def package_path(path):
    if not isinstance(path, str) or not path.isascii():
        raise LockError("invalid package lock path")
    pieces = path.split("/")
    while pieces:
        if len(pieces) < 2 or pieces.pop(0) != "node_modules":
            raise LockError("invalid package lock path")
        name = pieces.pop(0)
        if name.startswith("@"):
            if not re.fullmatch(r"@[A-Za-z0-9_~.-]+", name) or not pieces:
                raise LockError("invalid package lock path")
            name = pieces.pop(0)
        if name in {".", ".."} or not re.fullmatch(r"[A-Za-z0-9_~.-]+", name):
            raise LockError("invalid package lock path")
    return path


def artifact_pin(path, entry):
    url = entry.get("resolved")
    if (not isinstance(url, str) or not url or not url.isascii()
            or any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in url) or "\\" in url):
        raise LockError(f"{path}: missing or invalid artifact URL")
    try:
        parsed = urlsplit(url)
        allowed = (parsed.scheme == "https" and parsed.hostname in REGISTRY_HOSTS
                   and parsed.username is None and parsed.password is None
                   and parsed.port in {None, 443} and parsed.path.startswith("/")
                   and bool(parsed.path.strip("/")) and not parsed.query and not parsed.fragment)
    except ValueError:
        allowed = False
    if not allowed:
        raise LockError(f"{path}: artifact URL must use an allowed HTTPS registry without credentials, query or fragment")
    integrity = entry.get("integrity")
    if not isinstance(integrity, str) or not integrity.startswith("sha512-"):
        raise LockError(f"{path}: missing SHA-512 artifact integrity")
    encoded = integrity[len("sha512-"):]
    try:
        digest = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError):
        digest = b""
    if len(digest) != 64 or base64.b64encode(digest).decode("ascii") != encoded:
        raise LockError(f"{path}: artifact integrity must be canonical SHA-512 SRI (64 bytes)")


def verify(lock):
    if not isinstance(lock, dict) or lock.get("lockfileVersion") not in (2, 3):
        raise LockError("expected an npm package lock with lockfileVersion 2 or 3")
    packages = lock.get("packages")
    if not isinstance(packages, dict) or not isinstance(packages.get(""), dict) or len(packages) < 2:
        raise LockError("package lock must contain a root and installed package entries")
    entries = {}
    bundled = []
    for raw_path, entry in packages.items():
        if raw_path == "":
            continue
        path = package_path(raw_path)
        if not isinstance(entry, dict) or ("inBundle" in entry and not isinstance(entry["inBundle"], bool)):
            raise LockError(f"{path}: invalid package entry or inBundle marker")
        entries[path] = entry
        if entry.get("inBundle") is True:
            if "resolved" in entry or "integrity" in entry:
                raise LockError(f"{path}: bundled package must use its parent artifact pin")
            bundled.append(path)
        else:
            artifact_pin(path, entry)
    for path in bundled:
        owner = path
        while True:
            if "/node_modules/" not in owner:
                raise LockError(f"{path}: bundled package has no pinned parent artifact")
            owner = owner.rsplit("/node_modules/", 1)[0]
            parent = entries.get(owner)
            if parent is None:
                raise LockError(f"{path}: bundled package parent is absent from the lock")
            if parent.get("inBundle") is not True:
                # Every non-bundled entry was already checked above.
                break
    return {"entries": len(entries), "pinned": len(entries) - len(bundled), "bundled": len(bundled)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK, help="package-lock.json to check offline")
    args = parser.parse_args(argv)
    try:
        with args.lock.open(encoding="utf-8") as source:
            lock = json.load(source)
        counts = verify(lock)
    except (OSError, UnicodeError, json.JSONDecodeError):
        print("Frontend lock preflight failed: cannot read a valid JSON lock", file=sys.stderr)
        return 1
    except LockError as error:
        print(f"Frontend lock preflight failed: {error}", file=sys.stderr)
        return 1
    print("Verified frontend lock: {entries} entries ({pinned} pinned, {bundled} bundled)".format(**counts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
