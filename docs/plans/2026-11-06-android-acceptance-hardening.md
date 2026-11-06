# AtomCode-Go acceptance hardening

Baseline: `codex/android-local-runtime`, `1ae8d5266`; clean worktree before this pass. Previous goal turn made progress: delivered signed debug APK, bilingual source publication, and physical/emulator evidence. Final acceptance remains incomplete; real-model testing is deferred by the user.

This pass keeps all agent/session/runtime ownership unchanged. Android continues to own installation, process lifecycle and presentation. Changes address verified host boundaries:

1. Reject truncated or corrupt ZIP transfers before committing a new imported project. Bound compressed and expanded bytes; clean temporary state on failure; preserve existing files.
2. Require Node 22.12+ before installing build tools, matching the locked Vite/Rolldown requirements; update both languages without changing dependency resolution.
3. Diagnose missing/old WebView (Chrome 99 minimum matches Vite target) instead of presenting a blank page. Modern WebView behavior, token exchange and origin pinning stay on the existing path.
4. Run device storage checks against actual bundled runtime assets in a temporary Context-owned directory: successful installation, canceled repair preserving the old generation, insufficient-space preflight, installed-daemon corruption detection/repair, and archive roundtrip/rejection. A callback cancellation is not evidence of an abrupt process kill or physical ENOSPC; record those distinctions.

Verification: relevant Android JVM tests, lint, debug build and emulator smoke/storage checks. Keep physical app data and model credentials intact. Retain artifact/source hashes and exact evidence; do not claim real-model, Android 8, additional-vendor or 16 KB execution acceptance from these checks. All compilation is local. Any subsequent source publication must skip CI and preserve English GitHub / Chinese GitCode defaults.
