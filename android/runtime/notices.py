#!/usr/bin/env python3
"""Copy existing dependency notice texts locally; not a legal/source-delivery certification.

Uses installed npm lock entries (including dev dependencies) and the daemon's
normal/build reachable Cargo metadata graph. Cargo metadata runs locked/offline,
without building or downloading. Both selections are deliberately supersets,
not proof of which dependencies survived linking or frontend tree-shaking.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess

REPO = Path(__file__).resolve().parents[2]
TARGET = "aarch64-unknown-linux-musl"
LIMIT = 4 * 1024 * 1024
NOTICE = re.compile(r"^(?:licen[cs]e|copying|copyright|notice|unlicense|third[-_ ]party[-_ ](?:notices?|licenses?))(?:$|[._ -])", re.I)
NOTICE_DIRS = {"licenses", "licences", "notices", "license", "licence"}
EXCLUDED_DIRS = {"node_modules", ".git", "target", "__pycache__"}
SENSITIVE_SUFFIXES = {".key", ".pem", ".p12", ".pfx", ".jks", ".keystore", ".crt", ".cer"}
NON_NOTICE_SUFFIXES = {".rs", ".py", ".c", ".h", ".cc", ".cpp", ".js", ".ts", ".css", ".json", ".toml", ".yaml", ".yml", ".gz", ".zip"}


def relative_parts(value: str) -> tuple[str, ...]:
    path = PurePosixPath(value)
    if not value or path.is_absolute() or "\\" in value or "\0" in value or any(p in (".", "..") for p in value.split("/")):
        raise ValueError("unsafe_relative_path")
    return path.parts


def notice_name(relative: str) -> bool:
    path = PurePosixPath(relative)
    if path.name.startswith(".") or path.suffix.lower() in SENSITIVE_SUFFIXES | NON_NOTICE_SUFFIXES:
        return False
    return bool(NOTICE.match(path.name)) or any(p.lower() in NOTICE_DIRS for p in path.parts[:-1])


def read_anchored(root: Path, relative: str) -> bytes:
    """Open every component relative to the package FD, never follow links."""
    parts = relative_parts(relative)
    if not hasattr(os, "O_NOFOLLOW") or not hasattr(os, "O_DIRECTORY"):
        raise ValueError("safe_nofollow_open_unavailable")
    directory = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        with os.fdopen(descriptor, "rb") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or not 0 < info.st_size <= LIMIT:
                raise ValueError("non_regular_linked_empty_or_oversize_file")
            data = source.read(LIMIT + 1)
            if len(data) != info.st_size or b"\0" in data:
                raise ValueError("non_text_or_changed_file")
            return data
    finally:
        os.close(directory)


def notice_paths(root: Path) -> tuple[set[str], list[dict]]:
    candidates, problems = set(), []
    if root.is_symlink() or not root.is_dir():
        return candidates, [{"reason": "package_root_unavailable_or_symlink"}]
    def failed(_error):
        problems.append({"reason": "package_walk_failed"})
    for parent, directories, files in os.walk(root, followlinks=False, onerror=failed):
        for name in list(directories):
            path = Path(parent) / name
            if name in EXCLUDED_DIRS:
                directories.remove(name)
            elif path.is_symlink():
                directories.remove(name)
                if name.lower() in NOTICE_DIRS:
                    problems.append({"path": path.relative_to(root).as_posix(), "reason": "symlink_notice_directory_rejected"})
        for name in files:
            relative = (Path(parent) / name).relative_to(root).as_posix()
            if notice_name(relative):
                candidates.add(relative)
    return candidates, problems


def declared_license_path(root: Path, value: str) -> str:
    path = Path(value)
    if path.is_absolute():
        try:
            value = path.relative_to(root).as_posix()
        except ValueError:
            raise ValueError("declared_license_file_outside_package") from None
    relative_parts(value)
    if not notice_name(value):
        raise ValueError("declared_license_file_not_notice_named")
    return value


def package_notices(root: Path, declared: str | None = None) -> tuple[list[dict], list[dict], dict[str, bytes]]:
    candidates, problems = notice_paths(root)
    if declared:
        try:
            candidates.add(declared_license_path(root, declared))
        except ValueError as error:
            # Do not echo a rejected arbitrary path from metadata.
            problems.append({"reason": str(error)})
    records, contents = [], {}
    for relative in sorted(candidates):
        try:
            data = read_anchored(root, relative)
        except (OSError, ValueError):
            problems.append({"path": relative, "reason": "notice_not_read_safely"})
            continue
        digest = hashlib.sha256(data).hexdigest()
        contents[digest] = data
        records.append({"path": relative, "sha256": digest, "bytes": len(data), "copy": "texts/" + digest + ".txt"})
    if not records:
        problems.append({"reason": "no_notice_text_found_metadata_is_not_text"})
    return records, problems, contents


def cargo_packages(metadata: dict) -> list[dict]:
    packages = {p["id"]: p for p in metadata["packages"]}
    roots = [p for p in packages.values() if p["name"] == "atomcode-daemon" and p["id"] in metadata["workspace_members"]]
    if len(roots) != 1 or not metadata.get("resolve"):
        raise ValueError("daemon_resolve_graph_unavailable")
    nodes = {p["id"]: p for p in metadata["resolve"]["nodes"]}
    selected, pending = set(), [roots[0]["id"]]
    while pending:
        identity = pending.pop()
        if identity in selected:
            continue
        selected.add(identity)
        if identity not in nodes or identity not in packages:
            raise ValueError("incomplete_cargo_metadata_graph")
        for dependency in nodes[identity]["deps"]:
            if any(k["kind"] in (None, "normal", "build") for k in dependency["dep_kinds"]):
                pending.append(dependency["pkg"])
    return sorted((packages[p] for p in selected), key=lambda p: (p["name"], p["version"], p["id"]))


def previous_output_texts(output: Path, current: dict[str, bytes]) -> dict[str, int]:
    """Only the prior index can authorize deletion of an unused copied text."""
    if output.is_symlink() or (output.exists() and not output.is_dir()):
        raise ValueError("output_directory_symlink_or_type_conflict")
    if not output.exists():
        return {}
    if any(p.name not in {"index.json", "texts"} for p in output.iterdir()):
        raise ValueError("unknown_output_entry")
    previous = {}
    index = output / "index.json"
    if index.exists() or index.is_symlink():
        try:
            report = json.loads(read_anchored(output, "index.json"))
            if report["schema"] != 1 or report["kind"] != "dependency-notice-text-inventory":
                raise ValueError("wrong_previous_index")
            files = list(report["repositoryNotices"])
            for package in report["packages"]:
                files.extend(package["files"])
            for record in files:
                digest, size = record["sha256"], record["bytes"]
                if not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest) or record["copy"] != "texts/" + digest + ".txt":
                    raise ValueError("wrong_previous_text_reference")
                if not isinstance(size, int) or not 0 < size <= LIMIT or (digest in previous and previous[digest] != size):
                    raise ValueError("wrong_previous_text_size")
                previous[digest] = size
        except (KeyError, TypeError, OSError, ValueError) as error:
            raise ValueError("previous_notice_index_not_verifiable") from error
    directory = output / "texts"
    if directory.is_symlink() or (directory.exists() and not directory.is_dir()):
        raise ValueError("output_text_directory_symlink_or_type_conflict")
    if directory.exists():
        for path in directory.iterdir():
            if not re.fullmatch(r"[a-f0-9]{64}\.txt", path.name):
                raise ValueError("unknown_output_text_entry")
            digest = path.stem
            if digest not in previous and digest not in current:
                raise ValueError("unowned_output_text")
            try:
                data = read_anchored(output, "texts/" + path.name)
            except (OSError, ValueError) as error:
                raise ValueError("existing_output_text_not_verifiable") from error
            if hashlib.sha256(data).hexdigest() != digest or (digest in previous and len(data) != previous[digest]):
                raise ValueError("existing_output_text_changed")
    return previous


def generate(repo: Path, output: Path, metadata: dict, metadata_provided: bool = False) -> dict:
    repo = repo.resolve()
    records, contents, uninstalled = [], {}, []
    workspace = set(metadata["workspace_members"])
    for package in cargo_packages(metadata):
        root = Path(package["manifest_path"]).parent.resolve()
        files, problems, texts = package_notices(root, package.get("license_file"))
        contents.update(texts)
        source = package.get("source")
        records.append({"ecosystem": "cargo", "name": package["name"], "version": package["version"],
                        "sourceKind": "workspace" if package["id"] in workspace else (source.split("+", 1)[0] if source else "path"),
                        "sourceIdentitySha256": hashlib.sha256((source or (root.relative_to(repo).as_posix() if root.is_relative_to(repo) else str(root))).encode()).hexdigest(),
                        "declaredLicense": package.get("license"), "files": files, "problems": problems})
    frontend = repo / "webui"
    lock = json.loads((frontend / "package-lock.json").read_text())
    for relative, entry in sorted(lock["packages"].items()):
        if not relative:
            continue
        parts = relative_parts(relative)
        if parts[0] != "node_modules":
            raise ValueError("unsupported_npm_lock_path")
        root = frontend
        linked = False
        for part in parts:
            root /= part
            if root.is_symlink():
                linked = True
                break
        if linked:
            records.append({"ecosystem": "npm", "lockPath": relative, "name": relative,
                            "version": entry.get("version"), "files": [], "problems": [{"reason": "linked_package_not_followed"}]})
            continue
        if not root.is_dir():
            uninstalled.append({"lockPath": relative, "optional": bool(entry.get("optional")), "dev": bool(entry.get("dev"))})
            continue
        package = json.loads(read_anchored(root, "package.json"))
        files, problems, texts = package_notices(root)
        contents.update(texts)
        if package.get("version") != entry.get("version"):
            problems.append({"reason": "installed_version_differs_from_lock"})
        records.append({"ecosystem": "npm", "lockPath": relative, "name": package.get("name", relative),
                        "version": package.get("version"), "dev": bool(entry.get("dev")), "declaredLicense": package.get("license"),
                        "files": files, "problems": problems})
    # Shared repository texts are explicit, separate records. They do not erase
    # missing per-package texts or authorize arbitrary escaping license_file.
    shared, shared_problems = [], []
    for path in sorted(repo.iterdir()):
        if not notice_name(path.name):
            continue
        if path.is_symlink():
            shared_problems.append({"path": path.name, "reason": "repository_notice_symlink_rejected"})
            continue
        if path.is_dir():
            continue
        try:
            data = read_anchored(repo, path.name)
        except (OSError, ValueError):
            shared_problems.append({"path": path.name, "reason": "repository_notice_not_read_safely"})
            continue
        digest = hashlib.sha256(data).hexdigest()
        contents[digest] = data
        shared.append({"path": path.name, "sha256": digest, "bytes": len(data), "copy": "texts/" + digest + ".txt"})
    report = {"schema": 1, "kind": "dependency-notice-text-inventory",
              "completeCorrespondingSourceDelivered": False, "legalComplianceVerified": False, "shippedDependencySetProven": False,
              "scope": {"cargo": "daemon reachable normal/build edges; workspace feature-unified metadata superset, not final linked-code proof",
                        "cargoTargetFilter": None if metadata_provided else TARGET,
                        "cargoMetadataProvided": metadata_provided,
                        "npm": "installed package-lock entries including dev dependencies and fonts; not tree-shaken bundle membership",
                        "noticePolicy": "named notice files/notice directories only; no package-root escape or symlink traversal; metadata/SPDX/URLs are not notice texts"},
              "coverage": {"cargoPackages": sum(p["ecosystem"] == "cargo" for p in records),
                           "installedNpmPackages": sum(p["ecosystem"] == "npm" for p in records),
                           "packagesWithoutTexts": sum(not p["files"] for p in records), "uniqueTexts": len(contents)},
              "packages": records, "uninstalledNpmLockEntries": uninstalled,
              "repositoryNotices": shared, "repositoryProblems": shared_problems}
    previous = previous_output_texts(output, contents)
    output.mkdir(parents=True, exist_ok=True)
    directory = output / "texts"
    if directory.is_symlink():
        raise ValueError("output_text_directory_symlink_rejected")
    directory.mkdir(exist_ok=True)
    for digest, data in contents.items():
        target = directory / (digest + ".txt")
        if target.is_symlink():
            raise ValueError("output_text_symlink_rejected")
        target.write_bytes(data)
    # A reused output must not silently carry old texts into the next APK.
    # Remove only strict, content-verified references from this generator's old
    # index. Unknown files, symlinks or changed contents fail before any write.
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for digest in previous.keys() - contents.keys():
            name = digest + ".txt"
            if not (directory / name).exists():
                continue
            data = read_anchored(output, "texts/" + name)
            if hashlib.sha256(data).hexdigest() != digest:
                raise ValueError("stale_output_text_changed")
            os.unlink(name, dir_fd=descriptor)
    finally:
        os.close(descriptor)
    index = output / "index.json"
    if index.is_symlink():
        raise ValueError("output_index_symlink_rejected")
    index.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--cargo-metadata", type=Path)
    args = parser.parse_args()
    if args.cargo_metadata:
        metadata = json.loads(args.cargo_metadata.read_text())
    else:
        process = subprocess.run(["cargo", "metadata", "--locked", "--offline", "--format-version", "1", "--filter-platform", TARGET],
                                 cwd=args.repo, capture_output=True, text=True)
        if process.returncode:
            parser.error("Cargo metadata is unavailable in locked/offline mode; no download or build was attempted")
        metadata = json.loads(process.stdout)
    report = generate(args.repo, args.out, metadata, args.cargo_metadata is not None)
    print(json.dumps(report["coverage"], sort_keys=True))


if __name__ == "__main__":
    main()
