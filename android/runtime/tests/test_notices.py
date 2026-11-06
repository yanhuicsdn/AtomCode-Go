import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("dependency_notices", Path(__file__).parents[1] / "notices.py")
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


class NoticeInventoryTests(unittest.TestCase):
    def fixture(self, repo):
        definitions = [("daemon", "atomcode-daemon"), ("normal", "normal-dependency"),
                       ("builder", "build-dependency"), ("helper", "builder-helper"), ("devonly", "dev-only")]
        packages = []
        for identity, name in definitions:
            root = repo / "crates" / identity
            root.mkdir(parents=True)
            (root / "LICENSE").write_bytes(("Full license text for " + name + "\r\n").encode())
            packages.append({"id": identity, "name": name, "version": "1.0.0", "source": None,
                             "manifest_path": str(root / "Cargo.toml"), "license": "MIT", "license_file": None})
        def edge(identity, kind):
            return {"pkg": identity, "dep_kinds": [{"kind": kind, "target": None}]}
        nodes = [{"id": "daemon", "deps": [edge("normal", None), edge("builder", "build"), edge("devonly", "dev")]},
                 {"id": "builder", "deps": [edge("helper", None)]}]
        nodes += [{"id": name, "deps": []} for name in ["normal", "helper", "devonly"]]
        metadata = {"packages": packages, "workspace_members": ["daemon"], "resolve": {"nodes": nodes}}
        webui = repo / "webui"
        webui.mkdir()
        fonts = webui / "node_modules" / "test-font"
        fonts.mkdir(parents=True)
        (fonts / "package.json").write_text(json.dumps({"name": "test-font", "version": "2.0.0", "license": "OFL-1.1"}))
        (fonts / "LICENSE").write_bytes(b"Font notice preserved exactly.\r\n\xff\n")
        (webui / "package-lock.json").write_text(json.dumps({"packages": {"": {}, "node_modules/test-font": {"version": "2.0.0", "dev": True},
                                                                               "node_modules/platform-only": {"version": "1.0.0", "optional": True}}}))
        (repo / "LICENSE").write_text("Shared repository license\n")
        return metadata

    def test_collects_normal_and_build_closure_and_installed_dev_fonts_without_changing_text(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory) / "repo"; repo.mkdir()
            metadata = self.fixture(repo)
            output = Path(directory) / "notices"
            report = notices.generate(repo, output, metadata, metadata_provided=True)
            cargo = {p["name"] for p in report["packages"] if p["ecosystem"] == "cargo"}
            self.assertEqual(cargo, {"atomcode-daemon", "normal-dependency", "build-dependency", "builder-helper"})
            font = next(p for p in report["packages"] if p["name"] == "test-font")
            self.assertTrue(font["dev"])
            data = b"Font notice preserved exactly.\r\n\xff\n"
            self.assertEqual((output / font["files"][0]["copy"]).read_bytes(), data)
            self.assertEqual(font["files"][0]["sha256"], hashlib.sha256(data).hexdigest())
            self.assertFalse(report["completeCorrespondingSourceDelivered"])
            self.assertFalse(report["legalComplianceVerified"])
            self.assertFalse(report["shippedDependencySetProven"])
            self.assertIsNone(report["scope"]["cargoTargetFilter"])
            self.assertIn("superset", report["scope"]["cargo"])
            self.assertEqual(report["uninstalledNpmLockEntries"][0]["lockPath"], "node_modules/platform-only")

    def test_rejected_symlink_and_arbitrary_license_file_cannot_read_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); repo = base / "repo"; repo.mkdir()
            metadata = self.fixture(repo)
            package = metadata["packages"][1]
            root = Path(package["manifest_path"]).parent
            (root / "LICENSE").unlink()
            secret = base / "private-credentials"; secret.write_bytes(b"THIS_MUST_NOT_ENTER_NOTICES")
            (root / "LICENSE").symlink_to(secret)
            package["license_file"] = str(secret)
            (root / ".env").write_bytes(b"ANOTHER_PRIVATE_VALUE")
            output = base / "notices"
            report = notices.generate(repo, output, metadata, True)
            record = next(p for p in report["packages"] if p["name"] == package["name"])
            self.assertEqual(record["files"], [])
            self.assertIn({"reason": "declared_license_file_outside_package"}, record["problems"])
            self.assertNotIn(str(secret), json.dumps(report))
            for path in (output / "texts").iterdir():
                self.assertNotIn(b"PRIVATE", path.read_bytes())
            with self.assertRaisesRegex(ValueError, "not_notice_named"):
                notices.declared_license_path(root, ".env")

    def test_linked_notice_directories_and_hardlinks_are_not_read(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); package = base / "package"; package.mkdir()
            private = base / "private"; private.mkdir()
            (private / "NOTICE").write_bytes(b"not package material")
            (package / "licenses").symlink_to(private, target_is_directory=True)
            import os
            os.link(private / "NOTICE", package / "COPYING")
            files, problems, contents = notices.package_notices(package)
            self.assertEqual(files, [])
            self.assertEqual(contents, {})
            self.assertTrue(any(p["reason"] == "symlink_notice_directory_rejected" for p in problems))
            self.assertTrue(any(p["reason"] == "notice_not_read_safely" for p in problems))

    def test_spdx_metadata_does_not_substitute_for_missing_license_text(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); repo = base / "repo"; repo.mkdir()
            metadata = self.fixture(repo)
            (Path(metadata["packages"][0]["manifest_path"]).parent / "LICENSE").unlink()
            report = notices.generate(repo, base / "notices", metadata, True)
            package = next(p for p in report["packages"] if p["name"] == "atomcode-daemon")
            self.assertEqual(package["declaredLicense"], "MIT")
            self.assertEqual(package["files"], [])
            self.assertEqual(report["coverage"]["packagesWithoutTexts"], 1)
            self.assertTrue(report["repositoryNotices"])

    def test_output_symlink_and_malformed_graph_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); repo = base / "repo"; repo.mkdir()
            metadata = self.fixture(repo)
            outside = base / "outside"; outside.mkdir()
            output = base / "notices"; output.symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "output_directory_symlink"):
                notices.generate(repo, output, metadata, True)
            self.assertEqual(list(outside.iterdir()), [])
            metadata["resolve"]["nodes"] = []
            with self.assertRaisesRegex(ValueError, "incomplete_cargo_metadata"):
                notices.cargo_packages(metadata)

    def test_reused_output_removes_only_old_index_owned_unreferenced_texts(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); repo = base / "repo"; repo.mkdir()
            metadata = self.fixture(repo); output = base / "notices"
            first = notices.generate(repo, output, metadata, True)
            font = next(p for p in first["packages"] if p["name"] == "test-font")
            stale = output / font["files"][0]["copy"]
            (repo / "webui/node_modules/test-font/LICENSE").write_bytes(b"Replacement complete font notice\n")
            second = notices.generate(repo, output, metadata, True)
            self.assertFalse(stale.exists())
            self.assertEqual(len(list((output / "texts").iterdir())), second["coverage"]["uniqueTexts"])
            for package in second["packages"]:
                for record in package["files"]:
                    self.assertEqual(hashlib.sha256((output / record["copy"]).read_bytes()).hexdigest(), record["sha256"])

    def test_reused_output_rejects_unknown_files_and_changed_or_linked_owned_texts(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); repo = base / "repo"; repo.mkdir()
            metadata = self.fixture(repo); output = base / "notices"
            first = notices.generate(repo, output, metadata, True)
            unknown = output / "texts/user-data.txt"; unknown.write_bytes(b"keep unrelated data")
            with self.assertRaisesRegex(ValueError, "unknown_output_text"):
                notices.generate(repo, output, metadata, True)
            self.assertEqual(unknown.read_bytes(), b"keep unrelated data")
            unknown.unlink()
            owned = output / first["packages"][0]["files"][0]["copy"]
            original = owned.read_bytes(); owned.write_bytes(b"user changed this output")
            with self.assertRaisesRegex(ValueError, "existing_output_text_changed"):
                notices.generate(repo, output, metadata, True)
            owned.write_bytes(original)
            private = base / "private"; private.write_bytes(b"outside stays intact")
            owned.unlink(); owned.symlink_to(private)
            with self.assertRaisesRegex(ValueError, "existing_output_text_not_verifiable"):
                notices.generate(repo, output, metadata, True)
            self.assertEqual(private.read_bytes(), b"outside stays intact")


if __name__ == "__main__":
    unittest.main()
