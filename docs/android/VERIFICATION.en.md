# AtomCode-Go verification and acceptance record

Date: 2026-11-06. Status: development delivery; final acceptance has not been completed. Executed checks and outstanding device/model requirements are reported separately.

## Initial integration artifact

The initial integration APK, before the naming/fullscreen/automatic-start refinements, has SHA-256:

```text
78cf1210a3162838b3cbfdd81b07b5adc7668aed74f97cdb2a9d2cc77b8d9abf
```

Runtime: `atomcode-9985094f7f62544e`. ARM64 Linux daemon SHA-256: `77b93b1f3e72097e08b9275bddecf7e1f069a010da975e8bfbf075874f001e1a`.

Debug certificate SHA-256: `82923019b3fc796d3ed78d14561ab1d8ae966986e40a1936076ac6d795b02d41`. The signing key is not included in source or documentation.

| Environment | System | ABI / page size | Observed result |
| --- | --- | --- | --- |
| Physical Up Mobile Mover Edition | Android 14 / API 34 | ARM64 / 4096 bytes | Fresh install and local smoke suite passed |
| sdk_gphone64_arm64 emulator | Android 16 / API 36 | ARM64 / 4096 bytes | Local smoke suite passed |

The suite uses real PRoot, Linux daemon, TLS gateway, WebView and PTY processes. It does not substitute a remote AtomCode server. Verified behavior includes:

- Bundled runtime extraction, daemon startup and matching executable hash.
- Exact TLS certificate pin, token handoff to a Secure/HttpOnly/SameSite cookie, and token-free redirect.
- Incorrect certificate rejection, unauthenticated HTTP 401, authenticated access and foreign-Origin HTTP 403.
- Real Linux PTY commands and Git, actual generated workspace files and exit status 7.
- Daemon file read/edit APIs with revision checks and direct verification of the stored bytes.
- Actual WebView content, with no token in the post-redirect query string or JavaScript-readable cookies.
- Explicit stop/restart, token rotation, rejection of the previous token, and persistent workspace files/native session metadata.

The engine intentionally hides empty conversations in its normal history list. Persistence was checked by directly resolving the native session ID, rather than changing that visibility policy.

## Local automated checks

- WebUI build, TypeScript check and 402 tests passed.
- Official npm registry audit: the old lock reported eight advisories; updates within the existing version ranges reduced the reported count to zero. This is not a proof that no vulnerability exists.
- Daemon: 402 nextest tests passed, including private-socket shutdown/streaming and existing authentication tests.
- Coding crate: the full related suite was executed. One stale upstream assertion rejected Ollama's public `local` placeholder; its credential-isolation checks were retained while correcting the accepted no-secret value. Final regression results are recorded with the refreshed build.
- `bash gates/compile.sh` passed, covering workspace/all-targets, gated features and negative controls.
- Runtime packaging: seven Python tests passed for traversal, links, corrupted hashes, hardlinks and deterministic ZIP output.
- Android: five JVM tests passed; debug build and lint passed with zero errors. Localization/storage suggestions remain as warnings.

## Real-model status

A real-model request failed after the user completed AtomGit login. Inspection confirmed the shared provider factory returns `SourceBuildGatewayUnsupported`: the public source does not contain the official CodingPlan request signer. A stored login and an available official model-signing implementation are separate requirements.

This source build needs a user-configured compatible model API. Real-model streaming, model-driven approval/denial, cancellation and complete conversation resume have not passed acceptance. Model tests do not print credentials, connection URLs, raw model responses or tool contents.

## Acceptance matrix

| Criterion | Evidence and outstanding work |
| --- | --- |
| 1 Reproducible delivery | Local build, lockfiles, hashes and ELF audit exist; clean-machine rebuild, production signing and complete corresponding-source/license distribution remain |
| 2 Installation/startup | Android 14 device and Android 16 emulator fresh installation/start/stop passed; device low-space, corruption and interrupted-install cases remain |
| 3 On-device execution | Same-app-UID PRoot/daemon and real local commands/files observed; an independently recorded offline-start case remains |
| 4 Authentication/security | Private socket, TLS pin, cookies, 401, incorrect pin and foreign-Origin rejection passed; full port-takeover, malicious-app, file-upload and backup tests remain |
| 5 Core workflow | Real terminal, Git and file editing passed; configured real-model tools remain unaccepted because official signing support or a compatible API configuration is required |
| 6 Persistence | Workspace and native session metadata survive restart; real conversation resume, interrupted upgrade, transfer UI and forced-kill recovery remain |
| 7 Mobile usability | Real-device WebView works; updated naming/fullscreen/autostart must be rerun, with full orientation/IME/long-content/file-picker coverage still required |
| 8 Lifecycle | Foreground notification and basic explicit stop/restart exercised; long background periods, forced stops and more vendors remain |
| 9 Compatibility/network | Android 14 device and 16 emulator, both 4 KB; Android 8, additional vendors, 16 KB, IPv6/external HTTPS and sleep/network recovery remain |
| 10 Evidence | Local automation and initial device evidence recorded; final refreshed artifact/source identifiers and remaining criteria must be updated |

## Refreshed build

The product is now named **AtomCode-Go**. App launch automatically installs/starts the runtime and switches to fullscreen after readiness. Updated APK and device regression results will be recorded after the local rebuild; the initial artifact cannot stand in for that acceptance.
