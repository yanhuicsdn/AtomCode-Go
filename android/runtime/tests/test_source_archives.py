import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import source_archives as source


URL = "https://archive.ubuntu.com/ubuntu/pool/main/d/demo/demo_1.0.dsc"


def dsc(body=b"source bytes", filename="demo_1.0.orig.tar.xz", version="1:1.0-1", signed=True):
    text = ("Format: 3.0 (quilt)\nSource: demo\nVersion: " + version
            + "\nChecksums-Sha256:\n " + hashlib.sha256(body).hexdigest()
            + " " + str(len(body)) + " " + filename + "\n")
    if signed:
        text = ("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n" + text
                + "\n-----BEGIN PGP SIGNATURE-----\nfixture-not-a-verified-signature\n-----END PGP SIGNATURE-----\n")
    return text.encode()


class Response(io.BytesIO):
    status = 200

    def __init__(self, url, data):
        super().__init__(data)
        self.url = url

    def geturl(self):
        return self.url


class MemoryPublisher:
    def __init__(self, files):
        self.files, self.calls = files, []

    def open(self, url, timeout):
        self.calls.append(url)
        return Response(url, self.files[url])


class SourceArchivesTests(unittest.TestCase):
    def manifest(self):
        inventory = {"dependenciesSha256": "a" * 64, "ubuntuSources": [{"package": "demo", "version": "1:1.0-1"}]}
        return source.plan(inventory, "demo", dsc(), URL)

    def test_exact_epoch_and_envelope_are_not_signature_authentication(self):
        manifest = self.manifest()
        self.assertEqual(manifest["version"], "1:1.0-1")
        self.assertTrue(manifest["signatureEnvelopePresent"])
        self.assertFalse(manifest["signatureVerified"])
        self.assertFalse(manifest["completeCorrespondingSourceDelivered"])
        with self.assertRaisesRegex(ValueError, "source/version"):
            source.descriptor(dsc(version="1.0-1"), "demo", "1:1.0-1")
        with self.assertRaisesRegex(ValueError, "identity"):
            source.descriptor(dsc(), "../escape", "1:1.0-1")

    def test_archive_names_duplicates_and_binary_descriptors_are_rejected(self):
        for name in ("../outside", "/absolute", "a\\b", ".hidden", "a/b"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                source.descriptor(dsc(filename=name), "demo", "1:1.0-1")
        raw = dsc(signed=False)
        row = raw.splitlines()[-1]
        with self.assertRaisesRegex(ValueError, "duplicate"):
            source.descriptor(raw + row + b"\n", "demo", "1:1.0-1")
        with self.assertRaises(ValueError):
            source.descriptor(raw + b"\0", "demo", "1:1.0-1")

    def test_mixed_source_versions_require_an_exact_inventory_selection(self):
        inventory = {"dependenciesSha256": "a" * 64, "ubuntuSources": [
            {"package": "demo", "version": "1:1.0-1"}, {"package": "demo", "version": "1:2.0-1"}]}
        with self.assertRaisesRegex(ValueError, "select a version"):
            source.plan(inventory, "demo", dsc(), URL)
        selected = source.plan(inventory, "demo", dsc(), URL, "1:1.0-1")
        self.assertEqual(selected["version"], "1:1.0-1")

    def test_complete_subset_rechecks_bytes_and_uses_verified_cache(self):
        manifest = self.manifest()
        publisher = MemoryPublisher({URL: dsc(), manifest["files"][0]["url"]: b"source bytes"})
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory)
            receipt = source.acquire(manifest, out, publisher)
            self.assertTrue(receipt["archiveSetComplete"])
            self.assertFalse(receipt["signatureVerified"])
            self.assertFalse(receipt["completeCorrespondingSourceDelivered"])
            self.assertEqual(len(publisher.calls), 2)
            for item in receipt["files"]:
                self.assertEqual(source.sha256(out / item["relativePath"]), item["sha256"])
            cached = source.acquire(manifest, out, publisher)
            self.assertTrue(all(item["cacheHit"] for item in cached["files"]))
            self.assertEqual(len(publisher.calls), 2)

    def test_corrupt_transfer_never_commits_and_existing_bad_cache_is_preserved(self):
        item = self.manifest()["files"][0]
        publisher = MemoryPublisher({item["url"]: b"wrong bytes!"})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                source.fetch(item, root, source.FILE_LIMIT, publisher)
            self.assertFalse((root / item["sha256"] / item["name"]).exists())
            self.assertEqual(list(root.rglob("*.part")), [])
            existing = root / item["sha256"] / item["name"]
            existing.write_bytes(b"leave this existing file")
            calls = len(publisher.calls)
            with self.assertRaisesRegex(ValueError, "preserved"):
                source.fetch(item, root, source.FILE_LIMIT, publisher)
            self.assertEqual(existing.read_bytes(), b"leave this existing file")
            self.assertEqual(len(publisher.calls), calls)

    def test_manifest_cannot_change_descriptor_members_or_sibling_urls(self):
        manifest = self.manifest()
        for mutation in ("member", "url"):
            changed = copy.deepcopy(manifest)
            if mutation == "member":
                changed["files"][0]["name"] = "other.tar.xz"
            else:
                changed["files"][0]["url"] = "https://archive.ubuntu.com/ubuntu/other.tar.xz"
            publisher = MemoryPublisher({URL: dsc()})
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
                source.acquire(changed, Path(directory), publisher)
            self.assertEqual(publisher.calls, [URL])

    def test_untrusted_origins_and_symlink_cache_entries_are_rejected(self):
        for url in ("http://archive.ubuntu.com/x", "https://example.com/x", "https://user:pass@archive.ubuntu.com/x",
                    "https://archive.ubuntu.com/x?token=example"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                source.official_url(url)
        item = self.manifest()["files"][0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outside = root / "outside"
            outside.write_bytes(b"keep")
            destination = root / "cache" / item["sha256"]
            destination.mkdir(parents=True)
            (destination / item["name"]).symlink_to(outside)
            with self.assertRaisesRegex(ValueError, "symlink"):
                source.fetch(item, root / "cache", source.FILE_LIMIT, MemoryPublisher({}))
            self.assertEqual(outside.read_bytes(), b"keep")

    def test_receipts_keep_two_versions_of_one_source_package_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = source.receipt_path(root, "openssl", "3.0.13-0ubuntu3.15")
            second = source.receipt_path(root, "openssl", "3.0.13-0ubuntu3.16")
            self.assertNotEqual(first, second)
            first.write_text("first-version-evidence")
            second.write_text("second-version-evidence")
            self.assertEqual(first.read_text(), "first-version-evidence")
            with self.assertRaises(ValueError):
                source.receipt_path(root, "../escape", "1.0")


if __name__ == "__main__":
    unittest.main()
