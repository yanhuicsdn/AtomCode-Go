# AtomCode-Go verification and acceptance record

Date: 2026-11-07. Status: development delivery; **final acceptance remains incomplete**. This record separates current-APK evidence, historical artifacts, injected faults and external blockers. Narrow checks do not establish complete acceptance.

## Current artifact and source

AtomCode-Go 0.1.0, ARM64, Android debug signing. Current local APK SHA-256:

```text
28b2a6127be19aba8315d6b377428a3a1166f9ad4d4b28acb79fc5b45411d6e0
```

- Android host, bundled engine/WebUI and payload source: `2cebadbc019c5c0c6a547983cbcbd9da7151f30d`. Rust and payload were rebuilt; the old `a05c031ef`/`atomcode-752a620892e947e2` payload is not reused.
- The host-side upgrade coordinator's exact-byte canary transport fix is `0f4d412ac`; it does not change this APK's compiled source.
- Layout observation logic originates at `a6f9c2454553e03627c643a34e405dce84c7aa25`. That change improves observation of recreation without changing product layout.
- Runtime: `atomcode-7477b13242308dfe`. Daemon SHA-256: `26b74a624f74ec537d98a749672c50141f5d222cca060d2ba1a27237f4fc46c5`.
- Debug certificate SHA-256: `82923019b3fc796d3ed78d14561ab1d8ae966986e40a1936076ac6d795b02d41`. Private keys are excluded from source and delivery documentation.

Local `delivery.json`, `SHA256SUMS`, `build-report.json` and device evidence identify exact artifacts, test sources and dependency inputs.

## Local build and controlled repeat

`atomcode-fixed-build-inputs.json`, `atomcode-fixed-build-results.json` and `atomcode-fixed-full-build.log` record the complete local driver at `2cebadbc0`: an initially absent external Cargo target and runtime output, retained verified dependency caches/toolchain, the actual configured target selected instead of an older default-directory daemon, 402 WebUI tests, 19 Python tests and 31 JVM tests passed, and all 75 Gradle tasks executed with zero cache hits. The full driver exited zero.

`atomcode-repro-repeat-results.json` and `atomcode-repro-repeat-{daemon,payload,apk}.log` record a second build on the same host/toolchain and commit with `SOURCE_DATE_EPOCH=1793982551`. Changing WebUI filesystem timestamps caused an actual daemon recompile; the payload was regenerated and APK assembly ran clean with caching disabled (36 tasks executed). Daemon and APK hashes matched the current artifact above; both rootfs ZIP hashes were `fcb79df63b43c107a8a90ecb9d5c0d7193f63871ff91bc413878e72fac247566`, and manifests matched. These are byte-identical results for this controlled same-host repeat, not cross-host or fresh-machine acceptance.

The earlier isolated-source/empty-output `9ba42f01d` baseline also completed on this host. Its first Ubuntu download was rejected for a hash mismatch; a fresh download matching the original committed pin allowed completion without changing the pin. That earlier baseline alone was not a byte-identical comparison.

## Current device evidence

| Environment | System / ABI / page size | Result for the current APK |
| --- | --- | --- |
| Physical Up Mobile Mover Edition | Android 14 / API 34, ARM64, measured 4096 bytes | Current APK passed same-key in-place upgrade, seven independent-UID probes, two owner checks and ten isolated cache-storage checks. No UI suite was run; current phone UI and real models remain unpassed |
| sdk_gphone64_arm64 emulator | Android 16 / API 36, ARM64, 4096 bytes | The emulator runs listed below belong to historical APK `15dd396b`; no current-APK 4 KB emulator execution is claimed |
| Official-image AtomCode_API36_16K_Arm64 emulator | Android 16 / API 36, ARM64, measured runtime page size 16384 bytes | Current APK passed actual pre-commit upgrade force-stop/recovery, the following 12 smoke checks, seven independent-UID probes, two owner checks and five real low-space fixture checks; the separate ten-check cache-storage suite, focused rotation and real models are not claimed |

Evidence for the current `28b2a612` APK:

