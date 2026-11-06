# AtomCode-Go handoff

Engine baseline: AtomCode `c4a2fbfceb5ee99907d875d3b6ec604a495cc219`. Reference runtime approach: dsh-Go `cf2f7c861e1098661a3a2039b3a4cb1633790102`. The Android host is implemented in `android/`.

The implementation includes local builds, pinned runtime inputs, verified installation, a separate user home, foreground-service process ownership, a private Unix socket/TLS gateway, WebView and workspace ZIP transfer. The daemon retains the existing coding runtime and session store. The product name is **AtomCode-Go**; opening it starts the local environment automatically and the ready workspace occupies the full screen.

All compilation and tests run locally. GitHub defaults to the English README and GitCode to Chinese; both carry both languages. APKs, keys, credentials, toolchains and generated payloads are excluded from Git.

Read [Verification](VERIFICATION.en.md) for exact artifacts, executed checks and unpassed conditions. Android 14 physical-device and Android 16 ARM64 emulator checks establish the documented local-runtime workflows, not the entire acceptance matrix.

Official CodingPlan needs an unpublished request signer. AtomGit login does not enable official models in this source build. Configure a supported compatible model service for real-model acceptance. Do not create placeholder signatures, bypass authentication or silently fall back.

Before continuing, read [Goal and acceptance](GOAL-AND-ACCEPTANCE.en.md), [Local build](BUILD.en.md) and repository `AGENTS.md`. Preserve the user's home and model configuration; never clear the physical phone's app data. Real-model workflows, Android 8, additional vendors, 16 KB pages, network/lifecycle/upgrade fault scenarios and production distribution evidence remain required before final acceptance.
