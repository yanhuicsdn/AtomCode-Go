# AtomCode-Go verification and acceptance record

Date: 2026-11-06. Status: development delivery; **final acceptance remains incomplete**. This record separates current-APK evidence, historical artifacts, injected faults and external blockers. Narrow checks do not establish complete acceptance.

## Current artifact and source

AtomCode-Go 0.1.0, ARM64, Android debug signing. Current local APK SHA-256:

```text
15dd396bdab988ccffa1998badade33376ea3c5041c301d04a1890659f2b7339
```

- Android host and current device-test source: `ce6303f66325f991096deec101a6329aba0fa4f8`.
- Layout observation logic originates at `a6f9c2454553e03627c643a34e405dce84c7aa25`. That change improves observation of recreation without changing product layout.
- Bundled engine/WebUI source remains `a05c031ef27156cc733c0b144047ee562325f4d3`; Rust was not rebuilt in this pass.
- Runtime: `atomcode-752a620892e947e2`. Daemon SHA-256: `39393afd3622e7f32b39fd4d2cc254fb2d897fca6faa246c3075b382e29949da`.
- Debug certificate SHA-256: `82923019b3fc796d3ed78d14561ab1d8ae966986e40a1936076ac6d795b02d41`. Private keys are excluded from source and delivery documentation.

Local `delivery.json`, `SHA256SUMS`, `build-report.json` and device evidence identify exact artifacts, test sources and dependency inputs.

## Current device evidence

| Environment | System / ABI / page size | Result for the current APK |
| --- | --- | --- |
| Physical Up Mobile Mover Edition | Android 14 / API 34, ARM64, 4096 bytes | Current APK passed ten storage checks, seven independent-UID probes and two owner checks, including inspection of actual runtime-parent argv. UI awaits user unlock and is not recorded as passed |
| sdk_gphone64_arm64 emulator | Android 16 / API 36, ARM64, 4096 bytes | Current APK passed three consecutive online smoke runs, 12 checks each, plus ten storage checks, seven independent-UID probes, two owner checks and six keyboard/rotation checks. Current offline rerun evidence is not yet available; older evidence is scoped below |

Evidence for the current `ce6303f66` APK:

- `atomcode-processfix-emulator-smoke-{1,2,3}.log`: three consecutive runs, 12 passes each and no FAIL.
- `atomcode-processfix-{emulator,phone}-security/foreign-uid-probe.txt`: seven passes per device; `security-owner.txt` in each directory: two passes. Owner checks also positively inspect the actual marked PRoot parent and require no `ATOMCODE_DAEMON_TOKEN=` assignment in its arguments.
- `atomcode-processfix-{emulator,phone}-storage.log`: ten passes on each device.
- `atomcode-processfix-emulator-keyboard.log`: six passes, retaining two observations of lifecycle-proved recreation without monitor delivery.

Supplementary evidence from host `4b31953b0` / APK `580c500a468055ab7d6e04efba6034cfe7cddff3b0d5421460c1e96a776e12ee` remains retained: `atomcode-recovery-final-{emulator,phone}-storage.log` each has ten passes; `atomcode-recovery-final-emulator-offline.log` has 12; `atomcode-recovery-lifecycle-keyboard-{1,2}.log` each has six with monitor observations. These cover unchanged installer/reclamation and layout code, but are not recorded as current-APK executions.

The same old APK's `atomcode-recovery-final-emulator-smoke.log` failed to confirm old-engine exit after ten passes. That failure is retained and is not counted as a 12-check pass. After adding process-identity rechecks during termination, the current APK passed the three complete online runs listed above. Earlier keyguard-related phone rendering/keyboard-initialization failures are retained too; current unlocked-phone UI evidence is still required.

Tests use actual PRoot, Linux daemon, TLS gateway, WebView, PTY and device filesystems rather than a remote engine. The 12-check smoke suite covers automatic startup/health hash, TLS/cookies, incorrect-certificate rejection, HTTP 401/foreign-Origin rejection, real commands/Git/exit status, revision-checked file edits, WebView, fullscreen, keyboard resizing, explicit stop and restart/token rotation. Offline success does not establish offline model inference.

Historical APK `a2483445cde4fc4b6171f0da15413b5d8c2239edbeeac6df1c52de2e98fcdaf5` passed 12 physical-device smoke checks and five focused layout checks. Those results **do not establish current-APK physical-device UI acceptance**.

