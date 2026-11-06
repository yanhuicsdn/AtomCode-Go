import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("source_inventory", Path(__file__).parents[1] / "source_inventory.py")
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


def tar_bytes(members):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as archive:
        for name, content in members:
            entry = tarfile.TarInfo(name)
            entry.size = len(content)
            archive.addfile(entry, io.BytesIO(content))
    return output.getvalue()


def deb_bytes(control):
    output = bytearray(b"!<arch>\n")
    for name, data in [("debian-binary", b"2.0\n"), ("control.tar.gz", tar_bytes([("./control", control)]))]:
        header = f"{name + '/':<16}{0:<12}{0:<6}{0:<6}{'100644':<8}{len(data):<10}`\n".encode("ascii")
        assert len(header) == 60
        output.extend(header)
        output.extend(data)
        if len(data) % 2:
            output.extend(b"\n")
    return bytes(output)


class SourceInventoryTests(unittest.TestCase):
    def test_source_epoch_and_binary_only_rebuild_version(self):
        value = inventory.package_record({"Package": "git", "Architecture": "arm64", "Version": "1:2.43-1+b1", "Source": "git (1:2.43-1)"})
        self.assertEqual(value["sourceVersion"], "1:2.43-1")
        self.assertEqual(inventory.source_url("git", value["sourceVersion"]), "https://launchpad.net/ubuntu/+source/git/1%3A2.43-1")

    def test_duplicate_fields_and_malformed_sources_fail(self):
        with self.assertRaises(ValueError):
            inventory.paragraphs("Package: first\nPackage: second\n")
        with self.assertRaises(ValueError):
            inventory.package_record({"Package": "git", "Architecture": "arm64", "Version": "1", "Source": "../../outside (1)"})

    def test_metadata_paths_are_exact_and_links_not_followed(self):
        data = tar_bytes([("../var/lib/dpkg/status", b"not accepted")])
        with tarfile.open(fileobj=io.BytesIO(data)) as archive, self.assertRaises(ValueError):
            inventory.read_tar_member(archive, "var/lib/dpkg/status")
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w") as archive:
            link = tarfile.TarInfo("var/lib/dpkg/status")
            link.type = tarfile.SYMTYPE
            link.linkname = "/etc/passwd"
            archive.addfile(link)
        data.seek(0)
        with tarfile.open(fileobj=data) as archive, self.assertRaises(ValueError):
            inventory.read_tar_member(archive, "var/lib/dpkg/status")

    def fixture(self, root):
        status = b"Package: perl-base\nStatus: install ok installed\nArchitecture: arm64\nVersion: 1\nSource: perl\n\n"
        base = tar_bytes([("var/lib/dpkg/status", status)])
        deb = deb_bytes(b"Package: perl-base\nArchitecture: arm64\nVersion: 2+b1\nSource: perl (2)\n")
        inputs = []
        for name, data in [("ubuntu-base-fixture.tar.gz", base), ("perl-base_2+b1_arm64.deb", deb)]:
            (root / name).write_bytes(data)
            inputs.append({"name": name, "sha256": hashlib.sha256(data).hexdigest()})
        inputs[1].update(package="perl-base", version="2+b1", sourcePackage="perl", sourceVersion="2")
        dependencies = root / "dependencies.json"
        dependencies.write_text(json.dumps({"schema": 1, "inputs": inputs}))
        return dependencies

    def test_overlay_replaces_base_identity_and_verifies_control_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dependencies = self.fixture(root)
            result = inventory.generate(dependencies, root)
            self.assertEqual(result["ubuntuBinaryPackageCount"], 1)
            self.assertEqual(result["ubuntuPackages"][0]["version"], "2+b1")
            self.assertEqual(result["ubuntuPackages"][0]["sourceVersion"], "2")
            self.assertEqual(result["replacedBasePackages"][0]["baseVersion"], "1")
            self.assertFalse(result["completeCorrespondingSourceDelivered"])
            changed = json.loads(dependencies.read_text())
            changed["inputs"][1]["sourceVersion"] = "wrong"
            dependencies.write_text(json.dumps(changed))
            with self.assertRaisesRegex(ValueError, "disagrees"):
                inventory.generate(dependencies, root)

    def test_corrupt_cached_bytes_are_rejected_before_metadata_is_trusted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dependencies = self.fixture(root)
            (root / "perl-base_2+b1_arm64.deb").write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                inventory.generate(dependencies, root)


if __name__ == "__main__":
    unittest.main()
