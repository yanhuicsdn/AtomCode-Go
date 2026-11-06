#!/usr/bin/env python3
"""Controlled public-PNG upload fixture runner for the two named owned AVDs.

Only the two named owned emulators are accepted. Installs the fixture APK only,
never the product; never clears data, changes settings, opens DocumentsUI, calls
a model or reads main-app files/configuration/credentials. A changed pre-existing
fixture APK is refused instead of overwritten. All grants target one fixed app.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import signal
import subprocess
import time

OWNER = "io.github.yanhuicsdn.atomcode"
FIXTURE = OWNER + ".uploadfixture"
RUNNER = FIXTURE + "/" + FIXTURE + ".UploadInstrumentation"
ACTIVITY = FIXTURE + "/" + FIXTURE + ".GrantActivity"
CONFIRM = "public-png-upload-fixture-v1"
AVDS = {"AtomCode_API36_16K_Arm64": 16384, "AtomCode_Backup_AOSP36_Arm64": 4096}
TOTAL = 300


class NotProven(Exception):
    pass


def require(value, label):
    if not value:
        raise NotProven(label)


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for data in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def certificate(apksigner, apk):
    result = subprocess.run([apksigner, "verify", "--print-certs", str(apk.resolve())], capture_output=True, timeout=20)
    values = re.findall(rb"^Signer #1 certificate SHA-256 digest: ([A-Fa-f0-9]{64})\s*$", result.stdout, re.M)
    require(result.returncode == 0 and len(values) == 1, "local-apk-signature-not-proven")
    return values[0].decode("ascii").lower()


def package_name(aapt2, apk):
    result = subprocess.run([aapt2, "dump", "badging", str(apk.resolve())], capture_output=True, timeout=20)
    values = re.findall(rb"^package: name='([^']+)'", result.stdout, re.M)
    require(result.returncode == 0 and len(values) == 1, "local-apk-package-not-proven")
    return values[0].decode("ascii")


class Device:
    def __init__(self, adb, serial, avd, total_deadline):
        self.prefix = [adb, "-s", serial]
        self.avd = avd
        self.total_deadline = total_deadline
        self.deadline = total_deadline - 60

    def remaining(self, cap=10):
        left = self.deadline - time.monotonic()
        require(left > 0, "bounded-phase-deadline-exceeded")
        return min(cap, left)

    def call(self, *args, check=True, timeout=10):
        result = subprocess.run(self.prefix + list(args), capture_output=True, timeout=self.remaining(timeout))
        require(not check or result.returncode == 0, "adb-operation-failed")
        return result

    def verify_owned(self):
        require(self.call("get-state").stdout.strip() == b"device", "owned-emulator-not-online")
        actual = self.call("emu", "avd", "name").stdout.decode("ascii").splitlines()[0].strip()
        require(actual == self.avd, "wrong-owned-avd")
        require(self.call("shell", "getprop", "ro.hardware").stdout.strip() == b"ranchu", "ranchu-required")
        require(self.call("shell", "getprop", "ro.kernel.qemu").stdout.strip() == b"1", "qemu-required")
        page = self.call("shell", "getconf", "PAGE_SIZE").stdout.decode("ascii").strip()
        require(page == str(AVDS[self.avd]), "wrong-owned-avd-page-size")
        return {"avd": actual, "pageSize": int(page), "is16kEnvironment": page == "16384"}

    def package_sha(self, package, missing_ok=False):
        result = self.call("shell", "pm", "path", package, check=False)
        if missing_ok and result.returncode in (0, 1) and not result.stdout and not result.stderr:
            return None
        require(result.returncode == 0, "installed-package-state-not-readable")
        lines = result.stdout.decode("ascii").splitlines()
        require(len(lines) == 1 and lines[0].startswith("package:"), "single-installed-base-required")
        path = lines[0][8:]
        require(re.fullmatch(r"/data/app/[A-Za-z0-9_~+./=-]+/base\.apk", path) is not None
                and ".." not in path.split("/"), "unsafe-installed-apk-path")
        digest = self.call("shell", "/system/bin/toybox", "sha256sum", path).stdout.split()[0].decode("ascii")
        require(re.fullmatch(r"[a-f0-9]{64}", digest) is not None, "invalid-installed-digest")
        return digest

    def stop(self, package):
        require(package in (OWNER, FIXTURE), "unsafe-stop-target")
        self.call("shell", "am", "force-stop", package)
        until = min(self.deadline, time.monotonic() + 12)
        while True:
            result = self.call("shell", "pidof", package, check=False)
            if result.returncode == 1 and not result.stdout and not result.stderr:
                return
            require(result.returncode == 0 and result.stdout.split() and all(p.isdigit() for p in result.stdout.split()),
                    "process-exit-not-observable")
            require(time.monotonic() < until, "test-package-did-not-exit")
            time.sleep(0.1)

    def action(self, action):
        require(action in ("REVOKE", "CLEAN"), "host-action-not-in-fixed-cleanup-allowlist")
        # GRANT is deliberately started inside the live target instrumentation,
        # followed by actual grant checks and FileReader in that same process.
        self.call("shell", "am", "start", "-W", "-a", FIXTURE + "." + action, "-n", ACTIVITY)

    def instrument(self, path, revoked=False, timeout=180):
        command = self.prefix + ["shell", "am", "instrument", "-w", "-e", "fixture_confirmation", CONFIRM]
        if revoked:
            command += ["-e", "check_revoked", "true"]
        command += [RUNNER]
        with path.open("wb") as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT)
            try:
                process.wait(timeout=self.remaining(timeout))
            except (subprocess.TimeoutExpired, KeyboardInterrupt, InterruptedError):
                saved_deadline = self.deadline
                self.deadline = self.total_deadline
                try:
                    try:
                        self.stop(OWNER)
                    finally:
                        try:
                            process.wait(timeout=self.remaining(8))
                        except Exception:
                            process.terminate()
                            try:
                                process.wait(timeout=self.remaining(3))
                            except Exception:
                                process.kill()
                                process.wait(timeout=1)
                finally:
                    self.deadline = saved_deadline
                raise NotProven("instrument-timeout-owner-stopped-same-handle-observed")
        text = path.read_text(errors="replace")
        values = re.findall(r"^UPLOAD_FIXTURE_RESULT: (\{[^\r\n]+\})$", text, re.M)
        require(process.returncode == 0 and len(values) == 1 and "FAIL:" not in text and "Process crashed" not in text,
                "instrument-did-not-produce-one-success-report")
        report = json.loads(values[0])
        require(report.get("status") == "passed" and report.get("mode") == ("revoked" if revoked else "file-reader")
                and report.get("distinctUids") is True and len(re.findall(r"^PASS:", text, re.M)) == (1 if revoked else 5),
                "public-fixture-checks-not-passed")
        if not revoked:
            require(report.get("actualFileReaderFiles") == 2 and report.get("fixedBase64AndBytesMatched") is True
                    and report.get("sameProcessRevocationVerified") is True
                    and report.get("productionAllowContentAccess") is False, "actual-byte-flow-or-revocation-not-proven")
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd", choices=list(AVDS), default="AtomCode_API36_16K_Arm64")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--aapt2", required=True, help="SDK aapt2, used only for local APK package identity")
    parser.add_argument("--apk", type=Path, required=True, help="Already installed product APK; never installed by this helper")
    parser.add_argument("--fixture-apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--confirm-public-png-test", action="store_true", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("phones are rejected before any ADB action")
    if args.output.exists():
        parser.error("--output must be a new directory")
    args.output.mkdir(parents=True)
    started = time.monotonic()
    device = Device(args.adb, args.serial, args.avd, started + TOTAL)
    report = {"status": "not-passed", "startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "scope": "real cross-UID public PNG FileReader; intercepted picker, not actual DocumentsUI selection or model upload",
              "credentialsRead": False, "modelRequestsSent": False, "productInstalledOrDataCleared": False,
              "settingsChanged": False, "automaticRetries": 0, "totalLimitSeconds": TOTAL}
    fixture_owned = False
    stage = "local-artifact-preflight"
    try:
        require(args.apk.is_file() and args.fixture_apk.is_file()
                and args.apk.resolve() != args.fixture_apk.resolve(), "distinct-completed-apks-required")
        require(args.confirm_public_png_test, "explicit-public-test-confirmation-required")
        require(package_name(args.aapt2, args.apk) == OWNER and package_name(args.aapt2, args.fixture_apk) == FIXTURE,
                "local-product-or-fixture-package-id-mismatch")
        main_cert, fixture_cert = certificate(args.apksigner, args.apk), certificate(args.apksigner, args.fixture_apk)
        require(main_cert == fixture_cert, "fixture-and-target-signatures-differ")
        report["appSha256"], report["fixtureSha256"] = sha(args.apk), sha(args.fixture_apk)
        report["sharedCertificateSha256"] = main_cert
        stage = "owned-emulator-preflight"
        report["environment"] = device.verify_owned()
        require(device.package_sha(OWNER) == report["appSha256"], "installed-product-apk-differs")
        existing = device.package_sha(FIXTURE, missing_ok=True)
        require(existing is None or existing == report["fixtureSha256"], "unknown-existing-fixture-apk-refused")
        if existing is None:
            install = device.call("install", "-t", str(args.fixture_apk.resolve()), timeout=90)
            require(b"Success" in install.stdout, "fixture-install-not-proven")
        require(device.package_sha(FIXTURE) == report["fixtureSha256"], "installed-fixture-apk-differs")
        fixture_owned = True
        device.stop(OWNER)
        stage = "prepare-no-grants"
        device.action("REVOKE")
        report["beforeGrant"] = device.instrument(args.output / "before-grant.txt", revoked=True, timeout=20)
        stage = "real-public-content-file-reader"
        report["reader"] = device.instrument(args.output / "file-reader.txt", timeout=180)
        report["status"] = "passed"
    except Exception as failure:
        report["failureStage"] = stage
        report["failureClass"] = type(failure).__name__
        if isinstance(failure, NotProven):
            report["classification"] = str(failure)
    except (KeyboardInterrupt, InterruptedError):
        report["failureStage"] = stage
        report["classification"] = "interrupted-no-automatic-retry"
    finally:
        device.deadline = device.total_deadline
        if fixture_owned:
            errors = []
            try:
                device.verify_owned()
            except Exception as failure:
                errors.append("owned-emulator-" + type(failure).__name__)
            if not errors:
                for name, operation in [
                    ("stop-owner", lambda: device.stop(OWNER)),
                    ("revoke", lambda: device.action("REVOKE")),
                    ("verify-revoked", lambda: report.update(afterRevoke=device.instrument(args.output / "after-revoke.txt", revoked=True, timeout=20))),
                    ("clean-known-canaries", lambda: device.action("CLEAN")),
                    ("stop-fixture", lambda: device.stop(FIXTURE)),
                    ("stop-owner-final", lambda: device.stop(OWNER)),
                ]:
                    try:
                        operation()
                    except Exception as failure:
                        errors.append(name + "-" + type(failure).__name__)
                try:
                    state = device.call("shell", "-T", "run-as", FIXTURE, "/system/bin/toybox", "stat", "-c", "%F", "files/public-png-upload-v1", check=False)
                    require(state.returncode != 0 and b"no such file" in state.stderr.lower(), "canary-directory-not-removed")
                    report["fixedCanaryDirectoryAbsent"] = True
                except Exception as failure:
                    errors.append("canary-cleanup-" + type(failure).__name__)
            if errors:
                report["status"] = "not-passed"
                report["cleanupErrors"] = errors
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        report["finishedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (args.output / "upload-fixture-summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Public PNG fixture: " + report["status"])
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    def interrupted(_signum, _frame):
        raise InterruptedError()
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
