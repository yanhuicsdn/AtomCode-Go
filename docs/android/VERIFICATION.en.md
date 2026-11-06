# AtomCode-Go verification and acceptance record

Date: 2026-11-06. Status: development delivery; final acceptance has not been completed. Executed checks and outstanding device/model requirements are reported separately.

## Delivered artifact and device verification

The locally built AtomCode-Go 0.1.0 ARM64 debug APK has SHA-256:

```text
a2483445cde4fc4b6171f0da15413b5d8c2239edbeeac6df1c52de2e98fcdaf5
```

Runtime: `atomcode-752a620892e947e2`. ARM64 Linux daemon SHA-256: `39393afd3622e7f32b39fd4d2cc254fb2d897fca6faa246c3075b382e29949da`.

Android host source: `1d66f07126d11eaa2102da6393300c62576efde7`. Bundled engine/WebUI build source: `a05c031ef27156cc733c0b144047ee562325f4d3`; later implementation changes affect the Android host and tests. Exact source/dependency identifiers are included in local `delivery.json` and `build-report.json`.

Debug certificate SHA-256: `82923019b3fc796d3ed78d14561ab1d8ae966986e40a1936076ac6d795b02d41`. The signing key is not included in source or documentation.

| Environment | System | ABI / page size | Observed result |
| --- | --- | --- | --- |
| Physical Up Mobile Mover Edition | Android 14 / API 34 | ARM64 / 4096 bytes | Initial fresh install, current data-preserving upgrade and all 12 checks passed |
| sdk_gphone64_arm64 emulator | Android 16 / API 36 | ARM64 / 4096 bytes | All 12 checks passed, including a repeat with Wi-Fi and mobile data disabled |

The suite uses real PRoot, Linux daemon, TLS gateway, WebView and PTY processes. It does not substitute a remote AtomCode server. Verified behavior includes:

- Bundled runtime extraction, automatic startup on Activity launch and matching executable hash.
- Fullscreen workspace with native controls/system bars hidden; an actual composer touch opens the keyboard and resizes WebView.
- Exact TLS certificate pin, token handoff to a Secure/HttpOnly/SameSite cookie, and token-free redirect.
- Incorrect certificate rejection, unauthenticated HTTP 401, authenticated access and foreign-Origin HTTP 403.
- Real Linux PTY commands and Git, actual generated workspace files and exit status 7.
- Daemon file read/edit APIs with revision checks and direct verification of the stored bytes.
- Actual WebView content, with no token in the post-redirect query string or JavaScript-readable cookies.
- Explicit stop/rapid restart, token rotation, previous-token rejection, persistent workspace/native session metadata and a retained foreground service.
- All local checks repeated on the emulator with Wi-Fi/mobile data disabled. This does not establish offline model inference.

The engine intentionally hides empty conversations in its normal history list. Persistence was checked by directly resolving the native session ID, rather than changing that visibility policy.

## Local automated checks

- WebUI build, TypeScript check and 402 tests passed.
- Official npm registry audit: the old lock reported eight advisories; updates within the existing version ranges reduced the reported count to zero. This is not a proof that no vulnerability exists.
- Daemon: 402 nextest tests passed, including private-socket shutdown/streaming and existing authentication tests.
- Coding crate: all 1,245 nextest tests passed; 10 skipped. A stale assertion was corrected to accept Ollama’s public `local` placeholder while retaining host-credential isolation checks.
- `bash gates/compile.sh` passed, covering workspace/all-targets, gated features and negative controls.
- Runtime packaging: 12 Python tests passed for traversal, links, corrupted hashes, hardlinks and deterministic ZIP output.
- Android: 11 JVM tests passed; debug build and lint passed with zero errors and four localization/storage warnings.

## Real-model status

A real-model request failed after the user completed AtomGit login. Inspection confirmed the shared provider factory returns `SourceBuildGatewayUnsupported`: the public source does not contain the official CodingPlan request signer. A stored login and an available official model-signing implementation are separate requirements.

The user chose to defer compatible-provider configuration and receive the APK with real-model acceptance explicitly unpassed. This source build needs a user-configured compatible model API. Real-model streaming, model-driven approval/denial, cancellation and complete conversation resume have not passed acceptance. Model tests do not print credentials, connection URLs, raw model responses or tool contents.

## Acceptance matrix

