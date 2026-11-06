# Public PNG upload fixture

This standalone test-only APK is separate from the product and is never included
in the production APK. It exercises real cross-UID content reads using exactly
two fixed public PNGs. English instructions are followed by the Chinese version.

## English

### Boundary and proof

- Application ID: `io.github.yanhuicsdn.atomcode.uploadfixture`; no shared UID or
  requested network, storage or other permissions. `allowBackup=false`,
  `fullBackupContent=false`, `testOnly=true`; the release variant is disabled.
- AGP **8.11.1**, repository wrapper **Gradle 8.13**, **JDK 17**, build tools
  **36.1.0**, compile SDK **36**, min SDK **26**, target SDK **28**; platform APIs
  only. The local default debug signing certificate must match the product APK.
  The host verifies both digests and Android enforces instrumentation signatures;
  no signing key/password is stored in this fixture. Lint disables only
  `ExpiredTargetSdkVersion` for this target-28 sideload-only APK. The no-launcher
  app may retain the cosmetic `MissingApplicationIcon` warning.
- The non-exported provider has `grantUriPermissions=true` and serves only
  `content://io.github.yanhuicsdn.atomcode.uploadfixture.png/one.png` and
  `content://io.github.yanhuicsdn.atomcode.uploadfixture.png/two.png`. Each is an
  original generated 1x1 RGBA PNG of 70 bytes. Query exposes standard
  `DISPLAY_NAME`/`SIZE`, the type is `image/png`, and `openFile` accepts only `r`.
  Other URIs, query/fragment variants, modes and mutations are rejected.
- Only the fixture's `files/public-png-upload-v1/` is used. Every existing entry
  and byte sequence must match the two known canaries before creating missing
  files or cleaning up. Unknown files, changed bytes and symlinks are refused;
  nothing is overwritten or recursively removed.
- Exported `GrantActivity` uses `Theme.NoDisplay` and accepts only fixed `.GRANT`,
  `.REVOKE`, `.CLEAN` actions, without caller-selected packages, paths or URIs.
  GRANT checks the same signature and different UIDs, then gives only read access
  to `io.github.yanhuicsdn.atomcode` for the two URIs. REVOKE targets only that
  package and those URIs. CLEAN revokes and removes only verified canaries and
  their otherwise-empty dedicated directory.

Instrumentation targets the real main app: its UID differs from the fixture
provider/GrantActivity UID. It starts the actual MainActivity and awaits its
WebView, then invokes the real GrantActivity and verifies permissions in that
**same live owner process**. It observes the production `allowContentAccess=false`
setting and requires it to remain false; it never changes production settings.

It adds one uniquely named DOM `<input type=file multiple accept="image/*">`,
uses a real pointer tap, and intercepts the resulting `ACTION_OPEN_DOCUMENT`.
The monitor checks multiple/image MIME/read-only hints and supplies only the two
already granted content URIs through a framework Activity result. It never
calls the production upload callback directly or fabricates browser `File`s.
Actual `FileReader.readAsArrayBuffer` must read both PNGs; the resulting base64
and decoded bytes must match exactly. Unexpected selection metadata is rejected
before reading. JavaScript inspects only its own test input, result and geometry,
never URLs, cookies, application text, configuration, other files or model data.

Finally the same owner process asks GrantActivity to revoke, observes denied
permissions, and requires new `openFileDescriptor` calls for both URIs to throw
`SecurityException`. It removes the test DOM node and finishes its Activity.
Five checks require real byte matching, same-process revocation and DOM cleanup.
The separate `check_revoked` mode starts no MainActivity and proves denial before
and after the reader case.

**Scope:** real cross-UID provider/Chromium FileReader and framework Activity
result delivery with a synthetic picker result. This does not prove actual
DocumentsUI hand-selection, arbitrary providers, server/model upload or model
acceptance. A test failure does not authorize changing production settings.

### Local build

From the repository root, configure the installed JDK 17 and Android SDK:

```sh
export JAVA_HOME=/absolute/path/to/jdk17
export ANDROID_HOME=/absolute/path/to/Android/sdk
android/gradlew -p android/tests/fixtures/public-png-upload \
  --project-cache-dir "$PWD/.tools/public-png-upload-gradle" \
  :app:assembleDebug :app:lint --offline --no-build-cache --console=plain
```

