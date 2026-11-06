# AtomCode-Go

**Your Android phone becomes a local AI coding workspace.**

[简体中文](README.zh-CN.md) · [English](README.en.md) · [Local build](docs/android/BUILD.en.md) · [Verification](docs/android/VERIFICATION.en.md)

AtomCode-Go packages the AtomCode Rust engine and WebUI inside an Android app. A bundled Linux userspace runs through PRoot on the phone, following the local-runtime approach of [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go). Code, shell processes and sessions stay on the device. Model inference uses the service and credentials you configure.

## What it does

- Automatically installs or starts the bundled local environment when the app opens.
- Opens the coding workspace in fullscreen once ready; installation progress and recoverable errors use a native status screen.
- Reuses AtomCode's streaming chat, tool approvals, file editing, terminal and native session storage.
- Keeps the home directory and projects outside replaceable runtime generations.
- Provides workspace ZIP import/export while the engine is stopped, with path checks and no silent project overwrite.
- Uses an app-private Unix socket for the daemon and certificate-pinned loopback TLS for the WebView, with authenticated HttpOnly cookies.
- Runs under a foreground service. Stop it from the Android notification; system background limits still apply.

No root, Termux, computer or remote AtomCode server is required for normal use after installation. An internet connection is required for online models.

## Current delivery status

This is a development build; final acceptance remains incomplete. The current ARM64 candidate `f67bbd8c…` was built locally from `bbec500c1` and tested on an **AOSP Android 16 ARM64 emulator with measured 4 KB pages**: 12 offline local-runtime checks, seven upload-adapter checks, seven independent-UID plus two owner checks, active-package LocalTransport backup rejection, and real cross-UID reads of two fixed public PNGs passed. On the **official Android 16 ARM64 16 KB emulator**, a real pre-commit upgrade force-stop from baseline `28b2a612…` to this candidate recovered automatically and passed 12 smoke checks. Additional current-candidate 16 KB offline 12, independent-UID 7+2 and chooser seven groups passed; the real two-PNG cross-UID FileReader/revocation case also passed. Backup was not tested on this Google APIs image. These tests send no model requests; picker interception does not prove manual DocumentsUI selection or model attachment upload.

The physical **Up Mobile Mover Edition / Android 14** still has baseline APK `28b2a612…`; its prior upgrade, security and isolated-storage passes are retained as baseline evidence. The current `f67bbd8c…` candidate has not been installed or UI-tested on that phone. The app declares Android 8.0/API 26 as its minimum; Android 8 hardware, more vendors and physical 16 KB devices remain unaccepted. The reference's `targetSdk 28` execution strategy is used for sideloading; this is not a Play Store release. Exact artifacts, failures and remaining checks are in [the verification record](docs/android/VERIFICATION.en.md).

### Model authentication limitation

**A source build does not contain AtomCode's closed-source official CodingPlan request signer. AtomGit login alone cannot enable official CodingPlan models.** Configure your own compatible model provider and API key in Model configuration. The UI reports the missing build capability. Official-model support requires the actual official signing component; enabling an empty feature flag cannot provide it.

Model acceptance remains explicitly unpassed in this development delivery. Real-model streaming, model-driven approval/cancellation and real-conversation resume require a compatible service and actual end-to-end verification; successful local-engine tests do not imply successful model inference.

## Build locally

Compilation, packaging and tests run on your own machine. No GitHub Actions or remote build workers are used.

Requirements: Rust 1.95.0, Node.js 22.12.0+, Python 3.9+, JDK 17+, and an Android SDK containing platform 36 and compatible build tools.

```bash
export JAVA_HOME=/absolute/path/to/jdk17/Contents/Home
export ANDROID_HOME=/absolute/path/to/android-sdk
bash android/build-local.sh
```

Outputs are written to `dist/android/`, including `atomcode-go-arm64-debug.apk`, a device test APK, hashes and a runtime build report. Generated APKs, toolchains, credentials and large runtime payloads are excluded from Git. See [the build guide](docs/android/BUILD.en.md) for installation, signing and test commands.

## Architecture

```text
Android Activity + foreground service
  ├─ checked installer → immutable Linux generation
  ├─ PRoot → AtomCode daemon → existing coding runtime
  ├─ private Unix socket ↔ local TLS gateway ↔ fullscreen WebView
  └─ persistent home → sessions, model settings and projects
```

Android manages installation, process lifetime and presentation. AtomCode retains agent and session ownership. The port does not introduce a second agent or a second conversation store.

## Data and distribution

Files are stored in app-private storage. Uninstalling the app deletes them; export your workspace first. Updating the runtime preserves the separate home directory. APK upgrades require a compatible signing key and version code.

The current local APK uses an Android debug signing key. Production signing, complete corresponding-source/license distribution and the remaining device matrix must be completed before a public production release.

## Sources and documentation

- [Goals and acceptance criteria](docs/android/GOAL-AND-ACCEPTANCE.en.md)
- [Build guide: English](docs/android/BUILD.en.md) / [中文](docs/android/BUILD.zh-CN.md)
- [Verification: English](docs/android/VERIFICATION.en.md) / [中文](docs/android/VERIFICATION.zh-CN.md)
- [Architecture constraints](AGENTS.md) and [engine architecture](docs/architecture.md)
- [GitHub repository](https://github.com/yanhuicsdn/AtomCode-Go)
- [GitCode repository](https://gitcode.com/superyan/AtomCode-Go)

The Android host is independently implemented using dsh-Go as an architectural reference; its code and artwork have not been copied. AtomCode's engine and this repository use the [MIT license](LICENSE). Distributed runtime dependencies retain their own licenses, including PRoot's GPL-2.0 and talloc's LGPL-3.0-or-later. Dependency versions, hashes and source locations are recorded in [the runtime manifest inputs](android/runtime/dependencies.json).

The workspace requires Android System WebView 99 or newer. Missing or incompatible providers display a native upgrade diagnostic. See the verification record for the remaining platform and model checks.
