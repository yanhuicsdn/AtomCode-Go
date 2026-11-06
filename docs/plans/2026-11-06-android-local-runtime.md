# AtomCode Android local runtime implementation plan

**Goal:** Deliver the sideloaded ARM64 Android application defined in `docs/android/GOAL-AND-ACCEPTANCE.zh-CN.md`, following dsh-Go's local runtime implementation logic.

**Architecture:** Android owns installation, process supervision and presentation. PRoot runs a bundled Linux userspace containing the existing AtomCode daemon and embedded WebUI. The existing coding runtime remains the only agent/session owner; user home and workspaces must survive environment replacement.

**Tech Stack:** Rust daemon, existing Preact WebUI, Android SDK (API 26 minimum), Java 17 Android host, Termux PRoot, pinned Linux ARM64 userspace. Sideload target API 28 follows the reference's executable-files constraint; this is not a Play Store build.

## Baseline and reference

- Working branch: `codex/android-local-runtime`; clean source baseline `c0e3e5dd3` (upstream base `c4a2fbfce`).
- Reference: `ccch1mneyyy/dsh-Go@cf2f7c861e1098661a3a2039b3a4cb1633790102`.
- Inspected reference: Android Gradle configuration, runtime manifest, installer, controller, process ownership, dependency notices; build script and launcher inspection continues before implementation.
- Use independently written host code informed by the reference. Copying reference code requires explicit provenance and AGPL compliance; no brand/artwork reuse.
- Available: Rust 1.95, Node 22, Android SDK/NDK, Java 17. No connected Android device at baseline. Do not claim device acceptance without actual evidence.
- User constraint: compile, package and test locally. Do not use GitHub Actions or upload build artifacts to GitHub.

## Task 1: Build and verify the Linux runtime

**Files:** `android/runtime/build.py`, `android/runtime/dependencies.json`, `android/runtime/tests/`, `webui/package-lock.json`.

1. Inspect the reference's dependency pinning, executable packaging, PRoot arguments, DNS and TLS setup.
2. Lock WebUI dependencies and build embedded assets; run type checks and WebUI tests.
3. Build `atomcode-daemon` for Linux ARM64 with fixed Rust target/toolchain; inspect ELF architecture and dependencies.
4. Fetch every distributed runtime input against recorded SHA-256 values; retain licenses and source provenance.
5. Create the rootfs payload and runtime manifest with compressed and extracted sizes, hashes, ABI and source revision. Reject missing binaries/assets.
6. Exercise archive validation and reproducibility checks on the host. Actual PRoot startup is a device test, not a macOS ELF inspection.

## Task 2: Implement Android installation and durable storage

**Files:** `android/settings.gradle`, `android/build.gradle`, `android/app/build.gradle`, `android/app/src/main/AndroidManifest.xml`, host runtime Java classes and tests.

1. Add a reproducible Gradle wrapper, ARM64 flavor, API 26 minimum and executable native-library extraction.
2. Validate the manifest and asset hashes; preflight disk space; report progress and actionable errors.
3. Extract to staging with bounded sizes and safe paths/link handling; atomically activate only complete installations.
4. Keep user home/workspaces outside replaceable rootfs and bind them into the guest. A failed/interrupted install never mutates user data.
5. Test corruption, traversal, links, low-space planning, activation failure and interrupted replacement recovery.

## Task 3: Launch and authenticate the local daemon

**Files:** Android process controller/service and launcher; minimal daemon transport seam if necessary.

1. Build PRoot argv/environment from fixed paths; set home, workspace, temporary paths, certificate trust and device DNS.
2. Maintain one launch generation, foreground notification, explicit stop/restart and bounded startup monitoring.
3. Authenticate the server identity before sending a startup token. Prefer a private filesystem Unix socket boundary and authenticated local presentation transport over a racy connect-to-port identity check.
4. Reuse daemon authentication and HttpOnly Cookie handoff. Never enable `--no-auth`; never log tokens, credentials or full sensitive process environments.
5. Test forged service, port collision, invalid token, cross-origin access, cancellation, stale callbacks and process exit. Public daemon behavior must remain compatible.

## Task 4: Deliver the mobile UI and workspace transfer

**Files:** Android Activity/WebView and workspace archive classes; relevant WebUI mobile adjustments only if necessary.

1. Show installation size/progress, runtime status, start/stop/retry and useful failures.
2. Restrict WebView navigation and subresources to the authenticated local origin; disable file/content access, third-party cookies and unneeded bridges.
3. Handle keyboard, insets, Activity recreation, service death and file chooser lifecycle.
4. Import/export workspaces through the Android document picker with bounded archives, traversal/link rejection, no silent overwrite and an explicit stopped-runtime boundary.
5. Exclude all private files and WebView state from Android backup.

## Task 5: Validate and package

**Files:** `docs/android/BUILD.zh-CN.md`, `docs/android/VERIFICATION.zh-CN.md`, local build and acceptance evidence scripts.

1. Run meaningful host unit tests, Android build/lint, relevant nextest tests, `bash gates/compile.sh`, and formatting checks as required by `AGENTS.md`.
2. Produce APK and SHA-256 with recorded signing identity and source/ dependency versions; keep unsigned-release and debug signing distinctions explicit.
3. Run installation/lifecycle/auth/core workflows on an available emulator, then ARM64 devices. Record actual devices, ABI, API, page size, network and model setup without secrets.
4. Record every acceptance item as passed, failed or unverified with evidence. Missing hardware/model service remains an explicit outstanding requirement.

## State ownership and failure semantics

- Android installer owns immutable runtime generations and an atomic activation pointer; user data is independently persistent.
- Android service owns the process lifetime. Runtime generation changes invalidate startup monitors and WebView connection information.
- AtomCode daemon/coding runtime retain all session and agent state. No second agent or session store is introduced.
- Failed preparation, checksum, extraction, identity verification or startup fails closed. It must not mark ready, open an unauthenticated UI or silently create a replacement session.
- Acceptance documentation remains authoritative: implementation/build success is not final acceptance.
