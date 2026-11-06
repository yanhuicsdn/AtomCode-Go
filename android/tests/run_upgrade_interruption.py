#!/usr/bin/env python3
"""Check a real pre-commit runtime upgrade force-stop on an owned emulator.

Start the explicitly owned AVD yourself and keep it unlocked. Supply a completed
new app APK and its matching device-test APK. This does not start/clear an AVD,
clear app data, call a model, or read credentials. A unique workspace canary is
retained for inspection. A missed extraction window is NOT a passing result.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import zipfile


OWNER = "io.github.yanhuicsdn.atomcode"
TEST_PACKAGE = OWNER + ".test"
RUNNER = TEST_PACKAGE + "/" + OWNER + ".LocalRuntimeSmoke"
OWNED_AVDS = ("AtomCode_API36_16K_Arm64", "AtomCode_API36_Arm64")
GENERATION = re.compile(r"(atomcode-[a-f0-9]{16})-[a-f0-9]{32}\Z")
SHA256 = re.compile(r"[a-f0-9]{64}\Z")
STAGING = "files/runtime/staging"
# A small ordinary file early in the pinned Ubuntu ZIP, not a completion marker.
EXTRACTION_WITNESS = STAGING + "/etc/bash.bashrc"


class NotProven(Exception):
    def __init__(self, classification, may_still_run=False):
        super().__init__(classification)
        self.classification = classification
        self.may_still_run = may_still_run


def require(condition, classification):
    if not condition:
        raise NotProven(classification)


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds")


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for data in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def apk_manifest(path):
    require(path.is_file(), "app-apk-not-available")
    with zipfile.ZipFile(path) as archive:
        names = [entry for entry in archive.infolist() if entry.filename == "assets/runtime/manifest.json"]
        require(len(names) == 1 and 0 < names[0].file_size <= 1024 * 1024,
                "runtime-manifest-not-unique-or-bounded")
        manifest = json.loads(archive.read(names[0]))
        # The external observation must name a real ordinary entry in this
        # supplied payload, not assume that an arbitrary stage path exists.
        with archive.open("assets/runtime/rootfs.zip") as payload, zipfile.ZipFile(payload) as rootfs:
            witness = rootfs.getinfo("etc/bash.bashrc")
            require(not witness.is_dir() and witness.file_size > 0, "payload-has-no-extraction-witness")
    require(manifest.get("schema") == 1 and manifest.get("abi") == "arm64-v8a", "unsupported-runtime-manifest")
    version = manifest.get("runtimeVersion", "")
    root_hash = manifest.get("rootfsSha256", "")
    daemon_hash = manifest.get("build", {}).get("daemonSha256", "")
    require(re.fullmatch(r"atomcode-[a-f0-9]{16}", version) is not None, "invalid-runtime-version")
    require(SHA256.fullmatch(root_hash) is not None and SHA256.fullmatch(daemon_hash) is not None,
            "invalid-runtime-digests")
    return {"runtimeVersion": version, "rootfsSha256": root_hash, "daemonSha256": daemon_hash}


class Device:
    def __init__(self, adb, serial):
        self.prefix = [adb, "-s", serial]

    def call(self, *arguments, check=True, timeout=30, data=None):
        result = subprocess.run(self.prefix + list(arguments), input=data,
                                capture_output=True, timeout=timeout)
        if check and result.returncode:
            # Do not echo commands, arbitrary stderr, paths, headers or URLs.
            raise NotProven("adb-operation-failed")
        return result

    @staticmethod
    def private_path(path):
        require(re.fullmatch(r"files(?:/[A-Za-z0-9_./-]+)?", path) is not None
                and all(part not in ("", ".", "..") for part in path.split("/")),
                "unsafe-private-test-path")
        return path

    def stat(self, path, missing_ok=False):
        path = self.private_path(path)
        # Shell-v2 (-T, no PTY) carries the remote exit code and separate
        # stderr. exec-out cannot reliably distinguish ENOENT by exit status.
        result = self.call("shell", "-T", "run-as", OWNER, "/system/bin/toybox", "stat", "-c", "%F:%s", path,
                           check=False)
        if result.returncode:
            if missing_ok and b"no such file" in result.stderr.lower():
                return None
            raise NotProven("private-path-not-readable")
        value = result.stdout.decode("ascii", errors="strict").strip().rsplit(":", 1)
        require(len(value) == 2 and value[1].isdigit(), "invalid-device-stat")
        return {"type": value[0], "bytes": int(value[1])}

    def read(self, path, limit=256):
        info = self.stat(path)
        require(info["type"] == "regular file" and info["bytes"] <= limit, "private-record-not-bounded-file")
        result = self.call("shell", "-T", "run-as", OWNER, "/system/bin/toybox", "cat", self.private_path(path))
        require(len(result.stdout) <= limit, "private-record-grew-beyond-limit")
        return result.stdout

    def digest(self, path):
        info = self.stat(path)
        require(info["type"] == "regular file", "digest-source-not-regular-file")
        result = self.call("shell", "-T", "run-as", OWNER, "/system/bin/toybox", "sha256sum",
                           self.private_path(path), timeout=60)
        value = result.stdout.split(maxsplit=1)[0].decode("ascii")
        require(SHA256.fullmatch(value) is not None, "invalid-device-file-digest")
        return value

    def current(self):
        raw = self.read("files/runtime/current", 128)
        name = raw.decode("ascii")
        require(GENERATION.fullmatch(name) is not None, "invalid-active-generation")
        return raw, name

    def pids(self):
        result = self.call("shell", "pidof", OWNER, check=False)
        require(result.returncode == 0 or (result.returncode == 1 and not result.stdout and not result.stderr),
                "owner-pid-query-failed-not-proof-of-exit")
        values = result.stdout.decode("ascii").split()
        require(all(value.isdigit() and int(value) > 0 for value in values)
                and (bool(values) or result.returncode == 1), "invalid-owner-pid-result")
        return [int(value) for value in values]

    def verify_installed(self, package, expected_hash):
        result = self.call("shell", "pm", "path", package)
        paths = [line[8:] for line in result.stdout.decode("ascii").splitlines() if line.startswith("package:")]
        require(len(paths) == 1, "installed-apk-not-single-base")
        path = paths[0]
        require(re.fullmatch(r"/data/app/[A-Za-z0-9_~+./=-]+/base\.apk", path) is not None
                and ".." not in path.split("/"), "unsafe-installed-apk-path")
        result = self.call("shell", "/system/bin/toybox", "sha256sum", path, timeout=60)
        actual = result.stdout.split(maxsplit=1)[0].decode("ascii")
        require(actual == expected_hash, "installed-apk-does-not-match-supplied-artifact")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="An already running emulator-* serial; phones are rejected")
    parser.add_argument("--avd-name", default=OWNED_AVDS[0], choices=OWNED_AVDS,
                        help="Exact explicitly owned AVD name; no other AVD may be targeted")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apk", type=Path, required=True, help="Completed new app APK, with a different runtimeVersion")
    parser.add_argument("--tests-apk", type=Path, required=True, help="Matching completed device-test APK")
    parser.add_argument("--output", type=Path, required=True, help="New or empty evidence directory")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("only emulator-* serials are allowed; phones are never targeted")
    if args.output.exists() and (not args.output.is_dir() or any(args.output.iterdir())):
        parser.error("--output must be a new or empty directory; existing evidence is not overwritten")
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"schema": 1, "startedAt": utc_now(), "serial": args.serial, "expectedAvd": args.avd_name,
              "status": "not-passed", "scope": "actual am force-stop during pre-commit extraction; no callback fault injection",
              "appDataCleared": False, "credentialsReadByCoordinator": False,
              "credentialsPassedExternally": False, "modelRequestsSent": False}
    device = Device(args.adb, args.serial)
    stage = "local-artifact-preflight"
    ime_before = None
    ime_changed = False
    try:
        new = apk_manifest(args.apk)
        require(args.tests_apk.is_file() and args.tests_apk.resolve() != args.apk.resolve(), "matching-test-apk-not-available")
        report["newArtifact"] = dict(new, apkSha256=file_sha256(args.apk), testsApkSha256=file_sha256(args.tests_apk))
        stage = "owned-emulator-preflight"
        require(device.call("get-state").stdout.strip() == b"device", "emulator-not-online")
        name = device.call("emu", "avd", "name").stdout.decode("utf-8").splitlines()[0].strip()
        require(name == args.avd_name, "connected-avd-name-does-not-match-owned-allowlist")
        page_size = device.call("shell", "getconf", "PAGE_SIZE").stdout.decode("ascii").strip()
        if name == OWNED_AVDS[0]:
            require(page_size == "16384", "16k-avd-is-not-running-with-16384-pages")
        report["environment"] = {"avd": name, "pageSize": page_size,
                                 "api": device.call("shell", "getprop", "ro.build.version.sdk").stdout.decode("ascii").strip(),
                                 "abi": device.call("shell", "getprop", "ro.product.cpu.abi").stdout.decode("ascii").strip()}
        stage = "baseline-records"
        for path in ("files", "files/runtime", "files/home", "files/home/workspace"):
            require(device.stat(path)["type"] == "directory", "baseline-directory-is-not-a-real-directory")
        old_raw, old = device.current()
        old_version = GENERATION.fullmatch(old).group(1)
        require(new["runtimeVersion"] != old_version, "new-apk-runtime-version-has-not-changed")
        require(device.stat(STAGING, missing_ok=True) is None, "preexisting-staging-would-obscure-the-observation")
        old_ready_path = "files/runtime/" + old + "/.ready"
        old_daemon_path = "files/runtime/" + old + "/usr/local/bin/atomcode-daemon"
        old_ready = device.read(old_ready_path, 64)
        require(SHA256.fullmatch(old_ready.decode("ascii")) is not None, "old-ready-marker-invalid")
        old_daemon_hash = device.digest(old_daemon_path)
        report["baseline"] = {"current": old, "runtimeVersion": old_version,
                              "readySha256": old_ready.decode("ascii"), "daemonSha256": old_daemon_hash,
                              "rootfsPayloadHashChanged": old_ready.decode("ascii") != new["rootfsSha256"]}
        stage = "seed-unique-canary"
        directory = "files/home/workspace/upgrade-interruption-" + uuid.uuid4().hex
        canary_path = directory + "/keep.bin"
        device.call("shell", "-T", "run-as", OWNER, "/system/bin/toybox", "mkdir", directory)
        canary = ("Upgrade interruption test canary, not credentials: " + uuid.uuid4().hex + "\n").encode("ascii")
        write = device.call("shell", "-T", "run-as", OWNER, "/system/bin/toybox", "dd", "of=" + canary_path,
                            data=canary, check=False)
        report["canaryWrite"] = {"transport": "shell-v2-no-pty", "remoteExit": write.returncode,
                                 "stdoutBytes": len(write.stdout), "stderrBytes": len(write.stderr)}
        require(write.returncode == 0, "canary-write-command-failed")
        canary_info = device.stat(canary_path)
        require(canary_info["type"] == "regular file" and canary_info["bytes"] == len(canary),
                "canary-write-size-not-verified")
        canary_hash = hashlib.sha256(canary).hexdigest()
        require(device.digest(canary_path) == canary_hash, "canary-write-not-verified")
        report["canary"] = {"relativePath": canary_path, "bytes": len(canary), "sha256": canary_hash,
                            "retainedForInspection": True}
        stage = "baseline-quiescence-before-apk-install"
        # Avoid Android restoring a previously foreground Activity as soon as
        # its APK is replaced, before the external extraction observer starts.
        # This is preparation, not the later, evidenced upgrade interruption.
        device.call("shell", "am", "force-stop", OWNER)
        deadline = time.monotonic() + 15
        while device.pids() and time.monotonic() < deadline:
            time.sleep(0.1)
        require(not device.pids(), "baseline-owner-not-stopped-before-apk-install")
        report["preparation"] = {"ownerStoppedBeforeInPlaceInstall": True,
                                 "separateFromRecordedUpgradeInterruption": True}
        stage = "in-place-install"
        device.call("install", "-r", str(args.apk.resolve()), timeout=180)
        device.call("install", "-r", "-t", str(args.tests_apk.resolve()), timeout=180)
        device.verify_installed(OWNER, report["newArtifact"]["apkSha256"])
        device.verify_installed(TEST_PACKAGE, report["newArtifact"]["testsApkSha256"])
        registered = device.call("shell", "pm", "list", "instrumentation", OWNER).stdout.decode("ascii")
        require(RUNNER in registered and "target=" + OWNER in registered, "expected-owner-instrumentation-not-registered")
        require(device.current()[0] == old_raw and device.digest(canary_path) == canary_hash,
                "in-place-apk-install-changed-runtime-pointer-or-canary")
        stage = "observe-precommit-extraction"
        device.call("shell", "am", "start", "-W", "-n", OWNER + "/.MainActivity", timeout=45)
        observed = None
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            current_raw, current_name = device.current()
            if current_raw != old_raw:
                report["observedCurrent"] = current_name
                raise NotProven("upgrade-committed-before-force-stop-window")
            witness = device.stat(EXTRACTION_WITNESS, missing_ok=True)
            ready = device.stat(STAGING + "/.ready", missing_ok=True)
            if witness is not None and witness["type"] == "regular file" and witness["bytes"] > 0 and ready is None:
                pids = device.pids()
                if pids:
                    observed = {"observedAt": utc_now(), "witness": EXTRACTION_WITNESS,
                                "witnessBytes": witness["bytes"], "stagingReadyAbsent": True,
                                "currentStillBaseline": True, "ownerPids": pids}
                    break
            time.sleep(0.1)
        if observed is None:
            raise NotProven("precommit-extraction-window-not-observed-owner-may-still-run", may_still_run=True)
        report["killWindow"] = observed
        stage = "actual-force-stop"
        device.call("shell", "am", "force-stop", OWNER)
        report["forceStopIssuedAt"] = utc_now()
        deadline = time.monotonic() + 15
        while device.pids() and time.monotonic() < deadline:
            time.sleep(0.1)
        require(not device.pids(), "owner-process-still-present-after-force-stop")
        after_raw, after_name = device.current()
        if after_raw != old_raw:
            report["observedCurrent"] = after_name
            raise NotProven("force-stop-landed-after-pointer-commit-not-a-precommit-pass")
        partial = device.stat(EXTRACTION_WITNESS, missing_ok=True)
        require(device.stat(STAGING)["type"] == "directory" and partial is not None
                and partial["type"] == "regular file" and partial["bytes"] > 0
                and device.stat(STAGING + "/.ready", missing_ok=True) is None,
                "partial-uncommitted-staging-not-proved-after-kill")
        require(device.read(old_ready_path, 64) == old_ready and device.digest(old_daemon_path) == old_daemon_hash,
                "force-stop-did-not-preserve-old-ready-or-daemon")
        require(device.digest(canary_path) == canary_hash, "force-stop-did-not-preserve-home-canary")
        report["afterKill"] = {"ownerPidAbsent": True, "oldPointerBytesUnchanged": True,
                               "oldReadyUnchanged": True, "oldDaemonUnchanged": True, "homeCanaryUnchanged": True,
                               "partialStagingPresent": True, "stagingReadyAbsent": True}
        stage = "automatic-recovery"
        device.call("shell", "am", "start", "-W", "-n", OWNER + "/.MainActivity", timeout=45)
        deadline = time.monotonic() + 300
        recovered = None
        while time.monotonic() < deadline:
            current_raw, current_name = device.current()
            if GENERATION.fullmatch(current_name).group(1) == new["runtimeVersion"]:
                require(current_raw != old_raw, "new-generation-pointer-did-not-change")
                require(device.read("files/runtime/" + current_name + "/.ready", 64).decode("ascii") == new["rootfsSha256"],
                        "recovered-generation-ready-does-not-match-new-apk")
                recovered = current_name
                break
            time.sleep(0.2)
        if recovered is None:
            raise NotProven("automatic-recovery-not-yet-committed-owner-may-still-run", may_still_run=True)
        report["recoveryCommittedAt"] = utc_now()
        stage = "cold-start-default-smoke"
        # Normal suite preparation is separate from the recorded upgrade kill.
        ime_before = device.call("shell", "settings", "get", "secure", "show_ime_with_hard_keyboard").stdout.decode("ascii").strip()
        require(ime_before in ("0", "1", "null"), "unexpected-ime-preference")
        ime_changed = True
        device.call("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
        device.call("shell", "am", "force-stop", OWNER)
        smoke = device.call("shell", "am", "instrument", "-w", RUNNER, check=False, timeout=300)
        output = smoke.stdout + smoke.stderr
        (args.output / "smoke.txt").write_bytes(output)
        text = output.decode("utf-8", errors="replace")
        require(smoke.returncode == 0 and len(re.findall(r"^PASS:", text, re.M)) == 12
                and "FAIL:" not in text and "Process crashed" not in text, "default-twelve-smoke-not-passed")
        stage = "post-recovery-invariants"
        final_raw, final_name = device.current()
        require(final_name == recovered and GENERATION.fullmatch(final_name).group(1) == new["runtimeVersion"],
                "smoke-did-not-retain-recovered-generation")
        require(device.read("files/runtime/" + recovered + "/.ready", 64).decode("ascii") == new["rootfsSha256"]
                and device.digest("files/runtime/" + recovered + "/usr/local/bin/atomcode-daemon") == new["daemonSha256"],
                "final-runtime-does-not-match-new-apk")
        require(device.digest(canary_path) == canary_hash and device.read(old_ready_path, 64) == old_ready
                and device.digest(old_daemon_path) == old_daemon_hash, "old-generation-or-canary-not-retained-after-recovery")
        require(device.stat(STAGING, missing_ok=True) is None, "partial-staging-remains-after-successful-recovery")
        retention = device.read("files/runtime/retention", 256).decode("ascii")
        require(retention == recovered + "\n" + old + "\n", "retention-record-does-not-bind-new-and-old-generations")
        report["recovery"] = {"newCurrent": final_name, "newRuntimeVersion": new["runtimeVersion"],
                              "newReadyAndDaemonMatchApk": True, "oldGenerationAndCanaryPreserved": True,
                              "partialStagingRemoved": True, "retentionRecordMatches": True, "defaultSmokePassed": 12,
                              "gcSlotPresent": device.stat("files/runtime/.gc-staging", missing_ok=True) is not None}
        report["status"] = "passed"
        report["classification"] = "precommit-force-stop-preserved-old-state-and-recovered-new-runtime"
    except NotProven as failure:
        report["classification"] = failure.classification
        report["ownerMayStillBeWorking"] = failure.may_still_run
    except subprocess.TimeoutExpired:
        report["classification"] = "device-observation-timed-out-owner-state-not-proven"
        report["ownerMayStillBeWorking"] = True
    except Exception as failure:
        report["classification"] = "check-not-proven"
        report["exceptionClass"] = type(failure).__name__
    finally:
        report["lastStage"] = stage
        if ime_changed:
            try:
                if ime_before == "null":
                    device.call("shell", "settings", "delete", "secure", "show_ime_with_hard_keyboard")
                else:
                    device.call("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", ime_before)
                restored = device.call("shell", "settings", "get", "secure", "show_ime_with_hard_keyboard").stdout.decode("ascii").strip()
                report["imePreferenceRestored"] = restored == ime_before
                if not report["imePreferenceRestored"]:
                    report["status"] = "not-passed"
                    report["cleanupFailure"] = "ime-setting-restore-not-proven"
            except Exception:
                report["status"] = "not-passed"
                report["cleanupFailure"] = "ime-setting-restore-failed"
        report["finishedAt"] = utc_now()
        (args.output / "upgrade-interruption.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Upgrade interruption: " + report["status"] + "; " + report.get("classification", "unknown"))
    if report["status"] != "passed":
        print("No automatic retry was attempted; inspect the same owner process/state before rerunning.")
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
