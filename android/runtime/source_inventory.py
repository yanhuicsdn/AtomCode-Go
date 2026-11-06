#!/usr/bin/env python3
"""Inventory pinned runtime packages locally; this does not deliver their source.

No downloads or archive extraction are performed. Cached inputs are verified
before reading the Ubuntu Base dpkg status and Debian control metadata.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import tarfile
from pathlib import Path
from urllib.parse import quote

HERE = Path(__file__).resolve().parent
METADATA_LIMIT = 4 * 1024 * 1024
PACKAGE = re.compile(r"[a-z0-9][a-z0-9+.-]*\Z")


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def paragraphs(text: str) -> list[dict[str, str]]:
    records = []
    record: dict[str, str] = {}
    previous = None
    for line in text.splitlines() + [""]:
        if not line:
            if record:
                records.append(record)
            record, previous = {}, None
        elif line[0].isspace():
            if previous is None:
                raise ValueError("metadata continuation without field")
            record[previous] += "\n" + line[1:]
        else:
            name, colon, value = line.partition(":")
            if not colon or not name or name in record:
                raise ValueError("invalid or duplicate metadata field")
            record[name] = value.strip()
            previous = name
    return records


def read_tar_member(archive: tarfile.TarFile, expected: str) -> bytes:
    matches = [member for member in archive if member.name in (expected, "./" + expected)]
    if len(matches) != 1 or not matches[0].isfile():
        raise ValueError("missing, duplicate or non-regular metadata member: " + expected)
    member = matches[0]
    if not 0 <= member.size <= METADATA_LIMIT:
        raise ValueError("metadata member exceeds size limit")
    stream = archive.extractfile(member)
    if stream is None:
        raise ValueError("unreadable metadata member")
    with stream:
        data = stream.read(METADATA_LIMIT + 1)
    if len(data) != member.size:
        raise ValueError("truncated metadata member")
    return data


def deb_control(path: Path) -> dict[str, str]:
    # Debian .deb uses ar; only the control tar is inspected, never its data tar.
    with path.open("rb") as source:
        if source.read(8) != b"!<arch>\n":
            raise ValueError("invalid Debian archive")
        payload = None
        control_name = None
        while header := source.read(60):
            if len(header) != 60 or header[58:] != b"`\n":
                raise ValueError("invalid ar member header")
            name = header[:16].decode("ascii").strip().removesuffix("/")
            size = int(header[48:58].strip())
            if size < 0:
                raise ValueError("negative ar member size")
            if name.startswith("control.tar"):
                if payload is not None or size > METADATA_LIMIT:
                    raise ValueError("duplicate or excessive Debian control archive")
                payload, control_name = source.read(size), name
                if len(payload) != size:
                    raise ValueError("truncated Debian control archive")
            else:
                source.seek(size, 1)
            if size % 2:
                source.seek(1, 1)
    if payload is None:
        raise ValueError("Debian control archive missing")
    if control_name == "control.tar.zst":
        try:
            import zstandard
        except ImportError as error:
            raise ValueError("zstandard is required; use the pinned .tools/python environment") from error
        with zstandard.ZstdDecompressor().stream_reader(io.BytesIO(payload)) as compressed:
            payload = compressed.read(METADATA_LIMIT + 1)
        if len(payload) > METADATA_LIMIT:
            raise ValueError("expanded Debian control archive exceeds size limit")
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:*") as archive:
        records = paragraphs(read_tar_member(archive, "control").decode("utf-8"))
    if len(records) != 1:
        raise ValueError("Debian control must contain one paragraph")
    return records[0]


def package_record(record: dict[str, str]) -> dict[str, str]:
    package, version, arch = record["Package"], record["Version"], record["Architecture"]
    if not PACKAGE.fullmatch(package) or not version or not arch:
        raise ValueError("invalid package identity")
    source = record.get("Source", package)
    match = re.fullmatch(r"([a-z0-9][a-z0-9+.-]*)(?: \(([^()\s]+)\))?", source)
    if not match:
        raise ValueError("invalid source package identity")
    return {"package": package, "version": version, "architecture": arch,
            "sourcePackage": match[1], "sourceVersion": match[2] or version}


def source_url(package: str, version: str) -> str:
    return "https://launchpad.net/ubuntu/+source/" + quote(package, safe="") + "/" + quote(version, safe="")


def generate(dependencies: Path, cache: Path) -> dict:
    recipe = json.loads(dependencies.read_text())
    if recipe.get("schema") != 1:
        raise ValueError("unsupported dependency schema")
    inputs = recipe["inputs"]
    by_name = {}
    for item in inputs:
        name = item["name"]
        if Path(name).name != name or name in by_name:
            raise ValueError("unsafe or duplicate input name")
        path = cache / name
        if sha256(path) != item["sha256"]:
            raise ValueError("cached input SHA-256 mismatch: " + name)
        by_name[name] = item
    bases = [item for item in inputs if item["name"].startswith("ubuntu-base-") and item["name"].endswith(".tar.gz")]
    if len(bases) != 1:
        raise ValueError("expected one pinned Ubuntu Base archive")
    base = bases[0]
    with tarfile.open(cache / base["name"], "r:*") as archive:
        status = paragraphs(read_tar_member(archive, "var/lib/dpkg/status").decode("utf-8"))
    packages = {}
    for entry in status:
        if entry.get("Status") != "install ok installed":
            continue
        package = package_record(entry)
        key = package["package"], package["architecture"]
        if key in packages:
            raise ValueError("duplicate Ubuntu Base package identity")
        package.update(origin="ubuntu-base", input=base["name"], inputSha256=base["sha256"])
        packages[key] = package
    base_count = len(packages)
    native = []
    replaced = []
    for item in inputs:
        if not item["name"].endswith(".deb"):
            continue
        package = package_record(deb_control(cache / item["name"]))
        package.update(input=item["name"], inputSha256=item["sha256"])
        if "package" in item:
            for field in ("package", "version", "sourcePackage", "sourceVersion"):
                if package[field] != item[field]:
                    raise ValueError("manifest disagrees with actual Debian control: " + item["name"] + " / " + field)
            key = package["package"], package["architecture"]
            if key in packages:
                replaced.append({"package": key[0], "architecture": key[1],
                                 "baseVersion": packages[key]["version"], "finalVersion": package["version"]})
            package["origin"] = "ubuntu-overlay"
            packages[key] = package
        else:
            package.update(origin="termux-native", sourceUrl=item["source"],
                           buildRecipe=item["buildRecipe"], license=item["license"])
            if package["package"] in ("proot", "libtalloc"):
                package["localModification"] = "ELF .dynstr libtalloc.so.2 renamed to libtalloc.so; see build.py"
            native.append(package)
    ubuntu = sorted(packages.values(), key=lambda p: (p["package"], p["architecture"]))
    for package in ubuntu:
        package["sourceUrl"] = source_url(package["sourcePackage"], package["sourceVersion"])
        package["copyrightPath"] = "/usr/share/doc/" + package["package"] + "/copyright"
    sources = {}
    for package in ubuntu:
        key = package["sourcePackage"], package["sourceVersion"]
        sources.setdefault(key, {"package": key[0], "version": key[1], "url": package["sourceUrl"], "binaries": []})["binaries"].append(package["package"])
    return {
        "schema": 1, "kind": "pinned-runtime-source-inventory", "dependenciesSha256": sha256(dependencies),
        "completeCorrespondingSourceDelivered": False,
        "notice": "Offline inventory of verified pinned inputs, not source archive or license delivery. Source URLs are acquisition landing pages. Obtain and verify complete corresponding source and applicable notices before production distribution.",
        "dpkgStatusIsFinalInventory": False,
        "dpkgStatusNote": "Ubuntu .deb files are overlaid without running dpkg. The Base status file does not reflect those overlays; this inventory merges their actual control metadata.",
        "baseBinaryPackageCount": base_count, "ubuntuBinaryPackageCount": len(ubuntu),
        "ubuntuSourcePackageCount": len(sources), "nativeBinaryPackageCount": len(native),
        "replacedBasePackages": sorted(replaced, key=lambda p: (p["package"], p["architecture"])),
        "ubuntuPackages": ubuntu, "ubuntuSources": [sources[key] for key in sorted(sources)],
        "nativePackages": sorted(native, key=lambda p: p["package"]),
        "cachedSourceArchives": [{"name": item["name"], "sha256": item["sha256"], "url": item["url"]}
                                 for item in inputs if item["name"].startswith("proot-v") and item["name"].endswith(".zip")],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", type=Path, default=HERE / "dependencies.json")
    parser.add_argument("--cache", type=Path, default=HERE / "cache")
    parser.add_argument("--output", type=Path, default=HERE / "out/arm64-v8a/source-inventory.json")
    args = parser.parse_args()
    report = generate(args.dependencies, args.cache)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Inventoried {report['ubuntuBinaryPackageCount']} Ubuntu binaries / {report['ubuntuSourcePackageCount']} sources and {report['nativeBinaryPackageCount']} native components: {args.output}")


if __name__ == "__main__":
    main()