## Installation, repair and reclamation

The current APK's ten storage checks on each device use actual bundled assets in a disposable cache root, without changing user home or model credentials. They cover:

- Installation validation; callback cancellation preserving the old pointer/generation/home and cleaning staging; injected zero-usable-space preflight; damaged-daemon detection and repair.
- Separate corruption of env, real-path bash, loader, libc and libtinfo rejected by startup validation; reinstall repairing non-daemon damage while preserving home.
- Repeated successful installs retaining active plus the previous completed pointer, reclaiming only older managed directories; observable cleanup failure leaving the committed generation valid, followed by retry after maintenance state recovery.
- Resuming a constructed partially deleted `.gc-staging` slot without `.ready`; rejecting a top-level symlink/non-directory slot and not following nested links to outside targets.
- ZIP roundtrip preserving UTF-8, empty directories and execute permission, imports creating new projects, and truncated/traversal rejection without staging residue.

Expected boot-file hashes are derived from the APK rootfs ZIP after verification of its full SHA-256 and cached by asset hash. `isCurrent()` checks these five real-path files, executable permissions and critical Linux links; permission/link requirements correspond to the pinned ARM64 Linux layout, and the daemon is checked separately. This is not integrity coverage of every Linux file. DNS refresh uses `rootfs()` without decompressing the source ZIP or walking the generation tree.

`runtime/retention` is persisted first and bound to the new active name. Reclamation is authorized only after current commits and matches that record. Retired directories are atomically moved to the reserved `.gc-staging` slot before deletion. Unknown names, invalid markers and top-level symlinks are not deleted as generations; home is outside reclamation. Previous means the previous **completed pointer**, not a freshly revalidated copy of all old bytes or a successfully tested automatic rollback.

Maintenance failure is exposed through `maintenanceWarning()` and installation progress without reporting a committed environment as a failed install; subsequent valid startup retries. Callback cancellation, zero usable space and partial GC state are controlled injections/constructions, **not physical disk exhaustion, abrupt process termination or power-loss acceptance**.

Compressed ZIP input and expanded content are each limited to 2 GB and both require temporary storage. A complete central directory and matching entry sizes/CRC are required before committing an imported project. Truncation regressions fail on the old importer and pass after the fix.

## Independent-UID probe scope

The current APK was tested with a separate-UID probe on each device: its UID differs from the owner; access to the live private Unix socket fails with `EACCES`; unauthenticated sessions access is denied; foreign Origin is rejected; forged Host is rejected; the probe cannot bind the gateway's occupied loopback port; and default platform TLS trust rejects the app's self-signed certificate. Two additional checks establish authenticated owner access to a healthy private socket before and after probing, while positively inspecting the actual runtime-parent command line for absence of a daemon-token assignment.

The probe does not obtain or print tokens, cookies or model credentials. Failure to bind an occupied port does not establish protection against the complete pre-start port-takeover/fake-listener race. These checks do not prove coverage of every malicious application, upload, external navigation or backup attack.

## Keyboard, rotation and WebView

The emulator suite uses actual composer taps, system IME visibility and WebView/keyboard geometry to check portrait input, landscape/portrait return, temporary long text, runtime identity and a workspace canary. The current APK passed all six checks; two earlier six-check runs retain their previous-APK scope. `retained before tap=false` means the keyboard was reopened by touching the composer after rotation before checking overlap; it **does not prove continuous IME visibility during recreation** or persistence of unsent drafts.

New observations prove a newly created/resumed Activity with stable requested configuration while ActivityMonitor did not deliver it. Tests now combine lifecycle evidence and stable configuration, without changing product layout or relaxing recreation, composer visibility or runtime-identity requirements. The earliest historical landscape failure lacked these observations; it cannot retrospectively be attributed to the now-observed monitor omission.

Current phone UI checks remain unpassed while locked and must be rerun after unlock. A previous emulator-only check disabled/missed the WebView provider and observed a live process, native upgrade diagnostic and no WebView node; the provider was restored. The minimum syntax target is Chromium 99, with old/unknown-version JVM coverage. Neither establishes Android 8 device support.

## Local checks and source inventory

