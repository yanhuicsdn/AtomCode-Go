# AtomCode-Go local build

Compilation, packaging and tests run locally. No GitHub Actions or remote build workers are used. Fetching pinned open-source dependencies does not move compilation to a remote service.

## Prerequisites

- Rust 1.95.0 and rustup, with `aarch64-unknown-linux-musl`.
- Node.js 22.12.0+ and npm; the committed WebUI lockfile is used by `npm ci`. The build script checks this minimum before installing dependencies.
- Python 3.9+. Host tools are pinned in `android/runtime/requirements.txt` and installed into `.tools/python`.
- JDK 17+, selected through `JAVA_HOME`; Java 8 cannot build this Android project.
- Android SDK platform 36 and build tools 36.1.0 or a compatible version, selected through `ANDROID_HOME` or `ANDROID_SDK_ROOT`.
- The Gradle 8.13 wrapper verifies its distribution SHA-256; Android Gradle Plugin is pinned to 8.11.1.

From the repository root:

```bash
export JAVA_HOME=/absolute/path/to/jdk17/Contents/Home
export ANDROID_HOME=/absolute/path/to/android-sdk
bash android/build-local.sh
```

The script builds WebUI, the ARM64 Linux daemon, the runtime payload and APK in that order. Do not run payload assembly and APK packaging concurrently: the packaging gate rejects incomplete or mismatched payloads.

Outputs in `dist/android/` include the debug APK, device test APK, SHA256SUMS, dependency inputs and the runtime build report. Generated payloads, local toolchains, signing keys and credentials are excluded from Git.

Before downloads or compilation, the driver runs `python3 android/runtime/verify_frontend_lock.py`. The frontend lock now has 178 pinned registry artifacts and one bundled child protected by its parent. The 138 added URL/SHA-512 pairs preserve all versions and dependency relationships; existing mirror URLs retain officially matching hashes. Missing/invalid pins fail closed. Partial source archives and the separate original-notice supplement are documented in [source materials](SOURCES.en.md).

When unset, `SOURCE_DATE_EPOCH` defaults to the current Git commit's timestamp. An explicit override is accepted only as decimal digits within the unsigned 64-bit range; empty, negative, malformed or overflowing values stop the build. The daemon build watches both this environment variable and `webui/dist`, including added assets or a newly created directory, so an incremental build refreshes its embedded WebUI when these inputs change.

The daemon artifact is selected from Cargo metadata's actual `target_directory`, respecting `CARGO_TARGET_DIR` and Cargo configuration. The path is `<target_directory>/aarch64-unknown-linux-musl/release/atomcode-daemon`. Standalone `python android/runtime/build.py` uses the same lookup by default; `--daemon /absolute/path/to/atomcode-daemon` overrides it.

After compiling the daemon, the script generates complete locked Cargo metadata filtered for `aarch64-unknown-linux-musl`, then runs `android/runtime/notices.py`. The APK receives `assets/runtime/licenses/dependencies/index.json` and byte-preserved `texts/<sha256>.txt`; the full metadata stays in the Cargo target directory. The index records package/version, original package-relative paths, hashes and missing texts. Its Cargo normal/build dependency graph is a workspace feature-unified superset, and its installed npm lock entries include development dependencies and fonts; neither selection proves final linked or tree-shaken membership. The local inventory found 290 Cargo packages, 165 installed npm packages and 327 unique texts, with no per-package text found for 26 packages (20 Cargo, 6 npm). The shared repository MIT license is recorded separately and does not clear those missing-text entries. This is a notice inventory; complete corresponding-source delivery remains open.

Baseline `9ba42f01d` completed a local build from an isolated source checkout with empty generated outputs on this same host. A mismatched Ubuntu download was first rejected; fetching bytes matching the original committed pin allowed the build to finish without changing that pin. That baseline alone did not establish a byte-identical rebuild.

Current candidate source `bbec500c1` completed the full local driver with `SOURCE_DATE_EPOCH=1793993007`, using an already populated external Cargo target and verified dependency caches: 402 WebUI tests, 43 Python tests and 31 JVM tests passed; build/lint exited zero and all 75 Gradle tasks executed without cache hits. Fifteen separate offline-coordinator host tests passed. The staged APK is `f67bbd8c…`, test APK `313f0f49…`, runtime `atomcode-23286d8bafd6bc29`. A same-host clean uncached APK assembly using the unchanged pinned payload reproduced the APK SHA-256; daemon/rootfs were not rebuilt in that repeat. It is not a three-artifact repeat or a fresh-machine build. Exact hashes and scope are in [VERIFICATION.en.md](VERIFICATION.en.md).

