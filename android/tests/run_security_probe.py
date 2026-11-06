#!/usr/bin/env python3
"""Run installed owner/probe test APKs locally; no credentials leave the owner UID.

This stops/restarts the owner's local runtime. Install both test APKs first, and
do not run it while a user/model operation is active. It never clears app data.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import uuid

OWNER = "io.github.yanhuicsdn.atomcode"
PROBE = OWNER + ".probe"
OWNER_RUNNER = OWNER + ".test/" + OWNER + ".LocalRuntimeSmoke"
PROBE_RUNNER = PROBE + "/" + PROBE + ".ForeignUidProbe"
ENDPOINT = "cache/security-acceptance-endpoint.json"
STOP = "cache/security-acceptance-stop"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb, "-s", args.serial]

    def adb(*parts, check=True, timeout=30):
        return subprocess.run(prefix + list(parts), capture_output=True, text=True,
                              timeout=timeout, check=check)

    adb("shell", "am", "force-stop", OWNER)
    lease_id = uuid.uuid4().hex
    process = None
    with tempfile.TemporaryFile(mode="w+") as output:
        try:
            process = subprocess.Popen(prefix + ["shell", "am", "instrument", "-w",
                "-e", "security_lease", "true", "-e", "lease_id", lease_id, OWNER_RUNNER],
                stdout=output, stderr=subprocess.STDOUT, text=True)
            deadline = time.monotonic() + 180
            endpoint = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("owner lease exited before publishing a live endpoint")
                read = adb("exec-out", "run-as", OWNER, "cat", ENDPOINT, check=False)
                if read.returncode == 0:
                    try:
                        candidate = json.loads(read.stdout)
                        if candidate.get("lease_id") == lease_id:
                            endpoint = candidate
                            break
                    except json.JSONDecodeError:
                        pass  # descriptor write may still be in progress
                time.sleep(0.2)
            if endpoint is None:
                raise RuntimeError("live owner endpoint deadline exceeded")
            command = ["shell", "am", "instrument", "-w"]
            for field in ["port", "uds_path", "owner_uid", "expected_certificate_sha256"]:
                command += ["-e", field, str(endpoint[field])]
            # Endpoint metadata is public; deliberately never read entryUrl or token.
            probe = adb(*command, PROBE_RUNNER, timeout=120)
            (args.output / "foreign-uid-probe.txt").write_text(probe.stdout)
            if len(re.findall(r"^PASS:", probe.stdout, re.M)) != 7 or "FAIL:" in probe.stdout:
                raise RuntimeError("foreign UID boundary probe did not pass all seven checks")
        finally:
            if process is not None:
                adb("shell", "run-as", OWNER, "touch", STOP, check=False)
                try:
                    process.wait(timeout=210)
                except subprocess.TimeoutExpired:
                    # The host ADB command is still live. Terminate this test's
                    # owner package explicitly, then observe the same handle.
                    adb("shell", "am", "force-stop", OWNER, check=False)
                    process.wait(timeout=30)
                output.seek(0)
                owner_output = output.read()
                (args.output / "security-owner.txt").write_text(owner_output)
            else:
                owner_output = ""
    if len(re.findall(r"^PASS:", owner_output, re.M)) != 2 or "FAIL:" in owner_output:
        raise RuntimeError("owner health/cleanup checks did not pass")
    report = {"serial": args.serial, "foreignUidChecks": 7, "ownerHealthChecks": 2,
              "credentialsProvidedToProbe": False,
              "scope": "distinct UID, active socket isolation, unauthenticated TLS requests, origin/host filtering and occupied-port exclusivity; not every takeover race or upload path"}
    (args.output / "security-probe-summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Distinct-UID probe: 7/7; owner health: 2/2; no credentials disclosed")


if __name__ == "__main__":
    main()