- `atomcode-upgrade-fixed/upgrade-interruption.json`: an in-place upgrade from runtime `atomcode-752a620892e947e2` to `atomcode-7477b13242308dfe`, actual `am force-stop` during observed extraction before commit, unchanged old pointer/ready/daemon and 84-byte home canary after the process exited, then automatic recovery to the new bundled runtime with matching ready/daemon, retained old generation/canary, cleared staging and matching retention record.
- `atomcode-upgrade-fixed/smoke.txt`: 12 passes after recovery, with no FAIL. `cleanup.json`: recovery pointer and staging absence verified, coordinator restored its recorded IME value `1`, emulator shut down normally and AVD retained. The outer pre-upgrade IME initial value was not recorded and is not inferred. The upgrade JSON records API 36, `arm64-v8a`, measured page size `16384`.
- `atomcode-fixed-security/{foreign-uid-probe.txt,security-owner.txt,security-probe-summary.json}`: seven foreign-UID checks and two owner checks passed. `environment.json` verifies installed APK hash `28b2a612...`, the matching test APK, AVD and measured page size `16384`. `cleanup.json` records normal shutdown, AVD retained and IME value `1` before/after with no preference write in this run.
- `atomcode-fixed-phone-{preflight,install,summary}.json`: installed APK hash matches `28b2a612...`; API 34, `arm64-v8a`, measured page size `4096`; same-key `-r` install retained app data and runtime changed from `atomcode-752a620892e947e2` to `atomcode-7477b13242308dfe`, matching the final APK. `atomcode-fixed-phone-security/` records seven foreign-UID and two owner passes; `atomcode-fixed-phone-storage.log` records ten passes. Install and test coordinators exited zero. No uninstall/data clear, UI suite, screen/IME change, credential read or model request was performed; the phone remains connected.
- `atomcode-real-space-final/{real-space-summary.json,device-test.txt,launch.json,backing.json,install.json,cleanup.json}`: current app `28b2a612...` with separately built test APK `9e1ddc61...` passed five real low-space checks on the owned 16 KB emulator; exact pressure, preservation, release/reinstall and cleanup measurements appear below.
- These files are local evidence under `/tmp/`. This is one observed pre-commit extraction window; it does not establish arbitrary kill points, interrupted GC, power loss or complete upgrade acceptance.

## Historical artifact evidence

APK `15dd396bdab988ccffa1998badade33376ea3c5041c301d04a1890659f2b7339` used Android host `ce6303f66` with engine/WebUI `a05c031ef` and runtime `atomcode-752a620892e947e2`. The following remain historical executions rather than current-APK reruns:

- `atomcode-processfix-emulator-smoke-{1,2,3}.log`: three consecutive runs, 12 passes each and no FAIL.
- `atomcode-processfix-{emulator,phone}-security/foreign-uid-probe.txt`: seven passes per device; `security-owner.txt` in each directory: two passes. Owner checks also positively inspect the actual marked PRoot parent and require no `ATOMCODE_DAEMON_TOKEN=` assignment in its arguments.
- `atomcode-processfix-{emulator,phone}-storage.log`: ten passes on each device.
- `atomcode-processfix-emulator-keyboard.log`: six passes, retaining two observations of lifecycle-proved recreation without monitor delivery.
- `atomcode-16k-environment.json`: `emulator-5562` / `AtomCode_API36_16K_Arm64` completed boot, API 36, `arm64-v8a`, measured page size `16384`; image package `system-images;android-36;google_apis_ps16k;arm64-v8a`, revision 7, emulator 36.1.9.
- `atomcode-16k-install.json`: the historical owner APK, instrumentation APK and separate-UID probe APK all installed with exit code zero; `atomcode-16k-smoke.log`: that APK passed 12 checks without FAIL. Installing a probe APK alone is not proof that security probing passed.
- `atomcode-16k-security/foreign-uid-probe.txt`: seven passes; `security-owner.txt` in that directory: two passes. `security-probe-summary.json` identifies `emulator-5562`, the 7/2 counts, no credentials provided to the probe and the bounded probe scope; coordinator exit code was zero.

