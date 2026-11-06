#!/usr/bin/env python3
"""Run bounded real-space pressure only on the explicitly owned 16KB emulator.

Install the supplied app/test APKs first. This never starts an AVD, installs an
APK, clears app data, changes settings, accesses real home, or calls a model.
Use the AVD's default userdata location (no emulator -data override). At most
6GiB of sequential non-sparse filler is written inside one new app-files fixture;
the device retains at least 256MiB. This is not physical NAND exhaustion.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
import zipfile


OWNER = "io.github.yanhuicsdn.atomcode"
RUNNER = OWNER + ".test/" + OWNER + ".LocalRuntimeSmoke"
OWNED_AVD = "AtomCode_API36_16K_Arm64"
CONFIRMATION = "owned-emulator-fs-pressure-v1"
MIB = 1024 * 1024
MAX_FILL = 6 * 1024 * MIB
RESERVE = 256 * MIB
HOST_TIMEOUT = 360


class NotProven(Exception):
    pass


def require(condition, classification):
    if not condition:
        raise NotProven(classification)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(MIB), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_real_space_runner(path):
    # An old LocalRuntimeSmoke ignores unknown extras and runs its default UI
    # suite. Reject that APK locally before contacting or stopping the AVD.
    with zipfile.ZipFile(path) as apk:
        dex = [entry for entry in apk.infolist() if re.fullmatch(r"classes(?:[0-9]+)?\.dex", entry.filename)]
        require(0 < len(dex) <= 4 and sum(entry.file_size for entry in dex) <= 16 * MIB,
                "test-apk-dex-not-bounded")
        descriptor = b"Lio/github/yanhuicsdn/atomcode/RealSpaceAcceptance;"
        require(any(descriptor in (data := apk.read(entry)) and CONFIRMATION.encode("ascii") in data for entry in dex),
                "test-apk-has-no-real-space-runner-old-default-suite-refused")


class Device:
    def __init__(self, adb, serial):
        self.prefix = [adb, "-s", serial]

    def call(self, *arguments, check=True, timeout=30):
        result = subprocess.run(self.prefix + list(arguments), capture_output=True, timeout=timeout)
        require(not check or result.returncode == 0, "adb-operation-failed")
        return result

    def fixture_stat(self, path):
        require(re.fullmatch(r"files/real-space-acceptance-[a-f0-9]{32}", path) is not None,
                "unsafe-fixture-path")
        result = self.call("shell", "-T", "run-as", OWNER, "/system/bin/toybox", "stat", "-c", "%F", path,
                           check=False)
        if result.returncode:
            require(b"no such file" in result.stderr.lower(), "fixture-state-not-readable")
            return None
        return result.stdout.decode("ascii").strip()

    def verify_package(self, package, expected):
        result = self.call("shell", "pm", "path", package)
        lines = result.stdout.decode("ascii").splitlines()
        require(len(lines) == 1 and lines[0].startswith("package:"), "installed-package-not-single-base")
        path = lines[0][8:]
        require(re.fullmatch(r"/data/app/[A-Za-z0-9_~+./=-]+/base\.apk", path) is not None
                and ".." not in path.split("/"), "unsafe-installed-apk-path")
        result = self.call("shell", "/system/bin/toybox", "sha256sum", path, timeout=60)
        require(result.stdout.split(maxsplit=1)[0].decode("ascii") == expected,
                "installed-apk-does-not-match-supplied-artifact")

    def verify_owned(self, expected_avd):
        require(self.call("get-state").stdout.strip() == b"device", "emulator-not-online")
        name = self.call("emu", "avd", "name").stdout.decode("ascii").splitlines()[0].strip()
        require(name == expected_avd, "avd-not-in-exact-owned-allowlist")
        hardware = self.call("shell", "getprop", "ro.hardware").stdout.decode("ascii").strip()
        require(hardware in ("ranchu", "goldfish"), "emulator-hardware-required")
        require(self.call("shell", "getprop", "ro.kernel.qemu").stdout.strip() == b"1", "qemu-device-required")
        require(self.call("shell", "getconf", "PAGE_SIZE").stdout.strip() == b"16384", "owned-16k-runtime-required")
        return hardware

    def stop_owner(self):
        self.call("shell", "am", "force-stop", OWNER)
        deadline = time.monotonic() + 15
        while True:
            result = self.call("shell", "pidof", OWNER, check=False)
            if result.returncode == 1 and not result.stdout and not result.stderr:
                return
            require(result.returncode == 0 and all(value.isdigit() for value in result.stdout.split()),
                    "owner-exit-not-observable")
            require(time.monotonic() < deadline, "owner-did-not-exit")
            time.sleep(0.2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Only emulator-*; phones are rejected before ADB")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--avd-name", choices=[OWNED_AVD], default=OWNED_AVD)
    parser.add_argument("--apk", type=Path, required=True, help="Already installed app APK; never installed by this script")
    parser.add_argument("--tests-apk", type=Path, required=True, help="Already installed matching test APK")
    parser.add_argument("--output", type=Path, required=True, help="New or empty evidence directory")
    parser.add_argument("--confirm-fill-owned-emulator", action="store_true", required=True,
                        help="Explicitly permit bounded filesystem pressure on this owned AVD only")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("only emulator-* is allowed; phones are never targeted")
    if not args.confirm_fill_owned_emulator:
        parser.error("bounded filling requires --confirm-fill-owned-emulator")
    if args.output.exists() and (not args.output.is_dir() or any(args.output.iterdir())):
        parser.error("--output must be new or empty; previous evidence is never overwritten")
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"schema": 1, "startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "status": "not-passed", "serial": args.serial, "expectedAvd": args.avd_name,
              "scope": "real same-data-filesystem pressure, not physical NAND exhaustion or mocked free space",
              "productionApkInstalledByCoordinator": False, "appDataCleared": False,
              "settingsChanged": False, "credentialsRead": False, "modelRequestsSent": False}
    case_id = uuid.uuid4().hex
    fixture = "files/real-space-acceptance-" + case_id
    report["fixtureRelativePath"] = fixture
    device = Device(args.adb, args.serial)
    authorized = False
    fresh_fixture_proved = False
    execution_attempted = False
    stage = "local-preflight"
    try:
        require(args.apk.is_file() and args.tests_apk.is_file() and args.apk.resolve() != args.tests_apk.resolve(),
                "completed-app-and-test-apks-required")
        require_real_space_runner(args.tests_apk)
        app_hash, tests_hash = sha256(args.apk), sha256(args.tests_apk)
        with zipfile.ZipFile(args.apk) as apk:
            entry = apk.getinfo("assets/runtime/manifest.json")
            require(0 < entry.file_size <= MIB, "runtime-manifest-not-bounded")
            manifest = json.loads(apk.read(entry))
        require(re.fullmatch(r"atomcode-[a-f0-9]{16}", manifest.get("runtimeVersion", "")) is not None,
                "invalid-apk-runtime-version")
        report["artifacts"] = {"appSha256": app_hash, "testsSha256": tests_hash,
                               "runtimeVersion": manifest["runtimeVersion"]}
        # Filling grows emulator userdata on the host too. The supported owned
        # AVD uses its default directory and must retain >=2GiB host headroom.
        avd_data = Path.home() / ".android" / "avd" / (args.avd_name + ".avd")
        require(avd_data.is_dir() and not avd_data.is_symlink(), "owned-default-avd-directory-required")
        host_free = shutil.disk_usage(avd_data).free
        report["hostDefaultAvdFilesystemFreeBytes"] = host_free
        require(host_free >= MAX_FILL + 2 * 1024 * MIB, "insufficient-host-headroom-for-bounded-fill")

        stage = "owned-emulator-preflight"
        hardware = device.verify_owned(args.avd_name)
        device.verify_package(OWNER, app_hash)
        device.verify_package(OWNER + ".test", tests_hash)
        registered = device.call("shell", "pm", "list", "instrumentation", OWNER).stdout.decode("ascii")
        require(RUNNER in registered and "target=" + OWNER in registered, "matching-owner-runner-not-registered")
        authorized = True
        require(device.fixture_stat(fixture) is None, "unique-fixture-already-exists")
        fresh_fixture_proved = True
        report["hardware"] = hardware
        report["pageSize"] = 16384
        stage = "bounded-real-space-instrumentation"
        device.stop_owner()  # Only this owned emulator; no UI or model operation.
        execution_attempted = True
        result = device.call("shell", "am", "instrument", "-w", "-e", "real_space", "true",
                             "-e", "real_space_confirmation", CONFIRMATION, "-e", "real_space_case", case_id,
                             RUNNER, check=False, timeout=HOST_TIMEOUT)
        output = result.stdout + result.stderr
        (args.output / "device-test.txt").write_bytes(output)
        text = output.decode("utf-8", errors="replace")
        matches = re.findall(r"^REAL_SPACE_REPORT: (\{[^\r\n]+\})$", text, re.M)
        require(result.returncode == 0 and len(matches) == 1, "device-test-did-not-produce-one-complete-report")
        metrics = json.loads(matches[0])
        report["device"] = metrics
        require(len(re.findall(r"^PASS:", text, re.M)) == 5 and "FAIL:" not in text and "Process crashed" not in text
                and metrics.get("status") == "passed", "real-space-checks-not-passed")
        require(metrics.get("fixtureRelativePath") == fixture and metrics.get("reserveBytes") == RESERVE,
                "device-fixture-or-reserve-mismatch")
        require(metrics.get("runtimeVersion") == manifest["runtimeVersion"], "device-runtime-does-not-match-apk")
        require(metrics["lowFileUsableBytes"] >= RESERVE
                and metrics["lowFileUsableBytes"] < metrics["requiredBytes"]
                and metrics["fillerAllocatedBytes"] >= metrics["fillerWrittenBytes"] > 0
                and metrics["fillerWrittenBytes"] <= MAX_FILL,
                "actual-pressure-and-non-sparse-allocation-not-proved")
        require(metrics.get("spacePreflightRejected") is True and metrics.get("extractionProgressCallbacks") == 0
                and metrics.get("oldPointerReadyDaemonCanaryUnchanged") is True
                and metrics.get("reinstallAfterReleaseSucceeded") is True
                and metrics.get("fixtureAndFillerRemoved") is True, "preservation-or-recovery-not-proved")
        require(device.fixture_stat(fixture) is None, "fixture-remains-after-device-cleanup")
        report["status"] = "passed"
        report["checksPassed"] = 5
        report["classification"] = "actual-space-preflight-preserves-fixture-and-recovers-after-release"
    except subprocess.TimeoutExpired as failure:
        (args.output / "device-test.txt").write_bytes((failure.stdout or b"") + (failure.stderr or b""))
        report["classification"] = "bounded-test-deadline-exceeded-not-a-pass"
    except NotProven as failure:
        report["classification"] = str(failure)  # Only fixed local classification strings.
    except Exception as failure:
        report["classification"] = "check-not-proven"
        report["exceptionClass"] = type(failure).__name__
    finally:
        report["lastStage"] = stage
        if authorized and fresh_fixture_proved and execution_attempted:
            try:
                # A lost host transport can leave remote instrumentation alive.
                # Stop it before inspecting/removing this one newly owned path.
                device.verify_owned(args.avd_name)  # The serial may have been reused after a disconnect.
                device.stop_owner()
                kind = device.fixture_stat(fixture)
                if kind is not None:
                    require(kind == "directory", "leftover-fixture-is-not-a-real-directory")
                    device.call("shell", "-T", "run-as", OWNER, "/system/bin/toybox", "rm", "-f", "--",
                                fixture + "/non-sparse-filler.bin")
                    device.call("shell", "-T", "run-as", OWNER, "/system/bin/toybox", "rm", "-rf", "--", fixture,
                                timeout=60)
                    require(device.fixture_stat(fixture) is None, "leftover-fixture-removal-not-proved")
                    report["hostRemovedOnlyThisFixture"] = True
                    report["status"] = "not-passed"  # Host rescue is not device cleanup success.
                    report["classification"] = "device-cleanup-incomplete-host-rescue-not-a-pass"
                report["fixtureAbsentAfterCleanup"] = True
            except Exception as failure:
                report["status"] = "not-passed"
                report["cleanupFailureClass"] = type(failure).__name__
        report["finishedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (args.output / "real-space-summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Real-space acceptance: " + report["status"] + "; " + report.get("classification", "unknown"))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