Baseline source `2cebadbc0` previously completed the driver from empty external Cargo target/runtime output with retained caches/toolchain: 402 WebUI, 19 Python and 31 JVM tests, all 75 Gradle tasks executed. Its controlled same-host fixed-epoch (`1793982551`) rebuild actually regenerated daemon/rootfs/APK, all byte-identical to baseline `28b2a612…`. That older three-artifact result is retained as baseline evidence and is not a rebuild of candidate `f67bbd8c…`.

## Runtime inputs and storage

The implementation follows the local-runtime approach of dsh-Go at `cf2f7c861e1098661a3a2039b3a4cb1633790102`, using independently written Android host code. Ubuntu Base 24.04.5, Ubuntu Git/CA packages and Termux PRoot/talloc/android-shmem inputs have committed SHA-256 pins in `android/runtime/dependencies.json`. The reference's PRoot 5.1.107.98 package is no longer available; the port pins 5.1.107.102 and cross-checks its source hash against the official Termux recipe.

Assembly executes no foreign-architecture code. It checks ELF architectures and records the talloc dynamic-string rename required by APK native-library naming. The rootfs ZIP contains regular files and directories only; links are metadata restored last by the installer. Ubuntu package copyright files and native-component notices are retained.

Native PRoot components are extracted by Android into `nativeLibraryDir`. Linux generations live under app-private `files/runtime/`; the active generation pointer is committed atomically. User settings, sessions and projects live separately under `files/home/` and survive runtime replacement.

Startup validates the daemon and these five pinned userspace files against digests derived from the verified bundled ZIP, plus the required Linux aliases and execute permissions:

```text
/usr/bin/env
/usr/bin/bash
/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1
/usr/lib/aarch64-linux-gnu/libc.so.6
/usr/lib/aarch64-linux-gnu/libtinfo.so.6.4
```

This covers critical boot/terminal dependencies, not every Linux file. Expected boot digests are cached by the asset SHA-256; DNS refresh does not unpack the ZIP or walk a generation.

Reclamation runs only after a successful pointer commit and a matching retention record. It keeps the active generation and the pre-install active generation if that older directory has a valid completion marker; unknown entries and top-level symlinks are not pruned. Retired completed generations are atomically moved to the host-reserved `files/runtime/.gc-staging` before deletion. A later valid startup can finish an interrupted deletion even if that slot's `.ready` was already removed. Cleanup failure leaves the committed environment usable and retries later; `files/home/` is outside this cleanup scope.

## Install and run the device tests

```bash
adb -s DEVICE install -r dist/android/atomcode-go-arm64-debug.apk
adb -s DEVICE install -r dist/android/atomcode-go-arm64-smoke-tests.apk
adb -s DEVICE shell am force-stop io.github.yanhuicsdn.atomcode
adb -s DEVICE shell am instrument -w \
  io.github.yanhuicsdn.atomcode.test/io.github.yanhuicsdn.atomcode.LocalRuntimeSmoke
```

The smoke suite uses the actual Linux/PRoot daemon, TLS gateway, WebView, terminal and filesystem. It creates a new test project rather than overwriting user projects. It does not call a model and cannot establish real-model acceptance.

Keep the device unlocked during UI checks. Dismiss Android’s first-use fullscreen hint; on emulators with a hardware keyboard, enable the on-screen keyboard before testing IME resizing.

After configuring a supported model inside the app, the optional real-model test is:

```bash
adb -s DEVICE shell am instrument -w -e real_model true \
  io.github.yanhuicsdn.atomcode.test/io.github.yanhuicsdn.atomcode.LocalRuntimeSmoke
```

It calls the configured service, may consume its quota, and writes only within a new acceptance project. Credentials and raw model responses are not copied into the test report. Only explicitly expected test actions may receive one-time approval; unrelated requests are denied.

An old avdmanager may create Android 36.1 images with `target=android-0`, which disables ARM64 HVF acceleration. Correct only the newly created test AVD's `.ini` target to `android-36`; do not clear existing user AVDs. This project used `AtomCode_API36_Arm64`.

## Application behavior

Opening the app automatically starts or installs the environment. Once ready, the workspace occupies the full screen. Installation failures remain on the native status screen. A user-requested stop is respected; a configuration change does not immediately restart a deliberately stopped engine. Stop the engine from its Android notification to access native workspace import/export.

The initial payload expands to approximately 224 MB in decimal units; allow additional space for the APK, staging, retained generations and projects. The actual build manifest and installer calculate the required space.

The daemon is reached over an app-private Unix socket. The Android host owns a random loopback TLS port; WebView pins the app's certificate before exchanging the daemon token for a Secure/HttpOnly cookie. External navigation opens in a browser, external subresources are blocked, and no LAN listener is created.

