#!/usr/bin/env python3
"""Offline security/API-contract tests; never contact Ather or control a scooter."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parent / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


lab = load("ather_lab", "ather-lab.py")
guard = load("secret_guard", "secret-guard.py")
cutoff = load("charge_limit", "charge-limit.py")
GUARD_SCRIPT = Path(__file__).parent.resolve() / "secret-guard.py"


def fake_jwt():
    # Generated synthetic values keep credential patterns out of source fixtures.
    return ".".join(("eyJ" + "a" * 20, "b" * 20, "c" * 20))


class LabTests(unittest.TestCase):
    def test_charging_request_matches_android_contract(self):
        for action in ("start", "stop"):
            self.assertEqual(lab.payload_for(action, "test-scooter", 123), {
                "state": {"desired": {"remote_charging": {
                    "state": 1, "action": action, "error": "0", "timestamp": 123,
                }}}, "request_id": "ma_test-scooter",
            })

    def test_start_and_stop_preview_do_not_contact_api(self):
        for action in ("start", "stop"):
            with patch.object(sys, "argv", ["ather-lab.py", action]), \
                    patch.object(lab, "session", return_value={"token": "test", "uuid": "vehicle"}), \
                    patch.object(lab, "request") as request, contextlib.redirect_stdout(io.StringIO()):
                lab.main()
                request.assert_not_called()

    def test_execute_posts_once_and_keeps_token_out_of_output(self):
        for action in ("start", "stop"):
            output = io.StringIO()
            with patch.object(sys, "argv", ["ather-lab.py", action, "--execute"]), \
                    patch.object(lab, "session", return_value={"token": "private-value", "uuid": "vehicle"}), \
                    patch.object(lab, "request", return_value={}) as request, contextlib.redirect_stdout(output):
                lab.main()
                request.assert_called_once()
                self.assertEqual(request.call_args.args[2]["state"]["desired"]["remote_charging"]["action"], action)
                self.assertNotIn("private-value", output.getvalue())
                self.assertIn("execution is unconfirmed", output.getvalue())

    def test_storage_is_private_and_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(lab, "STORE", Path(directory) / "private"):
            lab.save_private("session.json", {"token": "synthetic"})
            self.assertEqual(lab.STORE.stat().st_mode & 0o777, 0o700)
            self.assertEqual((lab.STORE / "session.json").stat().st_mode & 0o777, 0o600)
            self.assertEqual(lab.read_private("session.json"), {"token": "synthetic"})
            target = lab.STORE / "session.json"
            target.unlink()
            target.symlink_to(Path(directory) / "other")
            with self.assertRaises(OSError):
                lab.read_private("session.json")

    def test_redaction_keeps_charging_evidence(self):
        token = fake_jwt()
        frame = {"telemetry.charging": {"chargingStatus": "Charging", "chargingHeartBeat": "On"},
                 "token": token, "uuid": "vehicle-test", "gps_location": {"lat": 1, "lng": 2},
                 "message": "echo " + token + " vehicle-test"}
        safe = lab.redact(frame, (token, "vehicle-test"))
        text = json.dumps(safe)
        self.assertNotIn(token, text)
        self.assertNotIn("vehicle-test", text)
        self.assertEqual(safe["gps_location"], "[redacted]")
        self.assertEqual(safe["telemetry.charging"]["chargingHeartBeat"], "On")

    def test_token_extraction_supported_envelopes(self):
        for root in ({"token": "synthetic"}, {"token": {"access_token": "synthetic"}},
                     {"data": {"token": "synthetic"}}):
            self.assertEqual(lab.extract_token(root), "synthetic")

    def test_flat_coordinates_and_saved_destinations_are_private(self):
        safe = lab.redact({"lat": 12, "lng": 79, "location.lat": 12,
                           "recents": [{"title": "Private destination", "lat": 12}],
                           "display_name": "Private name", "vin": "Private VIN", "savings": 20})
        for key in ("lat", "lng", "location.lat", "recents", "display_name", "vin"):
            self.assertEqual(safe[key], "[redacted]")
        self.assertEqual(safe["savings"], 20)

    def test_http_error_does_not_echo_response_body(self):
        from urllib.error import HTTPError
        failure = HTTPError("https://example.invalid", 400, "private-value", {}, io.BytesIO(b"private-value"))
        with patch.object(lab, "build_opener") as opener:
            opener.return_value.open.side_effect = failure
            with self.assertRaises(lab.LabError) as caught:
                lab.request("/auth/v2/verify-login-otp", payload={})
            self.assertNotIn("private-value", str(caught.exception))

    def test_http_redirects_are_not_followed(self):
        self.assertIsNone(lab.NoRedirect().redirect_request(None, None, 302, "", {}, "https://example.invalid"))

    def test_login_refuses_noninteractive_input(self):
        with patch.object(sys.stdin, "isatty", return_value=False), patch.object(lab, "request") as request:
            with self.assertRaises(lab.LabError):
                lab.request_otp()
            request.assert_not_called()


class ChargeLimitTests(unittest.TestCase):
    def setUp(self):
        self.now = 1000.0
        self.watcher = cutoff.Watcher(cutoff.Decimal("97"))
        self.data = {"token": "synthetic", "uuid": "vehicle"}
        self.saved = patch.object(cutoff.lab, "save_private").start()
        self.clock = patch.object(cutoff.time, "time", return_value=self.now).start()
        self.request = patch.object(cutoff.lab, "request", return_value={}).start()
        self.addCleanup(patch.stopall)

    def frame(self, percent=None, age=0, charging=None):
        bike = {"last_synced_time": int((self.now - age) * 1000)}
        if percent is not None:
            bike["battery_soc"] = percent
        return {"telemetry.bike": bike, "telemetry.charging": charging or {}}

    def test_stale_high_battery_cannot_trigger_stop(self):
        self.watcher.observe(self.frame("98", age=300), True, self.data)
        self.request.assert_not_called()

    def test_below_threshold_then_at_threshold_sends_exactly_once(self):
        self.watcher.observe(self.frame("96.99"), True, self.data)
        self.request.assert_not_called()
        with contextlib.redirect_stdout(io.StringIO()):
            self.watcher.observe(self.frame("97.00"), False, self.data)
            self.watcher.observe(self.frame("98.00"), False, self.data)
        self.request.assert_called_once()
        payload = self.request.call_args.args[2]
        self.assertEqual(payload["state"]["desired"]["remote_charging"]["action"], "stop")

    def test_unrelated_update_does_not_reuse_battery_to_stop(self):
        self.watcher.observe(self.frame("97", age=300), True, self.data)
        self.watcher.observe(self.frame(charging={"chargingStatus": "Charging"}), False, self.data)
        self.request.assert_not_called()

    def test_initial_snapshot_requires_source_timestamp(self):
        self.assertIsNone(cutoff.fresh_battery({"battery_soc": "97"}, True, self.now))

    def test_stop_requires_subsequent_physical_confirmation(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.watcher.observe(self.frame("97"), True, self.data)
            self.assertFalse(self.watcher.observe({"scooters.remote_charging": {"action": "stop"}}, False, self.data))
            self.assertFalse(self.watcher.observe(self.frame(age=1, charging={"chargingStatus": "Paused"}), True, self.data))
            self.assertTrue(self.watcher.observe(self.frame(age=-1, charging={"chargingStatus": "Paused"}), False, self.data))
        self.assertEqual(self.watcher.status["phase"], "stop_confirmed")

    def test_network_failure_does_not_retry_stop(self):
        self.request.side_effect = cutoff.lab.LabError("Timed out")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertTrue(self.watcher.observe(self.frame("97"), True, self.data))
        self.assertEqual(self.watcher.status["phase"], "stop_unconfirmed")
        self.request.assert_called_once()

    def test_partial_nested_and_flattened_frames_are_supported(self):
        for frame in ({"state": {"reported": {"telemetry": {"bike": {"battery_soc": "97"}}}}},
                      {"state": {"delta": {"telemetry.bike.battery_soc": "97"}}}):
            bike, _ = cutoff.fields(frame)
            self.assertEqual(bike["battery_soc"], "97")

    def test_new_snapshot_can_confirm_stop_if_source_is_after_command(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.watcher.observe(self.frame("97"), True, self.data)
            self.assertTrue(self.watcher.observe(self.frame(age=-1, charging={"chargingStatus": "Stopped"}), True, self.data))
        self.assertEqual(self.watcher.status["phase"], "stop_confirmed")

    def test_quiet_socket_refreshes_snapshot_and_threshold_still_stops(self):
        import websocket
        clock = [1000.0]
        connections = []
        def connect(*args, **kwargs):
            index = len(connections)
            snapshot = {"telemetry.bike": {"battery_soc": "96.90" if index == 0 else "97.10",
                                           "last_synced_time": int(clock[0] * 1000)},
                        "telemetry.charging": {"chargingStatus": "Stopped" if index >= 2 else "Charging"}}
            class QuietSocket:
                def __init__(self):
                    self.initial = True
                def getstatus(self):
                    return 101
                def send(self, value):
                    pass
                def settimeout(self, value):
                    pass
                def recv(self):
                    if self.initial:
                        self.initial = False
                        return json.dumps(snapshot)
                    clock[0] += 1
                    raise websocket.WebSocketTimeoutException()
                def close(self):
                    pass
                def ping(self):
                    pass
            result = QuietSocket()
            connections.append(result)
            return result
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(cutoff.lab, "private_directory", return_value=Path(directory)), \
                patch.object(cutoff.lab, "session", return_value=self.data), \
                patch.object(cutoff.time, "time", side_effect=lambda: clock[0]), \
                patch.object(cutoff.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(cutoff.signal, "signal"), \
                patch.object(websocket, "create_connection", side_effect=connect), \
                contextlib.redirect_stdout(io.StringIO()):
            self.watcher.run()
        self.assertEqual(len(connections), 3)
        self.request.assert_called_once()
        self.assertEqual(self.watcher.status["phase"], "stop_confirmed")


class GitGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "repo"
        self.repo.mkdir()
        self.run_git("init", "-q")
        self.run_git("config", "user.name", "Lab Test")
        self.run_git("config", "user.email", "lab@example.invalid")
        # Ignore machine/global hooks and configure this disposable repo explicitly.
        self.run_git("config", "core.hooksPath", str(self.repo / ".git/hooks"))
        self.call_guard("install", check=True)

    def run_git(self, *args, check=True):
        return subprocess.run(["git", *args], cwd=self.repo, capture_output=True, check=check)

    def call_guard(self, mode, input=None, check=False):
        return subprocess.run([sys.executable, str(GUARD_SCRIPT), mode], input=input,
                              cwd=self.repo, capture_output=True, check=check)

    def test_safe_commit_passes_and_session_file_is_blocked(self):
        (self.repo / "README.md").write_text("Safe documentation.\n")
        self.run_git("add", ".")
        self.run_git("commit", "-qm", "safe")
        (self.repo / "session.json").write_text("{}")
        self.run_git("add", "session.json")
        result = self.run_git("commit", "-qm", "unsafe", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"private session", result.stderr)

    def test_jwt_is_blocked_without_echoing_secret(self):
        secret = fake_jwt()
        (self.repo / "innocent.txt").write_text(secret)
        self.run_git("add", ".")
        result = self.run_git("commit", "-qm", "unsafe", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn(secret.encode(), result.stdout + result.stderr)
        self.assertIn(b"JWT-like", result.stderr)

    def test_push_blocks_secret_deleted_in_later_commit(self):
        (self.repo / "safe.txt").write_text("safe")
        self.run_git("add", ".")
        self.run_git("commit", "-qm", "base")
        base = self.run_git("rev-parse", "HEAD").stdout.decode().strip()
        (self.repo / "accident.txt").write_text(fake_jwt())
        self.run_git("add", ".")
        self.run_git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "accidental secret")
        self.run_git("rm", "accident.txt")
        self.run_git("commit", "-qm", "delete secret")
        tip = self.run_git("rev-parse", "HEAD").stdout.decode().strip()
        result = self.call_guard("push", f"refs/heads/main {tip} refs/heads/main {base}\n".encode())
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"accident.txt", result.stderr)

    def test_actual_push_hook_blocks_upload_to_disposable_remote(self):
        remote = Path(self.temp.name) / "remote.git"
        subprocess.run(["git", "init", "--bare", "-q", str(remote)], check=True)
        self.run_git("remote", "add", "origin", str(remote))
        (self.repo / "safe.txt").write_text("safe")
        self.run_git("add", ".")
        self.run_git("commit", "-qm", "safe")
        self.run_git("push", "origin", "HEAD:refs/heads/main")
        self.run_git("-c", "core.hooksPath=/dev/null", "commit", "--allow-empty", "-qm", fake_jwt())
        result = self.run_git("push", "origin", "HEAD:refs/heads/main", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"commit", result.stderr)
        remote_tip = subprocess.run(["git", "--git-dir", str(remote), "log", "-1", "--format=%s", "main"],
                                    capture_output=True, check=True).stdout.strip()
        self.assertEqual(remote_tip, b"safe")

    def test_exact_saved_opaque_token_is_detected(self):
        token = "private" + "x" * 40
        self.assertIsNotNone(guard.reason("notes.txt", token.encode(), [token.encode()]))
        self.assertIsNone(guard.reason("notes.txt", b"ordinary source", [token.encode()]))

    def test_common_api_credentials_and_private_keys_are_detected(self):
        credentials = [b"ghp_" + b"a" * 36, b"github_pat_" + b"a" * 50,
                       b"AIza" + b"a" * 35, b"AKIA" + b"A" * 16,
                       b"api_key = '" + b"x" * 40 + b"'",
                       b"-----BEGIN " + b"RSA PRIVATE KEY-----"]
        for value in credentials:
            self.assertIsNotNone(guard.reason("classes.dex", value, []))
        self.assertIsNone(guard.reason("client.kt", b'header("Authorization", "Bearer " + token)', []))

    def test_signing_key_containers_are_blocked(self):
        self.assertIsNotNone(guard.reason("assets/signing.keystore", b"binary", []))
        self.assertIsNone(guard.reason("META-INF/CERT.RSA", b"public signature", []))


if __name__ == "__main__":
    unittest.main()
