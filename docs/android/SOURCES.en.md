# AtomCode-Go source and notice materials

The verified APK remains `28b2a612…` from source `2cebadbc0`. Its original 327-text dependency-notice index is unchanged. The source/notice materials below are separate additions; they have not rebuilt or replaced that APK. Runtime, device UI and real-model limits remain in [VERIFICATION.en.md](VERIFICATION.en.md).

## Supplemental original notices

[Supplemental manifest](../../android/runtime/supplemental-notices/manifest.json) records 13 external package versions: ten `resolved`, three `unresolved`. Here `resolved` means a version-associated original notice text was obtained; it does not certify the complete license set, legal compliance or complete corresponding source. Nine unique original texts and the supporting metadata are preserved without rewriting their bytes.

The manifest links artifact versions/hashes to fixed upstream commits, original paths, request/final URLs and text hashes. It keeps npm alias names distinct from lock paths and records `napi-wasm` as bundled inside its `lightningcss-wasm` parent. `boolbase`, `dlv` and `napi-wasm` remain unresolved; SPDX declarations or generic MIT/ISC templates do not substitute for missing original texts. Thirteen workspace packages reference the existing [repository MIT license](../../LICENSE), rather than claiming thirteen independent notice files.

Run this check offline from the repository root:

```sh
python3 android/runtime/verify_supplemental_notices.py
```

The check verifies supplied bytes and recorded lock/metadata/source relationships. It does not rehash binary archives absent from the supplement or authenticate publisher signatures. This supplement is not connected to the APK packaging pipeline.

## Exact-version Ubuntu source pilot

[source_archives.py](../../android/runtime/source_archives.py) has two operations. `plan` pins an already acquired `.dsc` offline, checks its Source/Version against `source-inventory.json`, and records archive sizes/SHA-256. `fetch` accepts one pinned manifest, downloads or verifies its descriptor and source archives, and never extracts or executes them.

| Source package | Exact inventory version | Pinned manifest |
| --- | --- | --- |
| Git | `1:2.43.0-1ubuntu7.4` | [git.json](../../android/runtime/source-manifests/git.json) |
| Bash | `5.2.21-2ubuntu4` | [bash.json](../../android/runtime/source-manifests/bash.json) |

Both subsets were acquired: one `.dsc`, one upstream `orig` archive and one Debian packaging archive per package, six files totaling **13,878,273 bytes**. Receipts are under `dist/android/source-archives/`. To acquire or recheck one subset from the repository root:

```sh
python3 android/runtime/source_archives.py fetch \
  --manifest android/runtime/source-manifests/git.json \
  --out dist/android/source-archives
```

For Bash, run the same command with only `--manifest android/runtime/source-manifests/bash.json`. `plan` also accepts `--version` to select an exact inventory version. This matters for the current OpenSSL entries, `3.0.13-0ubuntu3.15` and `3.0.13-0ubuntu3.16`. Receipts use `<package>-<sha256(version)>-source-receipt.json`, so versions do not overwrite one another; neither OpenSSL subset has been acquired by this pilot.

Recorded archive sizes and SHA-256 match the pinned descriptors. A PGP signature envelope is present, but `signatureVerified` remains **false**: checksum consistency and independent OpenPGP authentication are separate results. These two source/version pairs cover **2 of 82** Ubuntu inventory pairs, not complete corresponding-source delivery; `completeCorrespondingSourceDelivered` remains false.

## npm artifact pins

The WebUI lock now supplies `resolved` and SRI for 138 previously unpinned independent entries. All 40 existing SRI values matched official `registry.npmjs.org` exact-version metadata. Eight existing mirror URLs remain, with hashes equal to the official artifacts. The lock describes 178 independent artifacts plus one bundled child acquired through its parent. Versions and dependency relationships were retained.

Current `webui/package-lock.json` SHA-256:

```text
9f77410695db6bf9bc52b2a74d17c96e389fb5bbe8b1298109472f20a6523db1
```

After the pin update, WebUI build/typecheck and 402 tests passed; all 94 generated files had the same SHA-256 as before. This is WebUI output comparison, not a newly compiled APK. The offline `python3 android/runtime/verify_frontend_lock.py` preflight now accepts 178 pinned entries plus one bundled child. `build-local.sh` runs it before downloads or compilation. The real older `2ce` lock was rejected; a driver fixture confirmed that rejection created no venv and executed no build tools. Eight checker tests passed. These artifact pins and the source pilot do not change model availability or establish complete product source delivery.