| Criterion | Evidence and outstanding work |
| --- | --- |
| 1 Reproducible delivery | Local build, lockfiles, hashes and ELF audit exist; clean-machine rebuild, production signing and complete corresponding-source/license distribution remain |
| 2 Installation/startup | Android 14 device and Android 16 emulator fresh installation/start/stop passed; isolated device tests pass callback cancellation, injected zero usable space, and installed-daemon corruption/repair; abrupt kills and physical storage exhaustion remain |
| 3 On-device execution | Same-app-UID PRoot/daemon and real local commands/files observed; the emulator passed the complete local suite with Wi-Fi/mobile data disabled; physical-device offline scenarios remain |
| 4 Authentication/security | Private socket, TLS pin, cookies, 401, incorrect pin and foreign-Origin rejection passed; full port-takeover, malicious-app, file-upload and backup tests remain |
| 5 Core workflow | Real terminal, Git and file editing passed; configured real-model tools remain unaccepted because official signing support or a compatible API configuration is required |
| 6 Persistence | Workspace and native session metadata survive restart; real conversation resume, canceled repair and archive APIs passed on both devices; interrupted upgrades, transfer UI and forced-kill recovery remain |
| 7 Mobile usability | Real-device WebView works; updated naming/fullscreen/autostart and keyboard resizing passed on both devices; focused rotation/long-text checks passed on the phone and twice on the emulator, but one initial emulator rotation failure remains unexplained; keyboard-plus-rotation and file-picker coverage remain |
| 8 Lifecycle | Foreground notification and basic explicit stop/restart exercised; long background periods, forced stops and more vendors remain |
| 9 Compatibility/network | Android 14 device and 16 emulator, both 4 KB; Android 8, additional vendors, 16 KB, IPv6/external HTTPS and sleep/network recovery remain |
| 10 Evidence | Local automation and initial device evidence recorded; current artifact/source identifiers and remaining criteria are recorded; the full matrix remains incomplete |

## Changes and delivery scope

The product name is **AtomCode-Go**. This APK fixes a rapid-restart race in which a retired foreground service stopped its replacement, and a gateway shutdown race that could dispatch connections into a stopped thread pool. Both devices were checked with the final APK.

This is a local debug delivery. Real-model workflows, Android 8, more vendors, 16 KB devices and other outstanding matrix items have not passed final acceptance. GitHub defaults to English and GitCode to Chinese; both retain English/Chinese product, build, goal and verification documents. Source publication uses a CI-skipping commit; APKs are not committed and no remote compilation is requested.

## Additional host validation

The current APK rejects imports without a complete ZIP central directory and verifies file sizes/CRC before committing a project. ZIP input and expanded content are each bounded to 2 GB; temporary storage is needed for both. Two new regression cases fail against the previous importer and pass after the fix.

Both devices passed six isolated storage checks using the actual bundled assets: installation, callback cancellation preserving the old pointer/home, injected zero-usable-space preflight, damaged installed-daemon detection/repair, archive roundtrip (UTF-8/empty directories/execute bit), and truncated/traversal import rejection. Tests use disposable cache roots, not the user's home. They do not establish physical disk exhaustion or abrupt-process-kill recovery.

A missing/disabled WebView provider was tested only on the task's emulator. The app remained alive, displayed its native initialization-failure/upgrade diagnostic and contained no WebView node; the provider was restored. Minimum supported WebView syntax target is Chromium 99; old/unknown versions also have JVM coverage, not Android 8 device evidence.

Five focused layout checks passed on the phone. An initial emulator landscape check failed without a detailed observation. Adding controlled diagnostics changed no pass condition; two subsequent emulator runs passed all five checks. The initial failure is retained as an unresolved intermittent observation, not silently discarded. Tests check recreation, the same runtime identity, composer visibility and temporary long-text scrolling; they do not establish persisted drafts or all keyboard/rotation combinations.

The verified-input source inventory lists 117 Ubuntu binaries (82 source/version pairs) and three Termux native components. It correctly records overlaid versions instead of trusting stale Base dpkg status. Corresponding-source archives are still incomplete. Node build preflight now matches the locked tools at 22.12+. Rust engine sources, Cargo manifests/lock and Rust gates are unchanged from the previously verified baseline; this pass reran the affected Android/Python checks.

Open host audit items: successful repairs retain older runtime generations without reclamation; readiness hashes the daemon, not every Linux file. Non-daemon corruption recovery still needs a dedicated reproduction and fix if confirmed.
