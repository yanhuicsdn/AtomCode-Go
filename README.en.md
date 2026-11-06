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

This is a development build, not a completed compatibility certification. A local ARM64 APK has been built and tested on an **Up Mobile Mover Edition running Android 14** and an **ARM64 Android 16 emulator**. Local startup, TLS/cookie authentication, rejected unauthenticated requests, real Linux terminal commands, Git, file edits and restart persistence have been exercised. See the verification records for the exact artifact and outstanding checks.

The app declares Android 8.0/API 26 as its minimum, but Android 8 hardware, additional vendors and 16 KB page-size devices still require acceptance testing. The reference's `targetSdk 28` execution strategy is used for sideloading; this is not a Play Store release.

### Model authentication limitation

**A source build does not contain AtomCode's closed-source official CodingPlan request signer. AtomGit login alone cannot enable official CodingPlan models.** Configure your own compatible model provider and API key in Model configuration. The UI reports the missing build capability. Official-model support requires the actual official signing component; enabling an empty feature flag cannot provide it.

Real-model end-to-end acceptance remains outstanding until a supported model service is configured and tested. Successful local engine tests do not imply successful model inference.

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
- [GitHub repository](https://github.com/yanhuicsdn/atomcode-android)
- [GitCode repository](https://gitcode.com/superyan/AtomCode-Go)

The Android host is independently implemented using dsh-Go as an architectural reference; its code and artwork have not been copied. AtomCode's engine and this repository use the [MIT license](LICENSE). Distributed runtime dependencies retain their own licenses, including PRoot's GPL-2.0 and talloc's LGPL-3.0-or-later. Dependency versions, hashes and source locations are recorded in [the runtime manifest inputs](android/runtime/dependencies.json).

The workspace requires Android System WebView 99 or newer. Missing or incompatible providers display a native upgrade diagnostic. See the verification record for the remaining platform and model checks.
