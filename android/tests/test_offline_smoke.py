"""Offline-only coordinator guard/parser/cleanup regressions; never invokes ADB."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("offline_smoke_under_test", Path(__file__).with_name("run_offline_smoke.py"))
offline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(offline)


def connected_dump(net=100):
    return ("Active default network: %d\nmDefaultNetwork=%d\nCurrent Networks:\n"
            "  NetworkAgentInfo{network{%d} ni{MOBILE[LTE] CONNECTED extra: synthetic} "
            "nc{[ Transports: CELLULAR Capabilities: INTERNET]}}\n") % (net, net, net)


def disconnected_dump():
    return "Active default network: none\nmDefaultNetwork=null\nCurrent Networks:\n"


class OfflineSmokeTests(unittest.TestCase):
    def arguments(self, output, serial="emulator-5562"):
        return ["--serial", serial, "--apk", "/not-an-apk", "--tests-apk", "/not-a-test-apk", "--output", str(output)]

    def test_phone_rejected_before_any_adb_or_output_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "new"
            with patch.object(offline.subprocess, "run", side_effect=AssertionError("ADB invoked")), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    offline.main(self.arguments(output, "0123456789ABCDEF"))
            self.assertEqual(failure.exception.code, 2)
            self.assertFalse(output.exists())

    def test_existing_output_rejected_before_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(offline.subprocess, "run", side_effect=AssertionError("ADB invoked")), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    offline.main(self.arguments(Path(directory)))
            self.assertEqual(failure.exception.code, 2)

    def test_live_cellular_default_is_not_offline(self):
        summary = offline.connectivity_summary(connected_dump())
        self.assertEqual(offline.default_transports(summary), ["CELLULAR"])
        self.assertFalse(offline.is_offline({"airplane": "1", "airplaneApi": True, "wifiApi": False, "connectivity": summary}))

    def test_offline_ignores_timestamped_history_but_not_live_agents(self):
        text = disconnected_dump() + "2026-01-01 " + connected_dump().splitlines()[-1] + "\n"
        summary = offline.connectivity_summary(text)
        self.assertTrue(offline.is_offline({"airplane": "1", "airplaneApi": True, "wifiApi": False, "connectivity": summary}))
        text += connected_dump().splitlines()[-1] + "\n"
        self.assertFalse(offline.is_offline({"airplane": "1", "airplaneApi": True, "wifiApi": False,
                                           "connectivity": offline.connectivity_summary(text)}))

    def test_unknown_or_disagreeing_default_format_fails_closed(self):
        for text in ("", "Active default network: none\n", "Active default network: 100\nmDefaultNetwork=null\n",
                     "Active default network: none\nmDefaultNetwork=null\n NetworkAgentInfo{unknown}\n"):
            with self.subTest(text=text), self.assertRaises(offline.NotProven):
                offline.connectivity_summary(text)

    def test_deadline_prevents_starting_another_adb_command(self):
        device = offline.Device("/never-invoked", "emulator-5562", time.monotonic() - 1)
        with patch.object(offline.subprocess, "run", side_effect=AssertionError("ADB invoked")):
            with self.assertRaises(offline.NotProven):
                device.call("get-state")

    def test_empty_successful_pidof_is_not_proof_of_owner_exit(self):
        device = offline.Device("/never-invoked", "emulator-5562", time.monotonic() + 5)
        empty = subprocess.CompletedProcess([], 0, b"", b"")
        with patch.object(device, "call", return_value=empty):
            with self.assertRaises(offline.NotProven):
                device.stop_owner()

    def test_unknown_test_apk_markers_rejected_without_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            app, tests = Path(directory) / "app.apk", Path(directory) / "tests.apk"
            with zipfile.ZipFile(app, "w") as archive:
                archive.writestr("assets/runtime/manifest.json", json.dumps({"schema": 1, "abi": "arm64-v8a", "runtimeVersion": "atomcode-0123456789abcdef"}))
            with zipfile.ZipFile(tests, "w") as archive:
                archive.writestr("classes.dex", b"Lio/github/yanhuicsdn/atomcode/LocalRuntimeSmoke;")
            with patch.object(offline.subprocess, "run", side_effect=AssertionError("ADB invoked")):
                with self.assertRaises(offline.NotProven) as failure:
                    offline.local_artifacts(app, tests)
            self.assertEqual(str(failure.exception), "unknown-test-apk-default-twelve-suite-not-recognized")

    def simulated_run(self, fail_while_instrumenting):
        actions = []
        processes = []
        baseline = {"wifi": "1", "mobileData": "1", "airplane": "0", "ime": "1", "wifiApi": True,
                    "airplaneApi": False, "connectivity": offline.connectivity_summary(connected_dump())}
        disconnected = dict(baseline, wifi="0", airplane="1", wifiApi=False, airplaneApi=True,
                            connectivity=offline.connectivity_summary(disconnected_dump()))

        class Process:
            def __init__(self, command, stdout, **_kwargs):
                actions.append(("instrument", tuple(command)))
                processes.append(self)
                self.returncode = None if fail_while_instrumenting else 0
                stdout.write(("\n".join("PASS: " + label for label in offline.DEFAULT_LABELS) + "\n").encode())
                stdout.flush()

            def poll(self):
                return self.returncode

            def wait(self, timeout):
                actions.append(("wait-same-handle", id(self)))
                if self.returncode is None:
                    raise subprocess.TimeoutExpired("synthetic", timeout)
                return self.returncode

        class Device:
            def __init__(self, _adb, _serial, deadline):
                self.prefix = ["/never-invoked", "-s", "emulator-5562"]
                self.deadline = deadline
                self.airplane = False

            def verify_owned(self):
                actions.append(("verify-owned",))

            def verify_package(self, *_args):
                pass

            def call(self, *args, **_kwargs):
                actions.append(args)
                if args == ("shell", "pm", "list", "instrumentation", offline.OWNER):
                    return subprocess.CompletedProcess([], 0, (offline.RUNNER + " target=" + offline.OWNER).encode(), b"")
                if args[-1:] == ("help",):
                    return subprocess.CompletedProcess([], 255, b"airplane-mode [enable|disable]\nset-wifi-enabled enabled|disabled\n", b"")
                if args[-2:] == ("airplane-mode", "enable"):
                    self.airplane = True
                if args[-2:] == ("airplane-mode", "disable"):
                    self.airplane = False
                return subprocess.CompletedProcess([], 0, b"", b"")

            def snapshot(self):
                if fail_while_instrumenting and processes and processes[0].poll() is None:
                    raise offline.NotProven("synthetic-observation-failure")
                return disconnected.copy() if self.airplane else baseline.copy()

            def stop_owner(self):
                actions.append(("stop-owner",))
                for process in processes:
                    if process.returncode is None:
                        process.returncode = 1

            def remaining(self, cap):
                return cap

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "new"
            with patch.object(offline, "Device", Device), patch.object(offline, "local_artifacts", return_value={"appSha256": "a" * 64, "testsSha256": "b" * 64}), patch.object(offline.subprocess, "Popen", Process), contextlib.redirect_stdout(io.StringIO()):
                code = offline.main(self.arguments(output))
            report = json.loads((output / "offline-smoke-summary.json").read_text())
        self.assertEqual(code, 1 if fail_while_instrumenting else 0)
        self.assertTrue(report["networkPreferencesRestored"] and report["originalDefaultTransportReconnected"] and report["imeUnchanged"])
        self.assertTrue(report["ownerStoppedAtCleanup"] and report["instrumentHandleTerminal"])
        plane_restore = actions.index(("shell", "cmd", "connectivity", "airplane-mode", "disable"))
        self.assertEqual(actions[plane_restore + 1], ("shell", "cmd", "wifi", "set-wifi-enabled", "enabled"))
        self.assertTrue(any(action == ("stop-owner",) for action in actions[:plane_restore]))
        self.assertTrue(all("settings" not in action and "data" not in action for action in actions))
        command = next(action[1] for action in actions if action[0] == "instrument")
        self.assertEqual(command[-5:], ("shell", "am", "instrument", "-w", offline.RUNNER))
        self.assertNotIn("-e", command)
        return actions, processes

    def test_normal_pass_restores_actual_connection_and_preserves_preferences(self):
        self.simulated_run(False)

    def test_failure_stops_owner_observes_same_handle_and_still_restores_network(self):
        actions, processes = self.simulated_run(True)
        self.assertIn(("wait-same-handle", id(processes[0])), actions)


if __name__ == "__main__":
    unittest.main()
