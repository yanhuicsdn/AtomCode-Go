# Real low-space recovery and source acquisition

Starting state: managed worktree `codex/android-delivery-audit`, clean tracked source `269462aacbdc067e884449dc96634ace294a70ae`. Existing APK `28b2a612...` remains the product artifact, built at `2cebadbc0`. The delivered evidence has been copied into this active writable worktree; the original checkout is left unchanged.

The preceding goal turn completed the fixed-source local build, a same-host repeat with equal daemon/rootfs/APK hashes, real pre-commit upgrade force-stop/recovery, and current-artifact native device checks. Final acceptance is still incomplete.

1. Add an emulator-only real low-space test using an isolated application-files fixture and a real `File`. It must consume real filesystem blocks, retain a safe free-space reserve, bound filesystem size/filler/time, fail closed outside the owned emulator, and always remove only its own fixture/filler. The production installer remains the owner of installation, pointer commits and recovery. Its actual insufficient-space rejection must preserve the old fixture state; freeing the filler must allow a successful retry.
2. Audit missing per-package notice texts against original package/version/VCS metadata. Shared workspace MIT and independently missing third-party texts are different evidence. Never substitute SPDX or generic license templates for original notices.
3. Acquire a pilot set of official exact-version source descriptors and archives with recorded size/hash/provenance. Do not call landing-page URLs or a partial archive set complete corresponding-source delivery. Hash consistency and signature authentication must be reported separately.
4. Run appropriate host/device checks locally, preserve previous failures and artifacts, update bilingual instructions/evidence and synchronize authorized source repositories with CI skipped.

No phone low-space tests, data clears, model requests, guessed signing implementation, Rust runtime/session changes, profile changes or cloud builds are part of this work. Model and other unpassed device/workflow criteria stay open.
