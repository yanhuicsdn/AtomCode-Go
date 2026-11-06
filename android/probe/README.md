# Distinct-UID security probe

This is an independent, test-only APK. It is not a dependency of the production
`:app` module, has no launcher, has no shared UID, and does not obtain credentials.
Only its explicitly requested debug variant is built. Release variants are disabled.

The owner instrumentation must keep the real runtime alive and confirm its Unix
socket and authenticated API before and after this probe. The host passes only
public endpoint metadata: `port`, `uds_path`, `owner_uid`, and
`expected_certificate_sha256`. The probe connects only to the supplied private
socket and `127.0.0.1`; it never scans, sends model requests or contacts an external
host. Its custom TLS trust bypass exists only to model a hostile unauthenticated
client and verifies the observed peer hash before sending a request.

```bash
adb -s DEVICE install -r -t android/probe/build/outputs/apk/debug/probe-debug.apk
adb -s DEVICE shell am instrument -w \
  -e port PORT -e uds_path PRIVATE_SOCKET_PATH -e owner_uid OWNER_UID \
  -e expected_certificate_sha256 PUBLIC_CERTIFICATE_SHA256 \
  io.github.yanhuicsdn.atomcode.probe/io.github.yanhuicsdn.atomcode.probe.ForeignUidProbe
```

The seven checks cover UID separation, Unix-socket `EACCES`, unauthenticated API
rejection, foreign Origin, forged Host, an already-bound gateway port, and default
platform TLS trust. They do **not** prove all pre-launch port races, file uploads,
backups, or every malicious-app capability has been tested. Failures report only
a controlled stage and exception class; response bodies and credentials are not
recorded. The probe can be uninstalled independently after the check.
