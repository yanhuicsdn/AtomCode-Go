#!/usr/bin/env python3
"""Acquire a pinned subset of Ubuntu source archives, without extracting/executing.

A .dsc is publisher metadata. Matching its file hashes is recorded separately
from OpenPGP authentication; neither proves complete product source delivery.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote, urljoin, urlsplit
import urllib.request

from source_inventory import paragraphs

HOSTS = {"archive.ubuntu.com", "security.ubuntu.com", "ports.ubuntu.com", "old-releases.ubuntu.com"}
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+~-]{0,240}\Z")
SHA = re.compile(r"[a-f0-9]{64}\Z")
PACKAGE = re.compile(r"[a-z0-9][a-z0-9+.-]{0,127}\Z")
VERSION = re.compile(r"[0-9][A-Za-z0-9.+:~_-]{0,179}\Z")
DESCRIPTOR_LIMIT = 1024 * 1024
FILE_LIMIT = 128 * 1024 * 1024
TOTAL_LIMIT = 512 * 1024 * 1024


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def official_url(url: str) -> str:
    parsed = urlsplit(url)
    if (parsed.scheme != "https" or parsed.hostname not in HOSTS or parsed.port not in (None, 443)
            or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment):
        raise ValueError("source URL is not an allowed public HTTPS archive URL")
    return url


class OfficialRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        official_url(newurl)
        return super().redirect_request(request, fp, code, message, headers, newurl)


def descriptor(data: bytes, package: str, version: str) -> tuple[list[dict], bool]:
    if not PACKAGE.fullmatch(package) or not VERSION.fullmatch(version):
        raise ValueError("invalid source package identity")
    if not data or len(data) > DESCRIPTOR_LIMIT or b"\0" in data:
        raise ValueError("source descriptor is empty, binary or excessive")
    text = data.decode("utf-8").replace("\r\n", "\n")
    signed = text.startswith("-----BEGIN PGP SIGNED MESSAGE-----\n")
    if signed:
        header, separator, body = text.partition("\n\n")
        if not separator or not any(line.startswith("Hash: ") for line in header.splitlines()):
            raise ValueError("invalid clearsigned descriptor header")
        body, separator, signature = body.partition("\n-----BEGIN PGP SIGNATURE-----")
        if not separator or "-----END PGP SIGNATURE-----" not in signature:
            raise ValueError("incomplete signature envelope")
        text = "\n".join(line[2:] if line.startswith("- ") else line for line in body.splitlines())
    records = paragraphs(text)
    if len(records) != 1 or records[0].get("Source") != package or records[0].get("Version") != version:
        raise ValueError("descriptor source/version does not match the binary inventory")
    files, names = [], set()
    for line in records[0].get("Checksums-Sha256", "").splitlines():
        if not line.strip():
            continue
        parts = line.split()
        if len(parts) != 3:
            raise ValueError("invalid source checksum row")
        digest, size, name = parts
        if (not SHA.fullmatch(digest) or len(size) > 20 or not size.isascii() or not size.isdecimal()
                or not 0 < int(size) <= FILE_LIMIT or not NAME.fullmatch(name) or name in names):
            raise ValueError("unsafe, duplicate or excessive source archive entry")
        names.add(name)
        files.append({"name": name, "bytes": int(size), "sha256": digest})
    if not files:
        raise ValueError("descriptor has no SHA-256 source archive entries")
    return sorted(files, key=lambda item: item["name"]), signed


def plan(inventory: dict, package: str, data: bytes, url: str, version: str | None = None) -> dict:
    official_url(url)
    matches = [item for item in inventory["ubuntuSources"] if item["package"] == package
               and (version is None or item["version"] == version)]
    if len(matches) != 1:
        raise ValueError("source package/version must occur exactly once in the inventory; select a version explicitly if needed")
    version = matches[0]["version"]
    files, signed = descriptor(data, package, version)
    name = urlsplit(url).path.rsplit("/", 1)[-1]
    if not NAME.fullmatch(name) or not name.endswith(".dsc"):
        raise ValueError("invalid descriptor filename")
    for item in files:
        item["url"] = official_url(urljoin(url, quote(item["name"], safe="")))
    return {"schema": 1, "kind": "pinned-ubuntu-source-archive-subset",
            "completeCorrespondingSourceDelivered": False,
            "inventoryDependenciesSha256": inventory["dependenciesSha256"],
            "package": package, "version": version,
            "descriptor": {"name": name, "url": url, "bytes": len(data),
                           "sha256": hashlib.sha256(data).hexdigest()},
            "signatureEnvelopePresent": signed, "signatureVerified": False,
            "verificationScope": "exact inventory source/version and archive sizes/SHA-256 from HTTPS publisher descriptor; no independent OpenPGP authentication",
            "files": files}


def checked_item(item: dict, maximum: int) -> None:
    if (not NAME.fullmatch(item["name"]) or not SHA.fullmatch(item["sha256"])
            or type(item["bytes"]) is not int or not 0 < item["bytes"] <= maximum):
        raise ValueError("invalid pinned source file")
    official_url(item["url"])


def directory(path: Path) -> None:
    if path.is_symlink():
        raise ValueError("source output directory must not be a symlink")
    path.mkdir(parents=True, exist_ok=True)
    if not path.is_dir():
        raise ValueError("source output is not a directory")


def fetch(item: dict, base: Path, maximum: int, opener=None) -> tuple[Path, bool]:
    checked_item(item, maximum)
    directory(base)
    parent = base / item["sha256"]
    directory(parent)
    destination = parent / item["name"]
    if destination.is_symlink():
        raise ValueError("source cache symlink rejected")
    if destination.exists():
        if not destination.is_file() or destination.stat().st_size != item["bytes"] or sha256(destination) != item["sha256"]:
            raise ValueError("existing source cache entry does not match its pin; preserved")
        return destination, True
    opener = opener or urllib.request.build_opener(OfficialRedirect())
    fd, temporary = tempfile.mkstemp(prefix=".download-", suffix=".part", dir=parent)
    count, digest = 0, hashlib.sha256()
    try:
        with os.fdopen(fd, "wb") as target:
            with opener.open(item["url"], timeout=60) as response:
                official_url(response.geturl())
                if response.status != 200:
                    raise ValueError("source server did not return a full HTTP 200 response")
                while block := response.read(1024 * 1024):
                    count += len(block)
                    if count > item["bytes"] or count > maximum:
                        raise ValueError("source download exceeded its pinned size")
                    target.write(block)
                    digest.update(block)
            target.flush()
            os.fsync(target.fileno())
        if count != item["bytes"] or digest.hexdigest() != item["sha256"]:
            raise ValueError("source size/SHA-256 mismatch; no cache entry committed")
        # An exclusive link never overwrites a file created by another writer.
        os.link(temporary, destination)
        return destination, False
    finally:
        Path(temporary).unlink(missing_ok=True)


def acquire(manifest: dict, output: Path, opener=None) -> dict:
    if manifest.get("schema") != 1 or manifest.get("kind") != "pinned-ubuntu-source-archive-subset":
        raise ValueError("unsupported source acquisition manifest")
    checked_item(manifest["descriptor"], DESCRIPTOR_LIMIT)
    for item in manifest["files"]:
        checked_item(item, FILE_LIMIT)
    if sum(item["bytes"] for item in manifest["files"]) > TOTAL_LIMIT:
        raise ValueError("source subset exceeds the total acquisition limit")
    directory(output)
    descriptor_path, cached = fetch(manifest["descriptor"], output / "descriptors", DESCRIPTOR_LIMIT, opener)
    parsed, signed = descriptor(descriptor_path.read_bytes(), manifest["package"], manifest["version"])
    expected = [{key: item[key] for key in ("name", "bytes", "sha256")} for item in manifest["files"]]
    if parsed != sorted(expected, key=lambda item: item["name"]):
        raise ValueError("manifest source files differ from the pinned descriptor")
    records = [{"kind": "descriptor", **manifest["descriptor"], "relativePath": descriptor_path.relative_to(output).as_posix(), "cacheHit": cached}]
    for item in manifest["files"]:
        expected_url = urljoin(manifest["descriptor"]["url"], quote(item["name"], safe=""))
        if item["url"] != expected_url:
            raise ValueError("source archive URL is not the descriptor's sibling")
        path, cached = fetch(item, output / "archives", FILE_LIMIT, opener)
        records.append({"kind": "source-archive", **item, "relativePath": path.relative_to(output).as_posix(), "cacheHit": cached})
    return {"schema": 1, "kind": "ubuntu-source-subset-acquisition-receipt", "package": manifest["package"],
            "version": manifest["version"], "archiveSetComplete": True, "completeCorrespondingSourceDelivered": False,
            "signatureEnvelopePresent": signed, "signatureVerified": False,
            "verificationScope": manifest["verificationScope"], "files": records}


def receipt_path(output: Path, package: str, version: str) -> Path:
    if not PACKAGE.fullmatch(package) or not VERSION.fullmatch(version):
        raise ValueError("invalid source receipt identity")
    version_id = hashlib.sha256(version.encode("ascii")).hexdigest()
    return output / (package + "-" + version_id + "-source-receipt.json")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    discover = commands.add_parser("plan", help="Pin an already acquired exact-version descriptor offline")
    discover.add_argument("--inventory", type=Path, required=True)
    discover.add_argument("--package", required=True)
    discover.add_argument("--version", help="Select the exact inventory version when a source package has multiple versions")
    discover.add_argument("--descriptor", type=Path, required=True)
    discover.add_argument("--descriptor-url", required=True)
    discover.add_argument("--output", type=Path, required=True)
    download = commands.add_parser("fetch", help="Download/verify the pinned source subset; never extract")
    download.add_argument("--manifest", type=Path, required=True)
    download.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "plan":
        result = plan(json.loads(args.inventory.read_text()), args.package, args.descriptor.read_bytes(), args.descriptor_url, args.version)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    else:
        result = acquire(json.loads(args.manifest.read_text()), args.out)
        receipt = receipt_path(args.out, result["package"], result["version"])
        if receipt.is_symlink():
            raise ValueError("source receipt symlink rejected")
        receipt.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"package": result["package"], "version": result["version"], "files": len(result["files"]),
                      "completeCorrespondingSourceDelivered": False, "signatureVerified": False}))


if __name__ == "__main__":
    main()