ZIP workspace import/export is limited to 2 GB and 100,000 entries. Imports create a new project. Exports fail explicitly on symlinks or special files. Stop the runtime before transferring a workspace.

## Signing, compatibility and model access

The default artifact is signed by the local Android debug keystore and is a development package. `assembleRelease` produces an unsigned artifact; it does not silently substitute the debug key. Production release requires a retained signing key, monotonic version codes and completed device/upgrade acceptance. Changing the signing key prevents an in-place APK upgrade; uninstalling deletes app data.

Minimum SDK is 26, ARM64 only. Target SDK 28 follows the reference's private-executable strategy and is a sideloading constraint, not a Play Store-ready configuration. PRoot ELF LOAD alignment is 16 KB, but execution is measured separately: current candidate `f67bbd8c…` recovered from an actual pre-commit upgrade force-stop on `AtomCode_API36_16K_Arm64` (official API 36 ARM64 `google_apis_ps16k` revision 7; `getconf PAGE_SIZE=16384`) and then passed 12 smoke checks. Current-candidate 16 KB strict offline 12, independent-UID 7+2 and chooser seven groups also passed; the actual two-PNG FileReader/revocation checks also passed. No backup test was run there, and this does not establish all 16 KB devices.

Current candidate `f67bbd8c…` also passed 12 strict offline smoke checks, seven chooser-adapter checks, independent-UID 7+2, active-package backup rejection and real fixed-PNG FileReader checks on the AOSP API 36 ARM64 `AtomCode_Backup_AOSP36_Arm64` emulator, measured 4096-byte pages. The physical Android 14/API 34 ARM64 phone still uses baseline `28b2a612…`; only that baseline passed same-key upgrade, native 7+2 and ten isolated cache-storage checks there. Candidate `f67bbd8c…` has not been installed or UI-tested on the phone. Real models remain unpassed.

**Source builds do not contain the closed-source official CodingPlan request signer. AtomGit login alone does not enable official models.** Add your own compatible model account/API key in Model configuration. The current binary advertises its real signing capability and explains this limitation. The port does not implement or bypass that private signing protocol.

Foreground service status does not guarantee unlimited background execution. The wake lock currently lasts at most 30 minutes; forced stops and vendor restrictions still apply.

Before public production distribution, complete the corresponding-source/license package, production signing and the remaining device matrix. Source URLs alone do not replace any applicable corresponding-source obligation.

## Additional host checks

The Android workspace requires an installed/enabled Chromium-based System WebView 99 or newer, matching the WebUI build target. Older, missing or broken providers display a native upgrade diagnostic. This does not establish Android 8 execution compatibility. ZIP import needs temporary storage for both the compressed archive and expanded project; each is limited to 2 GB. Incomplete central directories and file size/CRC mismatches are rejected.

On an unlocked emulator, run isolated storage checks with:

```bash
adb -s DEVICE shell am force-stop io.github.yanhuicsdn.atomcode
adb -s DEVICE shell am instrument -w -e storage true \
  io.github.yanhuicsdn.atomcode.test/io.github.yanhuicsdn.atomcode.LocalRuntimeSmoke
```

This uses bundled runtime assets in a new cache directory and removes that directory afterward. It tests callback cancellation and injected zero usable space, not an abrupt process kill or physical disk exhaustion.

For true offline local-runtime verification, use the guarded host coordinator with already installed matching APKs and a new output directory:

```bash
python3 android/tests/run_offline_smoke.py --serial emulator-5564 \
  --avd AtomCode_Backup_AOSP36_Arm64 --adb "$ANDROID_HOME/platform-tools/adb" \
  --apk /absolute/path/to/matching-product.apk \
  --tests-apk /absolute/path/to/matching-tests.apk \
  --output /tmp/atomcode-offline-new
```

It accepts only that 4096-byte AVD or `AtomCode_API36_16K_Arm64` / 16384-byte pages. It verifies no live active/default/connected network during the default 12 checks, stops the owner and restores actual network preferences/connection; it does not change the mobile-data preference or IME. UI prerequisites still apply. On the AOSP 4 KB candidate, an initial nine-PASS run failed at keyboard verification while Android's first-use fullscreen hint was unacknowledged. After the normal “Got it” action, the unchanged product and strict 12 assertions passed; both records are retained.

The optional `-e file_chooser true` instrumentation mode gives seven Activity/WebView adapter checks with synthetic content selections (use `am instrument -w -r` for the explicit raw result). For actual cross-UID bytes, use the separate test-only [public-PNG fixture](../../android/tests/fixtures/public-png-upload/README.md), with its named-AVD/page guard, matching signatures, fresh output and `--confirm-public-png-test`. It grants only two fixed 70-byte public PNGs, validates real DOM/FileReader bytes and same-process revocation, then removes only known canaries. It is never bundled into the product. These are not manual DocumentsUI or model-upload tests.