Supplementary evidence from host `4b31953b0` / APK `580c500a468055ab7d6e04efba6034cfe7cddff3b0d5421460c1e96a776e12ee` remains retained: `atomcode-recovery-final-{emulator,phone}-storage.log` each has ten passes; `atomcode-recovery-final-emulator-offline.log` has 12; `atomcode-recovery-lifecycle-keyboard-{1,2}.log` each has six with monitor observations. These cover unchanged installer/reclamation and layout code, but are not recorded as current-APK executions.

The same old APK's `atomcode-recovery-final-emulator-smoke.log` failed to confirm old-engine exit after ten passes. That failure is retained and is not counted as a 12-check pass. After adding process-identity rechecks during termination, historical APK `15dd396b` passed the three complete online runs listed above. Earlier keyguard-related phone rendering/keyboard-initialization failures are retained too; current unlocked-phone UI evidence is still required.

Tests use actual PRoot, Linux daemon, TLS gateway, WebView, PTY and device filesystems rather than a remote engine. The 12-check smoke suite covers automatic startup/health hash, TLS/cookies, incorrect-certificate rejection, HTTP 401/foreign-Origin rejection, real commands/Git/exit status, revision-checked file edits, WebView, fullscreen, keyboard resizing, explicit stop and restart/token rotation. Offline success does not establish offline model inference.

Historical APK `a2483445cde4fc4b6171f0da15413b5d8c2239edbeeac6df1c52de2e98fcdaf5` passed 12 physical-device smoke checks and five focused layout checks. Those results **do not establish current-APK physical-device UI acceptance**.

## Installation, repair and reclamation

The current APK passed ten isolated storage checks on the 4 KB physical phone using the new bundled payload under a disposable app-cache root, excluding real user home and model credentials. Historical APK `15dd396b` passed the same ten checks on both 4 KB devices; that ten-check cache suite has not been rerun on an emulator for the current APK. The separate five-check real-space result below does not replace it. The ten checks cover:

- Installation validation; callback cancellation preserving the old pointer/generation/home and cleaning staging; injected zero-usable-space preflight; damaged-daemon detection and repair.
- Separate corruption of env, real-path bash, loader, libc and libtinfo rejected by startup validation; reinstall repairing non-daemon damage while preserving home.
- Repeated successful installs retaining active plus the previous completed pointer, reclaiming only older managed directories; observable cleanup failure leaving the committed generation valid, followed by retry after maintenance state recovery.
- Resuming a constructed partially deleted `.gc-staging` slot without `.ready`; rejecting a top-level symlink/non-directory slot and not following nested links to outside targets.
- ZIP roundtrip preserving UTF-8, empty directories and execute permission, imports creating new projects, and truncated/traversal rejection without staging residue.

Expected boot-file hashes are derived from the APK rootfs ZIP after verification of its full SHA-256 and cached by asset hash. `isCurrent()` checks these five real-path files, executable permissions and critical Linux links; permission/link requirements correspond to the pinned ARM64 Linux layout, and the daemon is checked separately. This is not integrity coverage of every Linux file. DNS refresh uses `rootfs()` without decompressing the source ZIP or walking the generation tree.

`runtime/retention` is persisted first and bound to the new active name. Reclamation is authorized only after current commits and matches that record. Retired directories are atomically moved to the reserved `.gc-staging` slot before deletion. Unknown names, invalid markers and top-level symlinks are not deleted as generations; home is outside reclamation. Previous means the previous **completed pointer**, not a freshly revalidated copy of all old bytes or a successfully tested automatic rollback.

Maintenance failure is exposed through `maintenanceWarning()` and installation progress without reporting a committed environment as a failed install; subsequent valid startup retries. Callback cancellation, zero usable space and partial GC state are controlled injections/constructions, **not physical disk exhaustion, actual interrupted GC or power-loss acceptance**. The new current-APK force-stop test above adds actual process termination only during pre-commit extraction.