The APK is
`android/tests/fixtures/public-png-upload/app/build/outputs/apk/debug/app-debug.apk`.
`--offline` needs existing AGP/Gradle caches. Keep the default local debug signing
configuration matching the product. This command does not build/package the
production app. Use `ANDROID_HOME` rather than adding machine-local
`local.properties`; project cache is directed to ignored `.tools/`. Build output,
cache, evidence, APKs and signing keys are not fixture source files.

### Controlled execution

The product APK must already be installed and match `--apk`. The helper accepts
only running owned AVDs `AtomCode_API36_16K_Arm64` / 16384-byte pages or
`AtomCode_Backup_AOSP36_Arm64` / 4096-byte pages. It checks `ranchu`/qemu, exact
AVD/page size, local package IDs and matching signatures, installed APK digests,
a fresh output directory, and the explicit confirmation flag. Phones are
rejected. Keep the owned AVD unlocked and free of user/model operations.

```sh
python3 android/tests/fixtures/public-png-upload/run_fixture.py \
  --serial emulator-5564 --avd AtomCode_Backup_AOSP36_Arm64 \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --apksigner "$ANDROID_HOME/build-tools/36.1.0/apksigner" \
  --aapt2 "$ANDROID_HOME/build-tools/36.1.0/aapt2" \
  --apk /absolute/path/to/the-already-installed-product.apk \
  --fixture-apk android/tests/fixtures/public-png-upload/app/build/outputs/apk/debug/app-debug.apk \
  --output /tmp/atomcode-public-png-new --confirm-public-png-test
```

The runner never starts/unlocks an emulator, dismisses system UI, changes IME or
network settings, installs/uninstalls the product, or clears data. It installs
the test-only fixture with `-t` only when absent. An identical installed fixture
is reused; a different/unknown APK is refused instead of overwritten. Before an
intentional replacement, inspect, revoke and clean the previous fixture
separately. The fixture remains installed afterward with no grants/canaries;
fixture-only uninstall is a separate optional action.

Sequence: verify artifacts/owned AVD → stop owner → fixed REVOKE → no-grants
check → real MainActivity/GrantActivity/FileReader/same-process REVOKE case →
finally stop owner, fixed REVOKE, denial check, fixed CLEAN, stop fixture/owner
and confirm the exact canary directory is absent. Cleanup refuses unknown bytes
and records incomplete cleanup; it never bypasses this refusal with host `rm`.
The runner reads no main-app private path.

The device budget is 180 seconds, including a 30-second FileReader wait and
bounded grant checks. The host caps the reader run at 180 seconds and final
denial checks at 20 seconds, reserving 60 seconds of its 300-second total budget
for cleanup. On timeout it stops the owner and observes the same instrumentation
handle before revoke/cleanup. There is no automatic retry. Logs contain only
controlled PASS/FAIL stage/class and public fixture metadata, never URLs,
cookies, credentials, unexpected file bytes or generic exception messages.

## 中文

### 测试边界与通过条件

这是独立的 `testOnly` APK，不属于产品组件，也不会打进生产 APK。包名固定为
`io.github.yanhuicsdn.atomcode.uploadfixture`，不共享 UID，不申请网络、存储或其他权限，
关闭备份与 release 变体。构建使用 AGP 8.11.1、仓库 Gradle 8.13 wrapper、JDK 17、
Build Tools 36.1.0、compile SDK 36、min SDK 26、target SDK 28，仅使用平台 API。
使用与主 App 相同的本地默认 debug 签名；host 核对两者证书，Android 再校验
instrumentation 签名。本目录不保存密钥或密码。Lint 只关闭这款 target-28 侧载测试
APK 的 `ExpiredTargetSdkVersion` 检查；无启动图标可能产生外观警告。

非导出的 Provider 仅为英文部分列出的 `one.png`、`two.png` 两个精确 URI 提供数据，
开启 `grantUriPermissions`。两个公开合成 PNG 均为原创生成的 1×1 RGBA、每个 70 字节；
查询仅支持标准 `DISPLAY_NAME`、`SIZE`，类型为 `image/png`，只能以 `r` 打开。
其他 URI、query/fragment 变体、写入模式及修改操作均拒绝。文件只位于 fixture 自己的
`files/public-png-upload-v1/`；创建缺失文件或清理前，必须核对全部已有条目和固定字节。
未知文件、改动字节及符号链接会导致拒绝，不覆盖文件，不递归删除。