### Real low-space preflight (owned emulator only)

`run_real_space.py` is separate from the injected-space suite. Use only the already running `AtomCode_API36_16K_Arm64` AVD with default userdata, after verifying its `.ini` path and active qemu backing files are on the checked host filesystem. Keep the installed app matching `--apk`; build and install the updated device-test APK from `c5700c128` or later. The coordinator rejects phone serials and old test APKs without the real-space entry before ADB, then verifies the exact owned AVD, 16 KB pages and installed artifact hashes. Use a new/empty output directory:

```bash
adb -s emulator-5562 install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 android/tests/run_real_space.py --serial emulator-5562 \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --apk dist/android/atomcode-go-arm64-debug.apk \
  --tests-apk android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --output /tmp/atomcode-real-space-new --confirm-fill-owned-emulator
```

The fixture is a unique child of app **files**, using an ordinary `File` and real `getUsableSpace()`, not a cache fixture or overridden space value. Limits remain at most a 16 GiB data filesystem, 6 GiB filler, 120 seconds filling, at least 256 MiB guest reserve, and at least 8 GiB initial host headroom. Device/host test deadlines are 240/360 seconds. Sequential writes are synced and allocated blocks are checked; failure/timeout cleanup stops the test before removing only its exact fixture, and is not counted as a pass. Do not automatically retry a failed/timed-out fill. No production APK install, real-home access, phone/UI/model test or settings change is performed.

Baseline `28b2a612...` app with separately built `9e1ddc61...` tests passed 5/5: 4,196,466,128 bytes written, 4,196,474,880 allocated, and actual available space 318,488,576 below required 335,266,352 bytes. The space error occurred before any extraction callback; old fixture data stayed intact, then installation succeeded after filler release. Guest fixture cleanup restored available space within 57,344 bytes of the initial reading, consistent with normal system activity; the retained host qcow2 grew, so host free space was not fully reclaimed. See [verification](VERIFICATION.en.md#real-low-space-preflight) for evidence and scope. This baseline case was not rerun with candidate `f67bbd8c…`. It does not establish physical NAND exhaustion, mid-install ENOSPC or real-user-home recovery.

The local source inventory (`python android/runtime/source_inventory.py`) merges Ubuntu Base status with verified overlaid Debian control metadata. The current payload contains 117 Ubuntu binaries from 82 source/version pairs and three Termux natives. `source-inventory.json` records those source acquisition pages and replaces stale Base package versions; it is not the complete corresponding-source distribution.

### Rotation and on-screen keyboard

Keep the device unlocked, dismiss the first-use fullscreen hint and use an enabled, docked on-screen IME. On an emulator with a hardware keyboard, enable the on-screen keyboard preference first and restore that global preference afterward; the helper does not change it. Run:

```bash
adb -s DEVICE shell am force-stop io.github.yanhuicsdn.atomcode
adb -s DEVICE shell am instrument -w -e layout true -e keyboard_rotation true \
  io.github.yanhuicsdn.atomcode.test/io.github.yanhuicsdn.atomcode.LocalRuntimeSmoke
```

For rotation/long-text checks without the keyboard combination, omit only `-e keyboard_rotation true`. Keyboard mode verifies a visible IME and that WebView/composer bounds remain above it in portrait and landscape. If Android hides the IME during Activity recreation, the test touches the composer again before checking the new orientation. No model request is sent. The helper restores its own orientation/keep-screen flags and removes its canary; this is focused layout coverage, not the full mobile acceptance matrix.

### Independent-UID security probe

First install matching owner app and device-test APKs as above. Build/install the separate test-only probe locally:

```bash
android/gradlew -p android :probe:assembleDebug --console=plain
adb -s DEVICE install -r -t android/probe/build/outputs/apk/debug/probe-debug.apk
python3 android/tests/run_security_probe.py --serial DEVICE \
  --adb "$ANDROID_HOME/platform-tools/adb" --output dist/android/evidence/security-DEVICE
```

`:probe` is not bundled into `:app`; it has no shared UID or launcher and its release variant is disabled. `-t` is required for its test-only manifest. Run the coordinator when no user/model operation is active: it stops/restarts the owner's runtime without clearing app data. It passes public endpoint/certificate metadata only, never a token or cookie, and requires seven foreign-UID checks plus two owner-health checks. Results are written under `--output`. This checks the live private socket, unauthenticated TLS requests, Origin/Host filtering, occupied-port exclusivity and platform certificate trust; it does not establish every pre-launch takeover race, upload path or backup behavior.
