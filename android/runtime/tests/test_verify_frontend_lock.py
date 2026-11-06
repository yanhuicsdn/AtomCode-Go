import base64
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import verify_frontend_lock as frontend


SRI = "sha512-" + base64.b64encode(bytes(range(64))).decode("ascii")


def pinned(name="demo", version="1.0.0"):
    return {"version": version, "resolved": f"https://registry.npmjs.org/{name}/-/{name}-{version}.tgz", "integrity": SRI}


def fixture():
    return {"lockfileVersion": 3, "packages": {"": {"name": "frontend"}, "node_modules/demo": pinned()}}


class FrontendLockTests(unittest.TestCase):
    def test_alias_nested_versions_and_bundle_are_preserved(self):
        lock = fixture()
        lock["packages"]["node_modules/demo"]["name"] = "actual-alias-target"
        lock["packages"]["node_modules/demo/node_modules/demo"] = pinned(version="2.0.0")
        lock["packages"]["node_modules/demo/node_modules/child"] = {"version": "1.1.3", "inBundle": True}
        before = copy.deepcopy(lock)
        self.assertEqual(frontend.verify(lock), {"entries": 3, "pinned": 2, "bundled": 1})
        self.assertEqual(lock, before)

    def test_missing_artifact_pin_fails(self):
        for field in ("resolved", "integrity"):
            lock = fixture()
            del lock["packages"]["node_modules/demo"][field]
            with self.subTest(field=field), self.assertRaises(frontend.LockError):
                frontend.verify(lock)

    def test_untrusted_or_credential_bearing_urls_fail_without_echo(self):
        for url in ("http://registry.npmjs.org/demo.tgz", "https://registry.npmjs.org.attacker.test/demo.tgz",
                    "https://secret@registry.npmjs.org/demo.tgz", "https://registry.npmjs.org/demo.tgz?token=secret",
                    "https://registry.npmjs.org/demo.tgz#secret", "https://registry.npmjs.org:bad/demo.tgz",
                    "https://registry.npmjs.org/\nsecret.tgz"):
            lock = fixture()
            lock["packages"]["node_modules/demo"]["resolved"] = url
            with self.subTest(url=url), self.assertRaises(frontend.LockError) as error:
                frontend.verify(lock)
            self.assertNotIn("secret", str(error.exception))
            self.assertNotIn(url, str(error.exception))

    def test_existing_mirror_is_accepted(self):
        lock = fixture()
        entry = lock["packages"]["node_modules/demo"]
        entry["resolved"] = entry["resolved"].replace("registry.npmjs.org", "registry.npmmirror.com")
        self.assertEqual(frontend.verify(lock)["pinned"], 1)

    def test_bad_or_weak_integrity_fails(self):
        for integrity in (None, "sha1-" + base64.b64encode(bytes(20)).decode(), "sha512-!invalid!",
                          "sha512-" + base64.b64encode(bytes(63)).decode(), SRI + " ", SRI[:-2] + "AB"):
            lock = fixture()
            lock["packages"]["node_modules/demo"]["integrity"] = integrity
            with self.subTest(integrity=integrity), self.assertRaises(frontend.LockError):
                frontend.verify(lock)

    def test_bundle_requires_a_present_pinned_parent(self):
        cases = [
            {"node_modules/orphan": {"version": "1", "inBundle": True}},
            {"node_modules/absent/node_modules/child": {"version": "1", "inBundle": True}},
            {"node_modules/demo": {"version": "1"}, "node_modules/demo/node_modules/child": {"version": "1", "inBundle": True}},
            {"node_modules/demo": pinned(), "node_modules/demo/node_modules/child": {**pinned(), "inBundle": True}},
        ]
        for entries in cases:
            lock = {"lockfileVersion": 3, "packages": {"": {}, **entries}}
            with self.subTest(entries=list(entries)), self.assertRaises(frontend.LockError):
                frontend.verify(lock)

    def test_bundle_can_resolve_through_a_bundled_parent(self):
        lock = fixture()
        for path in ("node_modules/demo/node_modules/child", "node_modules/demo/node_modules/child/node_modules/@scope/grandchild"):
            lock["packages"][path] = {"version": "1", "inBundle": True}
        self.assertEqual(frontend.verify(lock), {"entries": 3, "pinned": 1, "bundled": 2})

    def test_invalid_structure_is_rejected(self):
        for malformed in (None, {}, {"lockfileVersion": [], "packages": {}}, {"lockfileVersion": 3, "packages": {"": {}}}):
            with self.subTest(malformed=malformed), self.assertRaises(frontend.LockError):
                frontend.verify(malformed)
        for path in ("node_modules/../escape", "node_modules/a/extra", "node_modules/@scope", "node_modules/secret\n"):
            lock = {"lockfileVersion": 3, "packages": {"": {}, path: pinned()}}
            with self.subTest(path=path), self.assertRaises(frontend.LockError):
                frontend.verify(lock)
        lock = fixture()
        lock["packages"]["node_modules/demo"]["inBundle"] = "true"
        with self.assertRaises(frontend.LockError):
            frontend.verify(lock)


if __name__ == "__main__":
    unittest.main()
