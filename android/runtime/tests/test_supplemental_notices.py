import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

RUNTIME = Path(__file__).parents[1]
REPO = RUNTIME.parents[1]
sys.path.insert(0, str(RUNTIME))
try:
    spec = importlib.util.spec_from_file_location("supplemental_notices", RUNTIME / "verify_supplemental_notices.py")
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
finally:
    sys.path.pop(0)


class SupplementalNoticeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "repo"
        (self.repo / "webui").mkdir(parents=True)
        for source in ["Cargo.lock", "LICENSE", "webui/package-lock.json"]:
            shutil.copyfile(REPO / source, self.repo / source)
        self.directory = self.repo / "supplement"
        shutil.copytree(RUNTIME / "supplemental-notices", self.directory)
        self.original = json.loads((self.directory / "manifest.json").read_text())

    def write(self, manifest):
        (self.directory / "manifest.json").write_text(json.dumps(manifest))

    def verify(self):
        return verifier.verify(self.repo, self.directory)

    def replace_evidence(self, record, metadata):
        raw = json.dumps(metadata).encode()
        digest = hashlib.sha256(raw).hexdigest()
        record.update(file="evidence/" + digest + ".json", sha256=digest, bytes=len(raw))
        (self.directory / record["file"]).write_bytes(raw)

    def test_offline_deliverable_preserves_ten_originals_and_three_unresolved_packages(self):
        self.assertEqual(self.verify(), {"externalPackages": 13, "resolved": 10, "unresolved": 3,
                                         "originalTexts": 9, "workspaceSharedPackages": 13})
        unresolved = {p["name"] for p in self.original["packages"] if p["status"] == "unresolved"}
        self.assertEqual(unresolved, {"boolbase", "dlv", "napi-wasm"})

    def test_changed_text_and_outside_symlink_are_rejected(self):
        record = next(p for p in self.original["packages"] if p["licenses"])["licenses"][0]
        target = self.directory / record["file"]
        original = target.read_bytes()
        target.write_bytes(original + b"changed")
        with self.assertRaisesRegex(ValueError, "content_digest_or_length"):
            self.verify()
        target.unlink()
        outside = self.repo / "outside.txt"
        outside.write_bytes(original)
        target.symlink_to(outside)
        with self.assertRaises((OSError, ValueError)):
            self.verify()
        self.assertEqual(outside.read_bytes(), original)

    def test_unknown_file_and_escaping_content_reference_are_rejected(self):
        extra = self.directory / "unrelated.txt"
        extra.write_text("Unrelated data must not enter the deliverable")
        with self.assertRaisesRegex(ValueError, "unreferenced_or_missing"):
            self.verify()
        extra.unlink()
        manifest = copy.deepcopy(self.original)
        next(p for p in manifest["packages"] if p["licenses"])["licenses"][0]["file"] = "../LICENSE"
        self.write(manifest)
        with self.assertRaisesRegex(ValueError, "unsafe_content_reference"):
            self.verify()

    def test_cargo_checksum_and_npm_alias_identity_remain_bound_to_locks(self):
        manifest = copy.deepcopy(self.original)
        next(p for p in manifest["packages"] if p["ecosystem"] == "cargo")["artifact"]["sha256"] = "0" * 64
        self.write(manifest)
        with self.assertRaisesRegex(ValueError, "cargo_lock_artifact_mismatch"):
            self.verify()
        manifest = copy.deepcopy(self.original)
        next(p for p in manifest["packages"] if p["name"] == "lightningcss-wasm")["name"] = "lightningcss"
        self.write(manifest)
        with self.assertRaisesRegex(ValueError, "npm_lock_identity_mismatch"):
            self.verify()

    def test_rehashed_metadata_cannot_substitute_a_different_release_commit(self):
        manifest = copy.deepcopy(self.original)
        package = next(p for p in manifest["packages"] if p["name"] == "@rollup/pluginutils")
        record = next(e for e in package["evidence"] if e["kind"] == "tag")
        metadata = json.loads((self.directory / record["file"]).read_text())
        metadata["object"]["sha"] = "0" * 40
        self.replace_evidence(record, metadata)
        self.write(manifest)
        with self.assertRaisesRegex(ValueError, "exact_release_tag_required"):
            self.verify()

    def test_bundled_napi_cannot_be_relabelled_as_an_independent_artifact(self):
        manifest = copy.deepcopy(self.original)
        package = next(p for p in manifest["packages"] if p["name"] == "napi-wasm")
        official = json.loads((self.directory / package["officialMetadata"]["file"]).read_text())
        package["artifact"] = {"type": "npm-tarball", "sha256": "0" * 64,
                               "integrity": official["dist"]["integrity"],
                               "sha1": official["dist"]["shasum"], "url": official["dist"]["tarball"]}
        self.write(manifest)
        with self.assertRaisesRegex(ValueError, "bundled_package_cannot_be_independent"):
            self.verify()

    def test_unresolved_cannot_be_closed_with_metadata_or_a_shared_notice_claim(self):
        manifest = copy.deepcopy(self.original)
        next(p for p in manifest["packages"] if p["name"] == "boolbase")["status"] = "resolved"
        self.write(manifest)
        with self.assertRaisesRegex(ValueError, "resolution_without_original_text"):
            self.verify()
        manifest = copy.deepcopy(self.original)
        manifest["workspaceSharedNotice"]["independentPerPackageTexts"] = True
        self.write(manifest)
        with self.assertRaisesRegex(ValueError, "invalid_shared_notice"):
            self.verify()

    def test_credential_bearing_source_urls_are_rejected(self):
        manifest = copy.deepcopy(self.original)
        next(p for p in manifest["packages"] if p["licenses"])["licenses"][0]["url"] = "https://token@example.com/LICENSE"
        self.write(manifest)
        with self.assertRaisesRegex(ValueError, "unsafe_source_url"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
