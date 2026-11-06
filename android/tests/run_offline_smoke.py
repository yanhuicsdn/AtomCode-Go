#!/usr/bin/env python3
"""Run default 12-check smoke offline on one explicitly owned, already running AVD.

Never starts an AVD, installs APKs, clears data, reads credentials, or calls a
model. Initial airplane mode must be off with a working default connection.
Disable WiFi before enabling airplane mode; leave the mobile_data user preference
unchanged. Restore airplane/WiFi through platform commands and require the
original default transport to reconnect, not merely matching settings values.
The default AVD requires 16384-byte pages. The separately named AOSP backup AVD
requires 4096-byte pages and produces 4KB evidence, never 16KB evidence.
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
import zipfile


OWNER = "io.github.yanhuicsdn.atomcode"
RUNNER = OWNER + ".test/" + OWNER + ".LocalRuntimeSmoke"
OWNED_AVD = "AtomCode_API36_16K_Arm64"
OWNED_AVDS = {OWNED_AVD: 16384, "AtomCode_Backup_AOSP36_Arm64": 4096}
TOTAL_SECONDS = 360
INSTRUMENT_SECONDS = 300
CLEANUP_RESERVE = 60
DEFAULT_LABELS = (
    "Activity launch automatically installs/starts bundled Linux/PRoot/daemon",
    "Pinned TLS and daemon token-to-HttpOnly-cookie exchange",
    "Incorrect local service certificate rejected",
    "Unauthenticated API rejection and authenticated API access",
    "Real Linux PTY command execution, Git and exit status",
    "Workspace file read and revision-checked edit",
    "Foreign-origin request rejection",
    "Actual WebView loading and credential isolation",
    "Fullscreen workspace after automatic startup",
    "Fullscreen workspace resizes for Android keyboard",
    "Explicit process stop",
    "Restart, token rotation and workspace persistence",
)


class NotProven(Exception):
    pass


def require(condition, classification):
    if not condition:
        raise NotProven(classification)


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def local_artifacts(app, tests):
    require(app.is_file() and tests.is_file() and app.resolve() != tests.resolve(), "app-and-test-apks-required")
    with zipfile.ZipFile(app) as apk:
        entries = [entry for entry in apk.infolist() if entry.filename == "assets/runtime/manifest.json"]
        require(len(entries) == 1 and 0 < entries[0].file_size <= 1024 * 1024, "runtime-manifest-not-bounded")
        manifest = json.loads(apk.read(entries[0]))
        require(manifest.get("schema") == 1 and manifest.get("abi") == "arm64-v8a"
                and re.fullmatch(r"atomcode-[a-f0-9]{16}", manifest.get("runtimeVersion", "")) is not None,
                "unsupported-runtime-manifest")
    with zipfile.ZipFile(tests) as apk:
        entries = [entry for entry in apk.infolist() if re.fullmatch(r"classes(?:[0-9]+)?\.dex", entry.filename)]
        require(0 < len(entries) <= 4 and sum(entry.file_size for entry in entries) <= 16 * 1024 * 1024,
                "test-dex-not-bounded")
        dex = b"\n".join(apk.read(entry) for entry in entries)
        require(b"Lio/github/yanhuicsdn/atomcode/LocalRuntimeSmoke;" in dex
                and all(label.encode("ascii") in dex for label in DEFAULT_LABELS),
                "unknown-test-apk-default-twelve-suite-not-recognized")
    return {"appSha256": sha256(app), "testsSha256": sha256(tests), "runtimeVersion": manifest["runtimeVersion"],
            "defaultTwelveTestMarkersPresent": True}


def connectivity_summary(raw):
    """Keep only current default/agent metadata; never persist addresses/SSID/APN."""
    text = raw.decode("utf-8", errors="strict") if isinstance(raw, bytes) else raw
    require(len(re.findall(r"^Current Networks:\s*$", text, re.M)) == 1,
            "connectivity-live-agent-section-not-recognized")
    active = re.findall(r"^Active default network:\s*(none|[0-9]+)\s*$", text, re.M)
    tracker = re.findall(r"^\s*mDefaultNetwork=(null|[0-9]+)\s*$", text, re.M)
    require(len(active) == len(tracker) == 1, "connectivity-default-format-not-recognized")
    active_id = None if active[0] == "none" else int(active[0])
    tracker_id = None if tracker[0] == "null" else int(tracker[0])
    require(active_id == tracker_id, "connectivity-default-observations-disagree")
    connected = []
    for line in text.splitlines():
        if not re.match(r"^\s*NetworkAgentInfo\{", line):
            continue  # Timestamped historical events and NetworkRequests are not live agents.
        identity = re.search(r"NetworkAgentInfo\{network\{([0-9]+)\}", line)
        state = re.search(r"\bni\{[^}]*?\b(CONNECTED|CONNECTING|DISCONNECTED|DISCONNECTING|SUSPENDED)\b", line)
        transports = re.search(r"\bTransports:\s*([A-Z_]+(?:\|[A-Z_]+)*)\b", line)
        require(identity is not None and state is not None and transports is not None,
                "connectivity-agent-format-not-recognized")
        if state.group(1) in ("CONNECTED", "CONNECTING", "SUSPENDED"):
            connected.append({"id": int(identity.group(1)), "state": state.group(1),
                              "transports": sorted(transports.group(1).split("|"))})
    return {"activeDefault": active_id, "trackerDefault": tracker_id, "connectedNetworks": connected}


def default_transports(connectivity):
    matches = [network["transports"] for network in connectivity["connectedNetworks"]
               if network["id"] == connectivity["activeDefault"] and network["state"] == "CONNECTED"]
    require(len(matches) == 1, "initial-or-restored-default-not-connected")
    return matches[0]


def is_offline(snapshot):
    network = snapshot["connectivity"]
    return (snapshot["airplane"] == "1" and snapshot["airplaneApi"] is True
            and snapshot["wifiApi"] is False and network["activeDefault"] is None
            and network["trackerDefault"] is None and not network["connectedNetworks"])


class Device:
    def __init__(self, adb, serial, deadline, avd=OWNED_AVD):
        require(avd in OWNED_AVDS, "avd-not-in-exact-owned-allowlist")
        self.prefix = [adb, "-s", serial]
        self.deadline = deadline
        self.avd = avd

    def remaining(self, cap=10):
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, "coordinator-phase-deadline-exceeded")
        return min(cap, remaining)

    def call(self, *arguments, check=True, timeout=10):
        result = subprocess.run(self.prefix + list(arguments), capture_output=True, timeout=self.remaining(timeout))
        require(not check or result.returncode == 0, "adb-operation-failed")
        return result

    def verify_owned(self):
        require(self.call("get-state").stdout.strip() == b"device", "owned-emulator-not-online")
        actual_avd = self.call("emu", "avd", "name").stdout.decode("ascii").splitlines()[0].strip()
        require(actual_avd == self.avd, "avd-not-in-exact-owned-allowlist")
        require(self.call("shell", "getprop", "ro.hardware").stdout.strip() == b"ranchu", "ranchu-emulator-required")
        require(self.call("shell", "getprop", "ro.kernel.qemu").stdout.strip() == b"1", "qemu-emulator-required")
        page = self.call("shell", "getconf", "PAGE_SIZE").stdout.decode("ascii").strip()
        require(page.isdigit() and int(page) == OWNED_AVDS[self.avd], "page-size-does-not-match-owned-avd")
        return {"avd": actual_avd, "hardware": "ranchu", "pageSize": int(page), "pageSizeSource": "getconf PAGE_SIZE",
                "is16kEnvironment": int(page) == 16384,
                "pageEvidenceScope": "16KB owned emulator" if int(page) == 16384 else "4KB AOSP backup emulator; not 16KB evidence"}

    def verify_package(self, package, expected):
        lines = self.call("shell", "pm", "path", package).stdout.decode("ascii").splitlines()
        require(len(lines) == 1 and lines[0].startswith("package:"), "installed-package-not-single-base")
        path = lines[0][8:]
        require(re.fullmatch(r"/data/app/[A-Za-z0-9_~+./=-]+/base\.apk", path) is not None
                and ".." not in path.split("/"), "unsafe-installed-apk-path")
        result = self.call("shell", "/system/bin/toybox", "sha256sum", path)
        require(result.stdout.split(maxsplit=1)[0].decode("ascii") == expected, "installed-apk-digest-mismatch")

    def setting(self, namespace, key):
        return self.call("shell", "settings", "get", namespace, key).stdout.decode("ascii").strip()

    def snapshot(self):
        values = {"wifi": self.setting("global", "wifi_on"),
                  "mobileData": self.setting("global", "mobile_data"),
                  "airplane": self.setting("global", "airplane_mode_on"),
                  "ime": self.setting("secure", "show_ime_with_hard_keyboard")}
        require(values["wifi"] in ("0", "1", "2", "3") and values["mobileData"] in ("0", "1")
                and values["airplane"] in ("0", "1") and values["ime"] in ("0", "1", "null"),
                "network-or-ime-value-not-recognized")
        wifi = self.call("shell", "cmd", "wifi", "status").stdout.decode("utf-8")
        wifi_state = re.findall(r"^Wifi is (enabled|disabled)\s*$", wifi, re.M)
        airplane = self.call("shell", "cmd", "connectivity", "airplane-mode").stdout.decode("ascii").strip()
        require(len(wifi_state) == 1 and airplane in ("enabled", "disabled"), "platform-radio-state-not-recognized")
        values.update(wifiApi=wifi_state[0] == "enabled", airplaneApi=airplane == "enabled")
        require(values["airplaneApi"] == (values["airplane"] == "1"), "airplane-api-and-setting-disagree")
        values["connectivity"] = connectivity_summary(self.call("shell", "dumpsys", "connectivity").stdout)
        values["observedAt"] = utc_now()
        return values

    def stop_owner(self):
        self.call("shell", "am", "force-stop", OWNER)
        deadline = min(self.deadline, time.monotonic() + 12)
        while True:
            result = self.call("shell", "pidof", OWNER, check=False)
            if result.returncode == 1 and not result.stdout and not result.stderr:
                return
            require(result.returncode == 0 and bool(result.stdout.split())
                    and all(value.isdigit() for value in result.stdout.split()), "owner-exit-not-observable")
            require(time.monotonic() < deadline, "owner-did-not-exit")
            time.sleep(0.2)


def wait_snapshot(device, predicate, observations, seconds=30):
    deadline = min(device.deadline, time.monotonic() + seconds)
    while True:
        snapshot = device.snapshot()
        observations.append(snapshot)
        if predicate(snapshot):
            return snapshot
        require(time.monotonic() < deadline, "network-transition-not-observed")
        time.sleep(min(0.5, max(0, deadline - time.monotonic())))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Only emulator-*; phones are rejected before ADB")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--avd", choices=list(OWNED_AVDS), default=OWNED_AVD,
                        help="Exact owned AVD; its fixed expected page size cannot be overridden")
    parser.add_argument("--apk", type=Path, required=True, help="Already installed app APK; hash is checked locally and on device")
    parser.add_argument("--tests-apk", type=Path, required=True, help="Already installed known default-12 test APK")
    parser.add_argument("--output", type=Path, required=True, help="New evidence directory; existing directories are rejected")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("only emulator-* is allowed; phones are never targeted")
    if args.output.exists():
        parser.error("--output must not already exist; no previous evidence is overwritten")
    args.output.mkdir(parents=True)
    started = time.monotonic()
    total_deadline = started + TOTAL_SECONDS
    device = Device(args.adb, args.serial, total_deadline - CLEANUP_RESERVE, avd=args.avd)
    report = {"schema": 1, "startedAt": utc_now(), "status": "not-passed", "serial": args.serial,
              "expectedAvd": args.avd, "expectedPageSize": OWNED_AVDS[args.avd],
              "scope": "default local-runtime smoke with WiFi off and airplane-mode cellular disconnection; no offline model inference",
              "totalLimitSeconds": TOTAL_SECONDS, "instrumentLimitSeconds": INSTRUMENT_SECONDS,
              "cleanupReserveSeconds": CLEANUP_RESERVE, "credentialsRead": False, "modelRequestsSent": False,
              "appInstalledOrDataCleared": False, "mobileDataPreferenceChangedByCoordinator": False,
              "imeChangedByCoordinator": False, "automaticRetries": 0}
    observations = []
    original = None
    authorized = False
    network_may_have_changed = False
    process = None
    smoke_file = None
    stage = "local-artifact-preflight"
    try:
        report["artifacts"] = local_artifacts(args.apk, args.tests_apk)
        stage = "owned-emulator-preflight"
        report["environment"] = device.verify_owned()
        report["ownedAvd"] = report["environment"]["avd"]
        device.verify_package(OWNER, report["artifacts"]["appSha256"])
        device.verify_package(OWNER + ".test", report["artifacts"]["testsSha256"])
        registered = device.call("shell", "pm", "list", "instrumentation", OWNER).stdout.decode("ascii")
        require(RUNNER in registered and "target=" + OWNER in registered, "owner-runner-not-registered")
        authorized = True
        # Android's help command can return 255 even when its help text is valid.
        plane_help = device.call("shell", "cmd", "connectivity", "help", check=False)
        wifi_help = device.call("shell", "cmd", "wifi", "help", check=False)
        require(b"airplane-mode [enable|disable]" in plane_help.stdout
                and b"set-wifi-enabled enabled|disabled" in wifi_help.stdout, "required-platform-command-not-advertised")
        report["cliHelpExitCodes"] = {"connectivity": plane_help.returncode, "wifi": wifi_help.returncode}
        stage = "capture-original-network-and-ime"
        original = device.snapshot()
        report["original"] = original
        require(original["airplane"] == "0" and original["wifi"] in ("0", "1")
                and original["wifiApi"] == (original["wifi"] == "1"), "initial-radio-state-not-supported-safely")
        original_transports = default_transports(original["connectivity"])
        report["originalDefaultTransports"] = original_transports
        device.stop_owner()
        stage = "platform-network-disconnection"
        network_may_have_changed = True  # A failed/timeout command may already have changed state.
        # Keep WiFi toggles outside airplane mode, avoiding a change to its
        # remembered in-airplane WiFi policy. Never use settings put or svc data.
        device.call("shell", "cmd", "wifi", "set-wifi-enabled", "disabled")
        device.call("shell", "cmd", "connectivity", "airplane-mode", "enable")
        disconnected = wait_snapshot(device, is_offline, observations)
        require(disconnected["mobileData"] == original["mobileData"] and disconnected["ime"] == original["ime"],
                "unchanged-mobile-data-or-ime-not-proved")
        report["offlineBeforeSmoke"] = disconnected
        stage = "default-twelve-smoke-offline"
        smoke_file = (args.output / "smoke.txt").open("wb")
        # No extras: this is the recognized runner's default twelve checks,
        # never real_model/storage/layout/security_lease/real_space modes.
        process = subprocess.Popen(device.prefix + ["shell", "am", "instrument", "-w", RUNNER],
                                   stdout=smoke_file, stderr=subprocess.STDOUT)
        instrument_deadline = min(device.deadline, time.monotonic() + INSTRUMENT_SECONDS)
        report["instrumentEffectiveLimitSeconds"] = round(instrument_deadline - time.monotonic(), 3)
        while process.poll() is None:
            require(time.monotonic() < instrument_deadline, "instrumentation-deadline-exceeded")
            snapshot = device.snapshot()
            observations.append(snapshot)
            require(is_offline(snapshot) and snapshot["mobileData"] == original["mobileData"]
                    and snapshot["ime"] == original["ime"], "network-or-setting-changed-during-smoke")
            if process.poll() is None:
                time.sleep(min(1, max(0, instrument_deadline - time.monotonic())))
        report["instrumentHandleTerminal"] = True
        report["instrumentExit"] = process.returncode
        smoke_file.close()
        smoke_file = None
        final_offline = device.snapshot()
        observations.append(final_offline)
        require(is_offline(final_offline), "offline-state-not-retained-through-smoke")
        output = (args.output / "smoke.txt").read_text(errors="replace")
        passes = re.findall(r"^PASS: (.*)$", output, re.M)
        require(process.returncode == 0 and tuple(passes) == DEFAULT_LABELS
                and not any(marker in output for marker in ("FAIL:", "Process crashed", "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")),
                "default-twelve-smoke-not-passed")
        report["defaultSmokePassed"] = 12
        report["status"] = "passed"
        report["classification"] = "default-twelve-passed-with-no-active-default-or-connected-network"
    except subprocess.TimeoutExpired:
        report["classification"] = "adb-or-instrument-observation-timed-out"
    except NotProven as failure:
        report["classification"] = str(failure)  # Fixed local classifications only.
    except (KeyboardInterrupt, InterruptedError):
        report["classification"] = "interrupted-without-automatic-retry"
    except Exception as failure:
        report["classification"] = "check-not-proven"
        report["exceptionClass"] = type(failure).__name__
    finally:
        report["lastStage"] = stage
        device.deadline = total_deadline
        cleanup_errors = []
        if authorized:
            try:
                device.verify_owned()  # Refuse cleanup mutations if the serial was reused.
                device.stop_owner()
                report["ownerStoppedAtCleanup"] = True
            except Exception as failure:
                cleanup_errors.append("owner-stop-" + type(failure).__name__)
            if process is not None:
                try:
                    try:
                        if process.poll() is None:
                            process.wait(timeout=device.remaining(8))
                        else:
                            process.wait(timeout=0)
                    except (subprocess.TimeoutExpired, NotProven):
                        # Reap only the exact host ADB handle this coordinator owns.
                        process.terminate()
                        try:
                            process.wait(timeout=device.remaining(3))
                        except (subprocess.TimeoutExpired, NotProven):
                            process.kill()
                            process.wait(timeout=1)
                        report["hostInstrumentHandleTerminated"] = True
                except Exception as failure:
                    cleanup_errors.append("instrument-handle-" + type(failure).__name__)
                report["instrumentHandleTerminal"] = process.poll() is not None
                report["instrumentExitAfterCleanup"] = process.returncode
        if smoke_file is not None:
            smoke_file.close()
        if authorized and original is not None and network_may_have_changed:
            try:
                device.verify_owned()
                # Complete platform state restoration, never just settings put.
                device.call("shell", "cmd", "connectivity", "airplane-mode", "disable")
                device.call("shell", "cmd", "wifi", "set-wifi-enabled", "enabled" if original["wifiApi"] else "disabled")
                def restored(snapshot):
                    if not all(snapshot[key] == original[key] for key in ("wifi", "mobileData", "airplane", "ime", "wifiApi", "airplaneApi")):
                        return False
                    try:
                        return default_transports(snapshot["connectivity"]) == original_transports
                    except NotProven:
                        return False
                after = wait_snapshot(device, restored, observations, seconds=35)
                report["restored"] = after
                report["networkPreferencesRestored"] = True
                report["originalDefaultTransportReconnected"] = True
                report["imeUnchanged"] = after["ime"] == original["ime"]
            except Exception as failure:
                cleanup_errors.append("network-restore-" + type(failure).__name__)
        elif authorized and original is not None:
            try:
                report["imeUnchanged"] = device.setting("secure", "show_ime_with_hard_keyboard") == original["ime"]
                require(report["imeUnchanged"], "ime-changed-before-network-mutation")
            except Exception as failure:
                cleanup_errors.append("ime-observation-" + type(failure).__name__)
        if cleanup_errors:
            report["status"] = "not-passed"
            report["cleanupErrors"] = cleanup_errors
            report["classificationBeforeCleanup"] = report.get("classification")
            report["classification"] = "cleanup-or-network-restoration-not-proven"
        report["networkObservations"] = observations
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        report["finishedAt"] = utc_now()
        (args.output / "offline-smoke-summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Offline smoke: " + report["status"] + "; " + report.get("classification", "unknown"))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    def interrupted(_signum, _frame):
        raise InterruptedError()
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