### Real low-space preflight

Tool/test source `c5700c128`, current app `28b2a612...` and new test APK SHA-256 `9e1ddc61153500744a7f9e85ee531a99907ac5ea01320d39081cb1aceff67115` passed **5/5 once**, only on `AtomCode_API36_16K_Arm64`. A unique fixture under app **files** used an ordinary `File.getUsableSpace()` on the actual 6,228,115,456-byte data filesystem; neither a cache fixture nor an overridden space value was used. The default launch had no `-data`/`-datadir` override. `.ini`, generated qemu configuration and active userdata file descriptors agreed on the default directory and checked host filesystem; actual page size was `16384`.

After a real bundled install seeded the fixture pointer, ready marker, daemon and home canary, sequential synced writes produced **4,196,466,128 bytes**, with **4,196,474,880 allocated bytes**, in 7,158 ms. Actual available space reached **318,488,576 < required 335,266,352 bytes**, still above the **256 MiB** reserve. The actual installer reported insufficient space with **zero extraction callbacks**; the old fixture pointer/ready/daemon/canary remained unchanged. Deleting filler restored 4,514,959,360 usable bytes, then real reinstallation succeeded and preserved the canary. Limits were not relaxed: filesystem at most 16 GiB, filler at most 6 GiB/120 seconds, initial host headroom at least 8 GiB, device/host deadlines 240/360 seconds. [The build guide](BUILD.en.md#real-low-space-preflight-owned-emulator-only) gives the explicit-confirmation command; the host refuses phones and locally rejects old test APKs that would otherwise run the default suite.

The filler and exact fixture were removed. Guest available space returned from 4,758,884,352 before the fixture to 4,758,827,008 bytes afterward, a 57,344-byte difference consistent with normal system activity. IME stayed `1`; the emulator shut down normally and its AVD was retained. Host qcow2 allocation remained grown: host free space changed from 68,650,450,944 to 64,180,596,736 bytes, so full host-space reclamation is **not** claimed. The phone, production APK, real user home, UI and models were untouched. This proves a real low-space **preflight** in an isolated fixture, not physical NAND/zero-space exhaustion, mid-extraction ENOSPC or real-user-home recovery.

The first `atomcode-upgrade-baseline/upgrade-interruption.json` stopped during canary preparation with `digest-source-not-regular-file` after a zero-byte canary, before installing/upgrading or reaching a force-stop window; it is not upgrade-failure evidence. Coordinator fix `0f4d412ac` uses shell-v2 without a PTY and verifies exact canary bytes; the successful fixed run retained 84 bytes. The earlier failure does not establish that `adb exec-in` must always fail.

Compressed ZIP input and expanded content are each limited to 2 GB and both require temporary storage. A complete central directory and matching entry sizes/CRC are required before committing an imported project. Truncation regressions fail on the old importer and pass after the fix.

## Independent-UID probe scope

The current APK passed seven separate-UID checks on both the measured 16 KB emulator and 4 KB physical phone: the probe UID differs from the owner; access to the live private Unix socket fails with `EACCES`; unauthenticated sessions access is denied; foreign Origin is rejected; forged Host is rejected; the probe cannot bind the gateway's occupied loopback port; and default platform TLS trust rejects the app's self-signed certificate. Two additional checks per device establish authenticated owner access to a healthy private socket before and after probing. Historical APK `15dd396b` passed the same 7+2 scope on three configurations (4 KB phone, 4 KB emulator and this 16 KB emulator), also positively inspecting the actual runtime-parent command line for absence of a daemon-token assignment; those executions remain historical.

The probe does not obtain or print tokens, cookies or model credentials. Failure to bind an occupied port does not establish protection against the complete pre-start port-takeover/fake-listener race. These checks do not prove coverage of every malicious application, upload, external navigation or backup attack.

## Keyboard, rotation and WebView

The emulator suite uses actual composer taps, system IME visibility and WebView/keyboard geometry to check portrait input, landscape/portrait return, temporary long text, runtime identity and a workspace canary. Historical APK `15dd396b` passed all six checks; the new APK has smoke keyboard-resizing coverage but no focused rotation rerun. Two earlier six-check runs retain their earlier-APK scope. `retained before tap=false` means the keyboard was reopened by touching the composer after rotation before checking overlap; it **does not prove continuous IME visibility during recreation** or persistence of unsent drafts.

New observations prove a newly created/resumed Activity with stable requested configuration while ActivityMonitor did not deliver it. Tests now combine lifecycle evidence and stable configuration, without changing product layout or relaxing recreation, composer visibility or runtime-identity requirements. The earliest historical landscape failure lacked these observations; it cannot retrospectively be attributed to the now-observed monitor omission.

Current phone UI checks remain unpassed while locked and must be rerun after unlock. A previous emulator-only check disabled/missed the WebView provider and observed a live process, native upgrade diagnostic and no WebView node; the provider was restored. The minimum syntax target is Chromium 99, with old/unknown-version JVM coverage. Neither establishes Android 8 device support.

## Local checks and source inventory

- The current APK rechecks UID, start time and state during process termination with bounded retries. An unreadable individual `/proc` member is not treated as proof of process disappearance; unresolved identity remains fail-closed. Control credentials are passed through the allowlisted environment rather than long-lived argv. Controlled diagnostics contain only stage, exception class/errno and state booleans, not command lines, environment contents or credentials.
- The full local driver passed WebUI build/typecheck/402 tests, Python 19 tests and JVM **31 tests**, zero JVM failures, including nine process-ownership regressions. Debug build/lint completed; the probe APK's earlier lint record is historical rather than a new driver execution.
- This pass's formatting check exited zero; forbidden Cargo.lock packages `hkdf`, `hmac` and `zeroize_derive` each have count zero.
- `atomcode-repro-daemon-no-proxy-tests.log`: all 402 daemon nextest tests passed with `NO_PROXY=localhost,127.0.0.1,::1`. The initial host-proxy run in `atomcode-repro-daemon-tests.log` remains retained: one local Ollama refusal diagnostic received proxy HTTP 502 and failed, with 347 tests not run. The passing rerun excluded loopback from that host proxy.
- `bash gates/compile.sh` passed (`atomcode-repro-compile-gate.log`), as did the formatting check. Rust sources have not changed since `9b7299d2a`; earlier coding 1,245 passed/ten skipped remains earlier evidence, not a new test run.
- Earlier official npm audit reduced eight reported advisories to zero; this does not prove the current absence of vulnerabilities.
- Verified-input inventory covers 117 Ubuntu binary packages from 82 source/version pairs and three Termux native components, reflecting overlays rather than stale Base dpkg status. The APK now also contains `assets/runtime/licenses/dependencies/index.json` and 327 byte-preserved unique texts for a selection of 290 Cargo and 165 installed npm packages, including development fonts. Cargo normal/build reachability uses a workspace feature-unified superset; installed npm entries do not prove final bundle membership. Twenty Cargo and six npm packages lack a per-package notice text; shared repository MIT is separately indexed and does not erase those entries. Complete corresponding-source archives remain undelivered; SPDX identifiers and source URLs are not full texts or corresponding source.
- Node build preflight requires 22.12+. Compilation is local; source publication skips CI and excludes APKs. GitHub defaults to English and GitCode to Chinese, both retaining both languages.
- The frontend lock now pins all 178 independent artifacts, retaining one bundled child under its parent's pin. Official exact-version metadata confirmed existing hashes; 138 missing URL/SHA-512 pairs were added without version/dependency changes. WebUI build/typecheck/402 tests passed, and all 94 generated file hashes matched the preceding outputs. Eight offline preflight tests and rejection of the real older lock passed before network/build actions. This did not replace the `28b2a612` APK.
- Current host tooling tests total 43 passes (including source-subset, supplemental-notice and lock-preflight checks); the current compile gate passed. [Source materials](SOURCES.en.md) records two of 82 Ubuntu source/version groups actually acquired (six files, 13,878,273 bytes), with checksum consistency but no independent OpenPGP authentication, plus ten version-associated supplemental original notices and three unresolved external packages. The thirteen workspace references use shared repository MIT. The APK's original 327-text index is unchanged and complete corresponding-source delivery remains open.

## Model and platform blockers

A real-model request failed after AtomGit login. The shared provider factory returns `SourceBuildGatewayUnsupported`: public source lacks the closed-source official CodingPlan request signer. Login cannot supply this missing build capability. The user deferred compatible-provider configuration and requested an APK with model acceptance explicitly unpassed.

**Real-model streaming, model-driven approval/denial, cancellation and complete conversation resume have not passed acceptance.** Empty native-session metadata retention is not real-conversation resume. The normal catalog hides empty conversations; tests resolve native session IDs directly to inspect metadata.

Official API 36 Google APIs 16 KB ARM64 image revision 7 has installed and booted, with measured runtime page size `16384` bytes; the current APK passed actual pre-commit force-stop/recovery, 12 local smoke checks, independent-UID 7+2 checks and five isolated real low-space preflight/recovery checks on it. This is execution evidence for one specific 16 KB emulator configuration, rather than ELF LOAD alignment alone. It does not establish 16 KB physical devices, other 16 KB images, the separate ten-check cache-storage suite, focused rotation or real-model acceptance. Android 8, additional vendors, IPv6/external HTTPS, sleep/network recovery and real models remain unaccepted.

## Final acceptance matrix

| Criterion | Evidence and outstanding work |
| --- | --- |
| 1 Reproducible delivery | Full empty-external-target local build and a controlled same-host fixed-epoch repeat produced identical daemon/rootfs/APK; cross-host/fresh-machine rebuilding, production signing and complete corresponding-source/license delivery remain |
| 2 Installation/startup | Current 16 KB APK passed actual pre-commit extraction force-stop/recovery, 12 smoke checks and five real low-space fixture preflight/release/reinstall checks; current 4 KB phone passed same-key upgrade and ten isolated storage checks. Historical runs stay separate. Physical exhaustion, mid-install ENOSPC, other interruption windows, power loss and current phone UI remain |
| 3 Local execution | Current actual 16 KB PRoot/daemon, PTY/Git/files passed; the current phone runtime upgraded and passed native health/storage checks. Full 4 KB emulator, offline and phone UI suites remain historical or unrun for this APK |
| 4 Authentication/security | Current smoke passed certificate pin/cookies/401/cross-origin checks, plus independent-UID 7+2 on current 16 KB emulator and 4 KB phone; three-configuration `15dd396b` results remain historical. Pre-start fake services, upload, backups and comprehensive attack coverage remain |
| 5 Core workflow | Local terminal/file operations passed; real models and model-driven tools/cancellation remain unpassed |
| 6 Sessions/data | Current 16 KB upgrade preserved home canary/old generation through a real pre-commit kill and recovered; smoke preserved workspace across restart. Current phone isolated cancel/repair/archive checks passed, excluding real home; **real-conversation resume has never passed**; arbitrary interruptions and system file-picker interaction remain |
| 7 Mobile usability | Current 16 KB smoke fullscreen/keyboard resizing passed; six focused rotation checks are historical. Current unlocked-phone UI, complete input/draft/code/file-picker coverage remain |
| 8 Lifecycle | Current pre-commit force-stop recovery and smoke explicit stop/restart passed; nine JVM process-ownership regressions passed. Historical stop failure is retained; long-background, other forced-stop scenarios and vendors remain |
| 9 Platform/network | Current APK has one measured 16 KB API 36 ARM64 configuration's recovery/12-check smoke/7+2/real-space five-check evidence and API 34 ARM64/4 KB phone native 7+2/storage ten checks; 4 KB emulator executions remain historical. Current phone UI, Android 8, more vendors, all 16 KB devices/other focused checks and the full network matrix remain |
| 10 Evidence | Current hashes/source and pass/failure/blocker records are separated; complete final acceptance cannot be declared |
