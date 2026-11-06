import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location("runtime_build", Path(__file__).parents[1] / "build.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class RuntimeArchiveTests(unittest.TestCase):
    def test_path_traversal_is_rejected_before_normalization(self):
        for path in ("../outside", "etc/../../outside", "/absolute", "etc//passwd", "a\\b"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                build.member_path(path)

    def test_linux_absolute_links_become_relative_and_cannot_escape(self):
        self.assertEqual(build.link_target("bin", "/usr/bin"), "usr/bin")
        self.assertEqual(build.link_target("etc/mtab", "/proc/mounts"), "../proc/mounts")
        with self.assertRaises(ValueError):
            build.link_target("etc/link", "../../host")

    def test_children_cannot_be_written_through_a_link(self):
        entries = {
            "bin": {"kind": "link", "target": "usr/bin"},
            "bin/tool": {"kind": "file", "mode": 0o755, "body": b"payload"},
        }
        with self.assertRaisesRegex(ValueError, "below link"):
            build.resolve_files(entries)

    def test_hardlinks_become_regular_files_and_cycles_fail(self):
        entries = {
            "tool": {"kind": "file", "mode": 0o755, "body": b"tool bytes"},
            "alias": {"kind": "hardlink", "target": "tool"},
        }
        build.resolve_files(entries)
        self.assertEqual(entries["alias"]["body"], b"tool bytes")
        with self.assertRaisesRegex(ValueError, "unresolved"):
            build.resolve_files({"a": {"kind": "hardlink", "target": "b"},
                                 "b": {"kind": "hardlink", "target": "a"}})

    def test_payload_is_deterministic_and_contains_no_archive_symlinks(self):
        entries = {
            "usr/bin/tool": {"kind": "file", "mode": 0o755, "body": b"tool"},
            "bin": {"kind": "link", "mode": 0o777, "target": "usr/bin"},
        }
        with tempfile.TemporaryDirectory() as directory:
            first, second = Path(directory) / "one.zip", Path(directory) / "two.zip"
            meta = build.write_payload(entries, first)
            build.write_payload(dict(reversed(list(entries.items()))), second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(meta["uncompressedBytes"], 4)
            self.assertEqual(meta["links"], {"bin": "usr/bin"})
            with zipfile.ZipFile(first) as archive:
                self.assertEqual(archive.read("usr/bin/tool"), b"tool")
                self.assertNotIn("bin", archive.namelist())

    def test_real_tar_traversal_is_rejected(self):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w") as archive:
            item = tarfile.TarInfo("../../outside")
            item.size = 1
            archive.addfile(item, io.BytesIO(b"x"))
        with self.assertRaises(ValueError):
            build.read_entries(data.getvalue())

    def test_corrupt_download_cannot_replace_cached_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "upstream"
            source.write_bytes(b"wrong bytes")
            cache = root / "cache"
            cache.mkdir()
            (cache / "input").write_bytes(b"old invalid bytes")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                build.fetch({"name": "input", "url": source.as_uri(),
                             "sha256": hashlib.sha256(b"expected bytes").hexdigest()}, cache)
            self.assertEqual((cache / "input").read_bytes(), b"old invalid bytes")
            self.assertEqual(list(cache.glob("*.part")), [])


if __name__ == "__main__":
    unittest.main()
