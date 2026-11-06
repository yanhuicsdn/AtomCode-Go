# Clean-source Android rebuild and distribution evidence

Baseline: `codex/android-local-runtime`, `9ba42f01d9d4dc7782ad2317da5cae9e1d33ccd8`, clean worktree. The delivered `15dd396b...` APK is retained. The isolated Codex worktree starts from that commit without generated frontend, Rust target, payload, Python environment or Gradle outputs. Compilation remains local; a second physical host is not implied.

The build driver owns selection of the daemon artifact. Cargo's configured target directory must be the source of that path; failures must stop packaging rather than selecting a default-directory leftover. The daemon build watches the WebUI directory and embedded resource epoch. The Android host and coding runtime/session ownership do not change.

1. Finish the unmodified full local build as a baseline and record its inputs, outputs and failures.
2. Fix configured-target selection and deterministic embedded timestamps, including Cargo invalidation when those inputs change. Run the relevant real build and existing daemon/compile gates.
3. Collect missing dependency license texts from verified local package inputs; distinguish notice coverage from complete corresponding-source delivery.
4. Install a newly built artifact on an owned emulator and verify real local execution. If feasible, interrupt actual runtime upgrade extraction with process force-stop and verify pointer/home preservation and retry.
5. Publish bilingual instructions and exact evidence without cloud builds. Preserve the previous delivery and explicitly keep unpassed model/device/distribution criteria open.

Do not clear phone data, reuse compiled artifacts as evidence of a cold build, claim byte-identical reproduction without comparison, or call callback cancellation an actual process kill.
