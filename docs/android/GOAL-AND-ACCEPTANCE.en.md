# AtomCode-Go goal and acceptance criteria

This is the acceptance baseline. Implementation progress and evidence are recorded in [Verification](VERIFICATION.en.md); the presence of this document does not mean final acceptance passed. Engine baseline: AtomCode `c4a2fbfceb5ee99907d875d3b6ec604a495cc219`. Reference: dsh-Go `cf2f7c861e1098661a3a2039b3a4cb1633790102`. Record the actual source revision, dependency inputs and artifact hashes for each delivery.

## Goal

Deliver a sideloadable ARM64 APK for Android 8.0/API 26 and later. Users install a bundled local environment, configure a supported model service, and chat, edit files and execute commands in the phone's workspace without root, Termux, a computer or a remote AtomCode server. Model inference still needs the user's configured service, credentials and network access. App-private data is deleted on uninstall unless exported first.

Follow the reference's PRoot/Linux userspace approach. Android owns installation, process management and presentation. The existing daemon, coding runtime and native session store remain the agent and session owners. ARM64 physical-device evidence is required; emulator results alone do not establish device compatibility.

## Acceptance criteria

All ten conditions must pass with retained evidence before declaring final acceptance. Record device model, Android version, ABI, APK hash, network conditions and non-secret model configuration. Never record credentials.

1. **Reproducible delivery:** Build pinned sources and verified inputs into an APK containing the correct PRoot, Linux userspace and daemon with built WebUI. Record versions, hashes, licenses and corresponding-source access. Document clean-environment rebuilding, signing and upgrades.
2. **Installation and startup:** Fresh install on supported ARM64 phones, display storage requirements and progress, and recover from interruption, insufficient storage and corrupt inputs. Fail visibly without damaging user data. Start, stop and restart without root or external servers.
3. **Local execution:** Process and network evidence shows the engine runs on the phone without a LAN listener. An installed environment starts offline and supports local file operations. Online model requests use the configured service.
4. **Security and authentication:** Use unpredictable per-run tokens and enabled daemon authentication. Verify unauthenticated rejection, HttpOnly-cookie exchange, exact local service identity, port interception, spoofed listeners and cross-origin rejection. Define external navigation and file-selection/upload boundaries. Keep secrets out of logs, artifacts and Android backups.
5. **Core workflow:** A supported real model streams replies, reads/creates/edits actual workspace files, requests tool approvals, handles both approval and denial, and executes commands with verified bytes and exit status. Cancellation terminates work with a clear result. Service failures must be visible.
6. **Sessions and data:** Workspace and conversations survive restart, process death and environment upgrades. Verify conversation resume, interrupted upgrades, rollback and storage exhaustion. Workspace imports/exports reject traversal and silent overwrites.
7. **Mobile usability:** Check portrait/landscape, keyboard, scrolling, long messages, code, approvals, file selection and terminal touch controls. Keyboard/system bars must not hide controls. Activity recreation and background return preserve committed data; service death is visible and recoverable.
8. **Lifecycle:** Startup/running/stopping states agree with actual processes. Foreground service and notifications work on target Android versions. Explicit stop removes owned processes. Record forced-stop and background limits, startup errors, port conflicts and crash recovery without promising unlimited residency.
9. **Network and platform compatibility:** Verify DNS, HTTPS certificates, IPv4/IPv6 and sleep/reconnect on devices. Cover Android 8, the current target version, multiple vendors and 16 KB pages, or explicitly record unsupported cases. Offline errors must be visible.
10. **Evidence:** Run relevant upstream compile/format/test gates and Android unit/install/end-to-end checks. Deliver APKs, checksums, commands, device results and limitations. Compilation or static checks alone are not device acceptance.

## Implementation boundaries

Authenticate the existing daemon using its token/cookie flow and verify listener ownership before sending credentials. Keep user data separate from replaceable runtime generations. Preserve the existing coding runtime and persistence format. Independently implement the Android host; copying reference code requires its applicable license obligations, and bundled dependencies retain their own licenses.

Run builds and tests locally, then test on emulators and ARM64 phones. Record missing model access, devices or toolchains as unpassed conditions instead of presenting plans as completed evidence.
