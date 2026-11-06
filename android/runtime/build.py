#!/usr/bin/env python3
"""Build a Linux userspace without executing target-architecture programs.

Independently implemented; design reference and input provenance are documented
in dependencies.json. All downloaded inputs must match their committed digest.
The ZIP contains only ordinary files/directories. Links are explicit metadata,
created last by the Android installer, so extraction cannot follow an archive's
symlinks into the host filesystem.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import struct
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
PREFIX = "data/data/com.termux/files/usr/"
MACHINE = 183
EPOCH = (2026, 1, 1, 0, 0, 0)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def configured_daemon(repo: Path) -> Path:
    metadata = json.loads(subprocess.check_output(
        ["cargo", "metadata", "--no-deps", "--locked", "--format-version", "1"],
        cwd=repo, text=True,
    ))
    return Path(metadata["target_directory"]) / "aarch64-unknown-linux-musl/release/atomcode-daemon"


def fetch(item: dict, cache: Path) -> Path:
    path = cache / item["name"]
    if path.is_file() and file_digest(path) == item["sha256"]:
        return path
    cache.mkdir(parents=True, exist_ok=True)
    # A failed download never becomes a cache hit, and concurrent builders never
    # share a partial filename.
    fd, name = tempfile.mkstemp(prefix=path.name + ".", suffix=".part", dir=cache)
    try:
        with os.fdopen(fd, "wb") as target:
            print("fetch", item["name"], flush=True)
            with urllib.request.urlopen(item["url"], timeout=90) as source:
                while block := source.read(1024 * 1024):
                    target.write(block)
        partial = Path(name)
        actual = file_digest(partial)
        if actual != item["sha256"]:
            raise ValueError(
                "SHA-256 mismatch: " + item["name"]
                + " (expected " + item["sha256"] + ", got " + actual
                + ", bytes " + str(partial.stat().st_size) + ")"
            )
        os.replace(partial, path)
    finally:
        Path(name).unlink(missing_ok=True)
    return path


def member_path(name: str) -> str:
    while name.startswith("./"):
        name = name[2:]
    name = name.rstrip("/")
    if not name or name == ".":
        return ""
    if name.startswith("/") or "\\" in name or "\x00" in name:
        raise ValueError("unsafe archive path: " + repr(name))
    if any(p in ("..", "") for p in name.split("/")):
        raise ValueError("unsafe archive path: " + repr(name))
    return str(PurePosixPath(name))


def link_target(path: str, target: str) -> str:
    if not target or "\x00" in target or "\\" in target:
        raise ValueError("unsafe link target")
    # Absolute Linux links become relative links within the extracted root.
    resolved = (target.lstrip("/") if target.startswith("/") else
                posixpath.join(posixpath.dirname(path), target))
    resolved = posixpath.normpath(resolved)
    if resolved == ".." or resolved.startswith("../"):
        raise ValueError("link escapes root: " + path)
    return posixpath.relpath(resolved, posixpath.dirname(path) or ".")


def deb_data(path: Path) -> bytes:
    data = path.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        raise ValueError("not a Debian archive")
    offset = 8
    while offset < len(data):
        header = data[offset:offset + 60]
        if len(header) != 60 or header[58:60] != b"`\n":
            raise ValueError("invalid ar header")
        size = int(header[48:58])
        name = header[:16].decode("ascii").strip().rstrip("/")
        payload = data[offset + 60:offset + 60 + size]
        if len(payload) != size:
            raise ValueError("truncated Debian archive")
        if name.startswith("data.tar"):
            return payload
        offset += 60 + size + size % 2
    raise ValueError("Debian archive has no data member")


def read_entries(payload: bytes) -> dict:
    if payload.startswith(b"\x28\xb5\x2f\xfd"):
        try:
            import zstandard
        except ImportError as error:
            raise RuntimeError("Install android/runtime/requirements.txt for zstd-compressed Debian packages") from error
        with zstandard.ZstdDecompressor().stream_reader(io.BytesIO(payload)) as source:
            payload = source.read()
    entries = {}
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:*") as archive:
        for member in archive:
            path = member_path(member.name)
            if not path:
                continue
            if member.isfile():
                source = archive.extractfile(member)
                assert source is not None
                body = source.read()
                if len(body) != member.size:
                    raise ValueError("truncated file: " + path)
                entries[path] = {"kind": "file", "mode": member.mode & 0o777, "body": body}
            elif member.isdir():
                entries[path] = {"kind": "dir", "mode": 0o755}
            elif member.issym():
                entries[path] = {"kind": "link", "mode": 0o777,
                                 "target": link_target(path, member.linkname)}
            elif member.islnk():
                entries[path] = {"kind": "hardlink", "target": member_path(member.linkname)}
            # Device nodes, sockets and FIFOs cannot be installed by an app.
    return entries


def elf_info(data: bytes) -> dict:
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("expected little-endian ELF64")
    machine = struct.unpack_from("<H", data, 18)[0]
    if machine != MACHINE:
        raise ValueError(f"ELF machine {machine}, expected ARM64 ({MACHINE})")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phcount = struct.unpack_from("<HH", data, 54)
    aligns, interpreter = [], None
    for i in range(phcount):
        row = struct.unpack_from("<IIQQQQQQ", data, phoff + phsize * i)
        if row[0] == 1:
            aligns.append(row[7])
        elif row[0] == 3:
            interpreter = data[row[2]:row[2] + row[5]].rstrip(b"\0").decode()
    return {"machine": machine, "minLoadAlign": min(aligns, default=0),
            "interpreter": interpreter}


def rename_talloc(data: bytes) -> bytes:
    """Change only the exact dynamic string; preserve binary layout/offsets."""
    elf_info(data)
    offset = struct.unpack_from("<Q", data, 40)[0]
    size, count, names_index = struct.unpack_from("<HHH", data, 58)
    rows = [struct.unpack_from("<IIQQQQIIQQ", data, offset + i * size) for i in range(count)]
    names_row = rows[names_index]
    names = data[names_row[4]:names_row[4] + names_row[5]]
    dynstr = next(r for r in rows if names[r[0]:].split(b"\0", 1)[0] == b".dynstr")
    strings = data[dynstr[4]:dynstr[4] + dynstr[5]]
    old, new = b"libtalloc.so.2\0", b"libtalloc.so\0"
    positions = []
    cursor = 0
    while cursor < len(strings):
        end = strings.find(b"\0", cursor)
        if end < 0:
            break
        if strings[cursor:end + 1] == old:
            positions.append(cursor)
        cursor = end + 1
    if len(positions) != 1:
        raise ValueError("expected exactly one talloc dynamic string")
    index = positions[0]
    # Reject dynamic entries pointing inside the replacement's tail.
    for row in rows:
        if row[1] == 6:  # SHT_DYNAMIC
            for pos in range(row[4], row[4] + row[5], row[9] or 16):
                tag, value = struct.unpack_from("<qQ", data, pos)
                if tag in (1, 14, 15, 29) and index < value < index + len(old):
                    raise ValueError("dynamic string has interior references")
    start = dynstr[4] + index
    return data[:start] + new.ljust(len(old), b"\0") + data[start + len(old):]


def resolve_files(entries: dict) -> None:
    def resolve(path: str, seen: set) -> dict:
        if path in seen or path not in entries:
            raise ValueError("unresolved hardlink: " + path)
        value = entries[path]
        if value["kind"] == "hardlink":
            value = resolve(value["target"], seen | {path})
        if value["kind"] != "file":
            raise ValueError("hardlink must resolve to a file: " + path)
        return value.copy()
    for path, value in list(entries.items()):
        if value["kind"] == "hardlink":
            entries[path] = resolve(path, set())
    for path in entries:
        for parent in PurePosixPath(path).parents:
            key = str(parent)
            if key != "." and key in entries and entries[key]["kind"] != "dir":
                raise ValueError("archive entry below link/non-directory: " + path)


def write_payload(entries: dict, destination: Path) -> dict:
    resolve_files(entries)
    links, modes, expanded, files = {}, {}, 0, 0
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED,
                         compresslevel=6, allowZip64=True) as archive:
        for path, entry in sorted(entries.items()):
            if entry["kind"] == "link":
                links[path] = entry["target"]
                continue
            is_dir = entry["kind"] == "dir"
            info = zipfile.ZipInfo(path + ("/" if is_dir else ""), EPOCH)
            mode = entry["mode"] | (0o040000 if is_dir else 0o100000)
            info.external_attr = mode << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, b"" if is_dir else entry["body"])
            if not is_dir:
                expanded += len(entry["body"])
                files += 1
                if entry["mode"] & 0o111:
                    modes[path] = entry["mode"]
        metadata = {"schema": 1, "links": links, "executables": modes,
                    "uncompressedBytes": expanded, "files": files}
        info = zipfile.ZipInfo(".atomcode-rootfs.json", EPOCH)
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(info, json.dumps(metadata, sort_keys=True).encode())
    return metadata


def assemble(inputs: list, paths: list, daemon: Path, out: Path) -> dict:
    by_name = {item["name"]: path for item, path in zip(inputs, paths)}
    entries = {}
    native = {}
    for item, path in zip(inputs, paths):
        if item["name"].startswith("ubuntu-base"):
            entries.update(read_entries(path.read_bytes()))
        elif path.suffix == ".deb":
            parsed = read_entries(deb_data(path))
            if item["name"].startswith(("proot_", "libtalloc_", "libandroid-shmem_")):
                native.update(parsed)
            else:
                entries.update(parsed)
    binary = daemon.read_bytes()
    elf_info(binary)
    # rust-embed records the asset filenames. Fingerprinted JS/CSS names reject
    # a binary built before this frontend or without an embedded frontend.
    webui_assets = sorted((REPO / "webui/dist/assets").glob("*"))
    code_assets = [p for p in webui_assets if p.suffix in (".js", ".css")]
    if not code_assets or any(p.name.encode() not in binary for p in code_assets):
        raise ValueError("daemon does not embed this WebUI build; rebuild the daemon")
    entries["usr/local/bin/atomcode-daemon"] = {"kind": "file", "mode": 0o755, "body": binary}
    # Runtime-generated or host-bind locations must be directories, not base-image links.
    for name in ("root", "tmp", "dev", "proc", "sys", "run", "run/atomcode", "dev/shm"):
        entries[name] = {"kind": "dir", "mode": 0o755}
    entries.pop("etc/resolv.conf", None)
    entries["etc/resolv.conf"] = {"kind": "file", "mode": 0o644, "body": b"# Written from Android network DNS at startup\n"}
    # Debian postinst normally creates this file; no foreign program is executed here.
    certs = [e["body"] for p, e in sorted(entries.items()) if
             p.startswith("usr/share/ca-certificates/") and p.endswith(".crt") and e["kind"] == "file"]
    if not certs:
        raise ValueError("no CA trust store in runtime inputs")
    entries["etc/ssl/certs/ca-certificates.crt"] = {"kind": "file", "mode": 0o644,
                                                  "body": b"\n".join(certs)}
    assets = out / "assets/runtime"
    jni = out / "jniLibs/arm64-v8a"
    assets.mkdir(parents=True, exist_ok=True)
    jni.mkdir(parents=True, exist_ok=True)
    native_specs = {
        "libproot.so": ("bin/proot", True),
        "libproot-loader.so": ("libexec/proot/loader", False),
        "libtalloc.so": ("lib/libtalloc.so.2.5.0", True),
        "libandroid-shmem.so": ("lib/libandroid-shmem.so", False),
    }
    audit = {}
    for name, (source, patch) in native_specs.items():
        data = native[PREFIX + source]["body"]
        original = digest(data)
        if patch:
            data = rename_talloc(data)
        (jni / name).write_bytes(data)
        (jni / name).chmod(0o755)
        audit[name] = dict(elf_info(data), sha256=digest(data), bytes=len(data),
                           originalSha256=original, patch="libtalloc.so.2 -> libtalloc.so" if patch else None)
    licenses = assets / "licenses"
    licenses.mkdir(exist_ok=True)
    source_key = next(name for name in by_name if name.startswith("proot-v") and name.endswith(".zip"))
    with zipfile.ZipFile(by_name[source_key]) as source:
        name = next(n for n in source.namelist() if n.endswith("/COPYING") and n.count("/") == 1)
        (licenses / "proot-COPYING.txt").write_bytes(source.read(name))
    (licenses / "libandroid-shmem-copyright.txt").write_bytes(native[PREFIX + "share/doc/libandroid-shmem/copyright"]["body"])
    header = native[PREFIX + "include/talloc.h"]["body"]
    (licenses / "libtalloc-notice.txt").write_bytes(header.split(b"*/", 1)[0] + b"*/\n")
    # Retain per-package copyright and all base-image license files.
    (licenses / "atomcode-LICENSE.txt").write_bytes((REPO / "LICENSE").read_bytes())
    for name in ("GPL-3", "LGPL-3"):
        (licenses / (name + ".txt")).write_bytes(entries["usr/share/common-licenses/" + name]["body"])
    (licenses / "dependency-provenance.json").write_text(json.dumps(inputs, indent=2) + "\n")
    payload = assets / "rootfs.zip"
    metadata = write_payload(entries, payload)
    elfs = {}
    for name, entry in entries.items():
        if entry["kind"] == "file" and entry["body"].startswith(b"\x7fELF"):
            elfs[name] = elf_info(entry["body"])
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip()
    provenance = {"sourceRevision": revision, "daemonSha256": digest(binary),
                  "recipeSha256": file_digest(Path(__file__)), "dependenciesSha256": file_digest(HERE / "dependencies.json"),
                  "webuiLockSha256": file_digest(REPO / "webui/package-lock.json"),
                  "cargoLockSha256": file_digest(REPO / "Cargo.lock"),
                  "rustVersion": subprocess.check_output(["rustc", "--version"], text=True).strip(),
                  "sourceDateEpoch": os.environ.get("SOURCE_DATE_EPOCH"),
                  "sourceDiffSha256": digest(subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=REPO))}
    identity = digest(json.dumps(provenance, sort_keys=True).encode())[:16]
    manifest = {"schema": 1, "abi": "arm64-v8a", "runtimeVersion": "atomcode-" + identity,
                "rootfs": "rootfs.zip", "rootfsBytes": payload.stat().st_size,
                "rootfsSha256": file_digest(payload), "uncompressedBytes": metadata["uncompressedBytes"],
                "files": metadata["files"], "nativeLibraries": audit, "build": provenance}
    (assets / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n")
    (out / "build-report.json").write_text(json.dumps({"manifest": manifest, "linuxElf": elfs,
                                                       "deviceValidated": False}, indent=2) + "\n")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fetch-only", action="store_true")
    parser.add_argument("--daemon", type=Path,
                        help="daemon artifact; defaults to Cargo's configured target directory")
    parser.add_argument("--out", type=Path, default=HERE / "out/arm64-v8a")
    args = parser.parse_args()
    inputs = json.loads((HERE / "dependencies.json").read_text())["inputs"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        paths = list(pool.map(lambda item: fetch(item, HERE / "cache"), inputs))
    if args.fetch_only:
        print("verified", len(paths), "runtime inputs")
        return
    if not (REPO / "webui/dist/index.html").is_file():
        raise ValueError("build the WebUI before building the daemon and rootfs")
    daemon = args.daemon if args.daemon is not None else configured_daemon(REPO)
    manifest = assemble(inputs, paths, daemon, args.out)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