公开的无界面 `GrantActivity` 只接受固定 GRANT/REVOKE/CLEAN action，不接受自选包名、
路径或 URI。GRANT 核对同签名、不同 UID，只向固定主包
`io.github.yanhuicsdn.atomcode` 授予两个 URI 的只读权限；REVOKE 只撤销该包和这两个 URI；
CLEAN 先撤权，再删除已验证的 canary 及除此之外为空的专用目录。

Instrumentation 在真实主 App UID 运行，Provider/GrantActivity 使用另一 UID。测试启动
真实 MainActivity 并等待其 WebView，在同一存活主进程内调用真实 GrantActivity 并确认授权。
只记录并断言生产 `allowContentAccess=false`，不修改它。测试插入自己唯一命名的
`multiple`、`accept="image/*"` 文件输入框，以真实点击触发生产 `ACTION_OPEN_DOCUMENT`
适配器。ActivityMonitor 校验多选、MIME、只读提示后，通过框架 Activity result 返回已授权
的两个 URI；不直接调用产品 callback，也不伪造浏览器 File。实际 FileReader 必须读取两份
文件，并同时匹配固定 base64 和解码字节；异常选择元数据在读取前拒绝。JavaScript 只读自己的
测试输入、结果及位置，不读 URL、cookie、产品文字、配置、其他文件或模型数据。

随后在同一存活主进程内撤权，确认权限拒绝，且两个 URI 的新 `openFileDescriptor` 均抛出
`SecurityException`；删除测试 DOM 并结束 Activity。五项通过条件包含真实字节匹配、
同进程撤权与 DOM 清理。独立 `check_revoked` 模式不启动 MainActivity，用于前后拒读校验。

范围只覆盖真实跨 UID Provider/Chromium FileReader 及框架结果回传，picker 的选择结果由
测试提供；不代表 DocumentsUI 手动选取、任意 Provider、服务端/模型上传或模型验收通过。
失败时不能为通过测试而修改生产设置。

### 构建与运行

在仓库根目录设置本机 JDK 17 的 `JAVA_HOME` 和 SDK 的 `ANDROID_HOME`，运行上方
“Local build”命令；离线构建需要已有 AGP/Gradle 缓存，产物位于上方所列 fixture
`app/build/outputs/apk/debug/app-debug.apk`。命令只构建 fixture，不构建或打包主 App。
保持与主包匹配的默认本地 debug 签名；通过 `ANDROID_HOME` 提供 SDK，勿添加
`local.properties`。示例把 project cache 放进已忽略的 `.tools/`。构建输出、缓存、证据、
APK 和签名密钥均不属于这 11 项 fixture 源文件。

主 APK 必须已安装且摘要与 `--apk` 一致。上方运行示例明确要求
`--confirm-public-png-test` 及不存在的新输出目录。只接受两台指定自有 AVD：
`AtomCode_API36_16K_Arm64` / 16384 字节页和 `AtomCode_Backup_AOSP36_Arm64` / 4096 字节页，
并校验 ranchu/qemu、精确 AVD 名/实际页大小、本地包名/同签名及设备已安装 APK 摘要；拒绝手机。
保持自有模拟器解锁且没有用户或模型操作。工具不启动或解锁模拟器、不关闭系统提示、不改
IME/网络设置、不安装或卸载主 App、不清数据。仅在 fixture 缺失时以 `-t` 安装；相同 APK
可复用，未知或不同 APK 拒绝覆盖。主动替换 fixture 前，应单独检查、撤权、清理旧版本。
结束后 fixture APK 仍安装，但无授权或 canary；仅卸载 fixture 属于单独的可选操作。

执行顺序与英文一致：核对产物/自有 AVD、停止主包、固定撤权、拒读检查、真实读取和同进程
撤权；finally 停止主包、再次撤权和拒读检查、固定 CLEAN、停止两包、核对精确 canary 目录
不存在。未知字节会使清理被拒绝并记录未完成，不用 host `rm` 绕过；不读主 App 私有路径。
设备预算 180 秒（FileReader 等待最多 30 秒，授权检查也有上限），host 读取阶段上限 180 秒、
最终拒读检查 20 秒，总预算 300 秒且预留 60 秒清理。超时先停止主包并观察原 instrumentation
handle 终态，再撤权和清理；不会自动重试。日志只包含受控 PASS/FAIL 阶段/类型及公开测试
元数据，不输出 URL、cookie、凭据、未知文件字节或任意异常正文。