- The current APK rechecks UID, start time and state during process termination with bounded retries. An unreadable individual `/proc` member is not treated as proof of process disappearance; unresolved identity remains fail-closed. Control credentials are passed through the allowlisted environment rather than long-lived argv. Controlled diagnostics contain only stage, exception class/errno and state booleans, not command lines, environment contents or credentials.
- Latest JVM XML totals **31 passed**, zero failures/errors/skips, including nine new process-ownership regressions. Local debug build/lint passed with four owner-app warnings and two probe-app warnings.
- This pass's formatting check exited zero; forbidden Cargo.lock packages `hkdf`, `hmac` and `zeroize_derive` each have count zero.
- Earlier runtime/source-inventory Python suite: 12 passed; related code is unchanged and this is not presented as a new execution.
- The corresponding earlier baseline has passed WebUI build/typecheck/402 tests, daemon 402 nextest tests, coding 1,245 tests with ten skipped, `bash gates/compile.sh` and formatting gates. Rust sources, Cargo manifests/lock and Rust gates are unchanged in this pass; prior evidence is reused, not presented as a new execution.
- Earlier official npm audit reduced eight reported advisories to zero; this does not prove the current absence of vulnerabilities.
- Verified-input inventory covers 117 Ubuntu binary packages from 82 source/version pairs and three Termux native components, reflecting overlays rather than stale Base dpkg status. Complete corresponding-source archives/license distribution remain incomplete.
- Node build preflight requires 22.12+. Compilation is local; source publication skips CI and excludes APKs. GitHub defaults to English and GitCode to Chinese, both retaining both languages.

## Model and platform blockers

A real-model request failed after AtomGit login. The shared provider factory returns `SourceBuildGatewayUnsupported`: public source lacks the closed-source official CodingPlan request signer. Login cannot supply this missing build capability. The user deferred compatible-provider configuration and requested an APK with model acceptance explicitly unpassed.

**Real-model streaming, model-driven approval/denial, cancellation and complete conversation resume have not passed acceptance.** Empty native-session metadata retention is not real-conversation resume. The normal catalog hides empty conversations; tests resolve native session IDs directly to inspect metadata.

An official API 36 16 KB ARM64 image is being acquired, but no installation, boot or `getconf PAGESIZE` result exists yet; downloading is not a 16 KB execution pass. ELF LOAD alignment is static evidence only. Android 8, additional vendors, IPv6/external HTTPS, sleep/network recovery and real models remain unaccepted.

## Final acceptance matrix

| Criterion | Evidence and outstanding work |
| --- | --- |
| 1 Reproducible delivery | Pinned source/lockfiles, hashes, ELF audit and source inventory exist; clean-machine rebuilding, production signing and complete corresponding-source/license delivery remain |
| 2 Installation/startup | Current APK passed three consecutive 12-check online emulator runs and ten storage checks on each device; older offline evidence is retained separately; physical exhaustion, interrupted upgrades/abrupt kills and current phone UI remain |
| 3 Local execution | Current actual PRoot/daemon, PTY/Git/files are evidenced; prior emulator offline results remain retained, while current-APK offline and phone offline suites have not been rerun |
| 4 Authentication/security | Certificate pin, cookies/401/cross-origin and independent-UID 7+2 checks are evidenced; pre-start interception/fake services, upload, backups and comprehensive attack coverage remain |
| 5 Core workflow | Local terminal/file operations passed; real models and model-driven tools/cancellation remain unpassed |
| 6 Sessions/data | Current emulator workspace/empty native metadata survive restart; current canceled-repair, corruption-repair and archive APIs passed on both devices; **real-conversation resume has never passed**; hard interruption, system file-picker interaction and forced-kill recovery remain |
| 7 Mobile usability | Current emulator smoke fullscreen/keyboard and six focused keyboard/rotation checks passed; unlocked-phone UI remains missing and old-APK results cannot replace it; complete input/draft/code/file-picker coverage remains |
| 8 Lifecycle | Old-APK online stop failure is retained; new identity rechecks have nine JVM regressions and three complete current-emulator start-stop passes; long-background, forced-stop and vendor checks remain |
| 9 Platform/network | Runtime evidence covers Android 14 hardware/Android 16 emulator, both 4 KB; downloading a 16 KB image is not execution acceptance, and Android 8/more vendors/full network matrix remain |
| 10 Evidence | Current hashes/source and pass/failure/blocker records are separated; complete final acceptance cannot be declared |
