#!/usr/bin/env bash
# All compilation runs on this machine. No GitHub Actions or remote builders.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"

for tool in node npm cargo rustc python3 java git; do
    command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done
if ! node -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit(major > 22 || (major === 22 && minor >= 12) ? 0 : 1)'; then
    echo 'Node.js 22.12.0 or newer is required by the locked Vite/Rolldown build tools.' >&2
    exit 1
fi
if [[ "$(rustc --version)" != rustc\ 1.95.0\ * ]]; then
    echo 'This runtime is pinned to Rust 1.95.0. Select that toolchain before building.' >&2
    exit 1
fi
if [[ -z "${SOURCE_DATE_EPOCH+x}" ]]; then
    SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"
fi
# rust-embed parses this value as u64. Reject malformed or overflowing input
# instead of letting the macro silently fall back to filesystem timestamps.
if ! python3 - "$SOURCE_DATE_EPOCH" <<'PY'
import re, sys
value = sys.argv[1]
normalized = value.lstrip('0') or '0'
valid = re.fullmatch(r'[0-9]+', value) is not None and len(normalized) <= 20
sys.exit(0 if valid and int(normalized) <= 18446744073709551615 else 1)
PY
then
    echo 'SOURCE_DATE_EPOCH must be a non-negative integer within the unsigned 64-bit range.' >&2
    exit 1
fi
export SOURCE_DATE_EPOCH
if [[ ! -x .tools/python/bin/python ]]; then python3 -m venv .tools/python; fi
.tools/python/bin/python -m pip install -r android/runtime/requirements.txt
export PATH="$ROOT/.tools/python/bin:$PATH"

(cd webui && npm ci && npm run build && npm run typecheck && npm test)
rustup target add aarch64-unknown-linux-musl
runtime_target_dir="$(cargo metadata --no-deps --locked --format-version 1 | python -c 'import json, sys; print(json.load(sys.stdin)["target_directory"])')"
cargo zigbuild --locked --release -p atomcode-daemon --target aarch64-unknown-linux-musl
python android/runtime/build.py --daemon "$runtime_target_dir/aarch64-unknown-linux-musl/release/atomcode-daemon"
python android/runtime/source_inventory.py
python -m unittest discover -s android/runtime/tests -v

# JAVA_HOME must select JDK 17+; ANDROID_HOME/ANDROID_SDK_ROOT must identify
# the user's local SDK (compile platform 36 and build-tools 36.0.0+).
(cd android && ./gradlew :app:clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain)
mkdir -p dist/android
cp android/app/build/outputs/apk/debug/app-debug.apk dist/android/atomcode-go-arm64-debug.apk
cp android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk dist/android/atomcode-go-arm64-smoke-tests.apk
cp android/runtime/out/arm64-v8a/build-report.json dist/android/build-report.json
cp android/runtime/dependencies.json dist/android/dependencies.json
cp android/runtime/out/arm64-v8a/source-inventory.json dist/android/source-inventory.json
python - <<'PY'
import hashlib, pathlib
root = pathlib.Path('dist/android')
with (root / 'SHA256SUMS').open('w') as output:
    for path in sorted(root.glob('*.apk')):
        output.write(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n')
print('Local debug APK and test APK: ' + str(root.resolve()))
PY
