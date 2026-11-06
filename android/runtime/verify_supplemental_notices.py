#!/usr/bin/env python3
"""Offline byte/metadata checks for the separate source/notice supplement.

Does not authenticate publisher signatures, rehash absent binary archives, prove
shipped dependency membership, or certify complete source/legal compliance.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

from notices import read_anchored

REPO = Path(__file__).resolve().parents[2]
DEFAULT_DIRECTORY = Path("android/runtime/supplemental-notices")
HOSTS = {"raw.githubusercontent.com", "api.github.com", "registry.npmjs.org"}
SHA256 = re.compile(r"[a-f0-9]{64}")
COMMIT = re.compile(r"[a-f0-9]{40}")


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def parse(raw):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, "duplicate_json_field")
            value[key] = item
        return value
    return json.loads(raw, object_pairs_hook=unique)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def check_url(value):
    u = urlsplit(value)
    require(u.scheme == "https" and u.hostname in HOSTS and not u.username
            and not u.password and not u.query and not u.fragment, "unsafe_source_url")


def urls(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in {"url", "finalURL"}:
                check_url(item)
            elif isinstance(item, (dict, list)):
                urls(item)
    elif isinstance(value, list):
        for item in value:
            urls(item)


def verify(repo: Path, directory: Path) -> dict:
    manifest_raw = read_anchored(directory, "manifest.json")
    require(not any(p in manifest_raw for p in [b"/Users/", b"/private/", b"/tmp/"]), "host_path_in_manifest")
    m = parse(manifest_raw)
    require(m["schema"] == 1 and m["kind"] == "atomcode-go-supplemental-notices", "unsupported_manifest")
    scope = m["scope"]
    require(scope["completeCorrespondingSourceDelivered"] is False
            and scope["legalComplianceVerified"] is False
            and scope["binaryArchivesIncluded"] is False
            and scope["apkBaseline"]["changedByThisSupplement"] is False, "invalid_scope_claim")
    urls(m)
    referenced = set()

    def content(record, folder="evidence"):
        digest = record["sha256"]
        require(isinstance(digest, str) and SHA256.fullmatch(digest), "invalid_file_digest")
        require(record["file"] in {folder + "/" + digest + ".txt", folder + "/" + digest + ".json"}, "unsafe_content_reference")
        raw = read_anchored(directory, record["file"])
        require(len(raw) == record["bytes"] and 0 < len(raw) <= 262144 and sha(raw) == digest, "content_digest_or_length_mismatch")
        referenced.add(record["file"])
        return raw

    def response(record, folder="evidence"):
        require(record["status"] == "fetched" and record["httpStatus"] == 200, "response_not_obtained")
        check_url(record["url"])
        check_url(record["finalURL"])
        return content(record, folder)

    lock_text = read_anchored(repo, "Cargo.lock").decode()
    cargo_lock = {}
    for block in lock_text.split("[[package]]")[1:]:
        name = re.search(r'^name = "([^"]+)"$', block, re.M)
        version = re.search(r'^version = "([^"]+)"$', block, re.M)
        checksum = re.search(r'^checksum = "([a-f0-9]{64})"$', block, re.M)
        if name and version:
            cargo_lock[(name[1], version[1])] = checksum[1] if checksum else None
    npm_lock = parse(read_anchored(repo, "webui/package-lock.json"))["packages"]
    shared = m["workspaceSharedNotice"]
    require(shared["repositoryPath"] == "LICENSE" and shared["independentPerPackageTexts"] is False, "invalid_shared_notice")
    require(sha(read_anchored(repo, "LICENSE")) == shared["sha256"], "shared_license_changed")
    require(len(set(shared["packages"])) == len(shared["packages"])
            and all((n, shared["version"]) in cargo_lock for n in shared["packages"]), "shared_package_not_in_lock")
    packages = m["packages"]
    identities = {(p["ecosystem"], p["name"], p["version"]) for p in packages}
    require(len(identities) == len(packages), "duplicate_package")
    npm_packages = {(p["name"], p["version"]): p for p in packages if p["ecosystem"] == "npm"}
    for p in packages:
        require(p["status"] in {"resolved", "unresolved"}, "invalid_resolution")
        require(bool(p["licenses"]) == (p["status"] == "resolved"), "resolution_without_original_text")
        if p["status"] == "unresolved":
            require(bool(p.get("unresolvedReason")), "unresolved_reason_required")
        upstream = p["upstream"]
        require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", upstream["repository"]), "invalid_repository")
        commit = upstream["commit"]
        require((commit is None and p["status"] == "unresolved")
                or (isinstance(commit, str) and COMMIT.fullmatch(commit)), "immutable_commit_required")
        for license in p["licenses"]:
            require(license["commit"] == commit and license["repository"] == upstream["repository"], "notice_source_mismatch")
            require(not license["path"].startswith("/") and not any(x in {"", ".", ".."} for x in license["path"].split("/")) and "\\" not in license["path"], "unsafe_original_path")
            expected = "https://raw.githubusercontent.com/" + upstream["repository"] + "/" + commit + "/" + license["path"]
            require(license["url"] == expected and license["finalURL"] == expected, "notice_url_not_pinned_to_commit")
            response(license, "texts")
        artifact = p["artifact"]
        if p["ecosystem"] == "cargo":
            require(artifact["type"] == "cargo-crate" and cargo_lock.get((p["name"], p["version"])) == artifact["sha256"], "cargo_lock_artifact_mismatch")
            vcs = p["publishedVcsInfo"]
            require(vcs["originArtifactSha256"] == artifact["sha256"]
                    and vcs["originalPath"] == p["name"] + "-" + p["version"] + "/.cargo_vcs_info.json", "vcs_artifact_mismatch")
            data = parse(content(vcs))
            require(data["git"]["sha1"] == commit and data["path_in_vcs"] == upstream["pathInVcs"], "published_vcs_commit_mismatch")
            continue
        require(p["ecosystem"] == "npm", "unknown_ecosystem")
        entry = npm_lock[p["lockPath"]]
        actual_name = entry.get("name") or p["lockPath"].split("node_modules/")[-1]
        require(actual_name == p["name"] and entry["version"] == p["version"], "npm_lock_identity_mismatch")
        official = parse(response(p["officialMetadata"]))
        require(official["name"] == p["name"] and official["version"] == p["version"], "official_metadata_identity_mismatch")
        require(unquote(urlsplit(p["officialMetadata"]["url"]).path) == "/" + p["name"] + "/" + p["version"], "wrong_version_metadata_url")
        source_repository = official["repository"]["url"].removeprefix("git+").removeprefix("https://github.com/").removesuffix(".git")
        require(source_repository == upstream["repository"], "official_repository_mismatch")
        evidence = []
        for item in p["evidence"]:
            if item["status"] == "fetched":
                raw = response(item)
                evidence.append((item, raw))
        if official.get("gitHead"):
            require(official["gitHead"] == commit, "official_git_head_mismatch")
        elif p["status"] == "resolved":
            tags = [(e, parse(raw)) for e, raw in evidence if e["kind"] == "tag"]
            require(any(e["releaseTag"].endswith(p["version"]) and d["ref"] == "refs/tags/" + e["releaseTag"]
                        and d["object"]["type"] == "commit" and d["object"]["sha"] == commit for e, d in tags), "exact_release_tag_required")
        if p["status"] == "resolved":
            source = next(((e, parse(raw)) for e, raw in evidence if e.get("path") == upstream["sourcePackagePath"]), None)
            require(source is not None and source[0]["commit"] == commit and source[0]["repository"] == upstream["repository"]
                    and source[1]["name"] == upstream["sourcePackageName"] and source[1]["version"] == p["version"], "source_package_identity_mismatch")
        if artifact["type"] == "npm-tarball":
            require(entry.get("inBundle") is not True, "bundled_package_cannot_be_independent_artifact")
            require(SHA256.fullmatch(artifact["sha256"]) and official["dist"]["integrity"] == artifact["integrity"]
                    and official["dist"]["shasum"] == artifact["sha1"] and official["dist"]["tarball"] == artifact["url"], "npm_artifact_metadata_mismatch")
            if entry.get("integrity"):
                require(entry["integrity"] == artifact["integrity"], "npm_lock_integrity_mismatch")
        else:
            require(artifact["type"] == "npm-bundled" and entry.get("inBundle") is True, "invalid_bundled_artifact")
            parent = artifact["parent"]
            owner = npm_packages[(parent["parentName"], parent["parentVersion"])]
            require(owner["artifact"]["type"] == "npm-tarball" and owner["artifact"]["sha256"] == parent["parentTarballSha256"]
                    and owner["artifact"]["integrity"] == parent["parentSri"]
                    and p["lockPath"] == owner["lockPath"] + "/" + artifact["pathInParent"], "bundled_parent_mismatch")
            for member in artifact["members"]:
                raw = content(member["evidence"])
                require(member["member"].startswith("package/" + artifact["pathInParent"] + "/")
                        and sha(raw) == member["sha256"] and len(raw) == member["bytes"], "bundled_member_mismatch")
        tree = next((parse(raw) for e, raw in evidence if e["kind"] == "root-tree"), None)
        for source in p.get("sourceFiles", []):
            raw = content(source)
            require(tree and tree["sha"] == commit, "source_tree_commit_mismatch")
            expected = next((x["sha"] for x in tree["tree"] if x["path"] == source["path"] and x["type"] == "blob"), None)
            require(hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == expected, "source_git_blob_mismatch")
        for item, raw in evidence:
            if item["kind"] != "attestation":
                continue
            for attestation in parse(raw)["attestations"]:
                statement = parse(base64.b64decode(attestation["bundle"]["dsseEnvelope"]["payload"]))
                sri = base64.b64decode(artifact["integrity"].split("-", 1)[1]).hex()
                require(all(s["digest"]["sha512"] == sri for s in statement["subject"]), "provenance_subject_mismatch")
                if statement["predicateType"] == "https://slsa.dev/provenance/v1":
                    deps = statement["predicate"]["buildDefinition"]["resolvedDependencies"]
                    require(any(d["digest"].get("gitCommit") == commit for d in deps), "provenance_commit_mismatch")
    expected_files = referenced | {"manifest.json", "README.md"}
    actual_files = set()
    require(not directory.is_symlink(), "supplement_directory_symlink")
    for parent, directories, files in os.walk(directory, followlinks=False):
        for name in directories + files:
            require(not (Path(parent) / name).is_symlink(), "supplement_symlink")
        for name in files:
            actual_files.add((Path(parent) / name).relative_to(directory).as_posix())
    require(actual_files == expected_files, "unreferenced_or_missing_supplement_file")
    return {"externalPackages": len(packages), "resolved": sum(p["status"] == "resolved" for p in packages),
            "unresolved": sum(p["status"] == "unresolved" for p in packages),
            "originalTexts": sum(p.startswith("texts/") for p in referenced),
            "workspaceSharedPackages": len(shared["packages"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--directory", type=Path)
    args = parser.parse_args()
    directory = args.directory if args.directory is not None else args.repo / DEFAULT_DIRECTORY
    try:
        print(json.dumps(verify(args.repo, directory), sort_keys=True))
    except (OSError, ValueError, KeyError, TypeError, StopIteration) as error:
        parser.exit(1, "Supplement verification failed: " + type(error).__name__ + "\n")


if __name__ == "__main__":
    main()
