# AtomCode-Go 本地构建

所有编译、打包和测试都在本机执行，不使用 GitHub Actions 或远程构建服务。下载固定版本的开源依赖不属于远程编译。

## 依赖与构建

- Rust 1.95.0、rustup；Linux 目标 `aarch64-unknown-linux-musl`。
- Node.js 22.12.0 及以上、npm；使用仓库已有 `webui/package-lock.json` 执行 `npm ci`，构建脚本会在安装依赖前检查最低版本。
- Python 3.9 以上。`android/runtime/requirements.txt` 固定本机交叉编译和 Debian 解压工具版本，安装在项目内 `.tools/python`。
- JDK 17 以上；通过 `JAVA_HOME` 指定。系统默认 Java 8 不可用于 Android 构建。
- Android SDK：platform 36、build-tools 36.0.0 或兼容版本。通过 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指定。
- Gradle 8.13 wrapper，分发包 SHA-256 已固定。Android Gradle Plugin 8.11.1。

在仓库根目录执行：

```bash
export JAVA_HOME=/absolute/path/to/jdk17/Contents/Home
export ANDROID_HOME=/absolute/path/to/android-sdk
bash android/build-local.sh
```

脚本按顺序构建 WebUI、ARM64 Linux daemon、运行环境和 APK。校验失败时停止，不能并行运行环境打包与 APK 构建，否则 APK 校验门会拒绝尚未完成的 payload。

输出位于 `dist/android/`：调试 APK、设备测试 APK、SHA256SUMS、运行环境构建报告和依赖清单。`dist/`、本地工具链和大型运行环境均不提交到 Git。

## 运行环境

参考 dsh-Go `cf2f7c861e1098661a3a2039b3a4cb1633790102` 的本机运行路径，独立实现 Android 宿主。Ubuntu Base 24.04.5、Git/证书等 Ubuntu 依赖、Termux PRoot、talloc 和 android-shmem 的版本及摘要固定于 `android/runtime/dependencies.json`。参考版本的 PRoot 5.1.107.98 已从当前仓库移除，改用官方索引仍提供的 5.1.107.102，并核对官方构建配方中的源码摘要。

打包不执行异构架构代码。宿主读取 Debian 包，校验 ELF 架构，把 PRoot 的 talloc 动态字符串改为 APK 可打包的 `libtalloc.so`，并记录原始与修改后摘要。Linux 文件打包成只含普通文件和目录的 ZIP，符号链接作为独立元数据在 Android 上最后恢复。保留 Ubuntu 包内版权文件，并附原生组件许可及来源说明。

PRoot 原生组件由 Android 安装器解压到 `nativeLibraryDir`。Linux rootfs 安装到私有的不可变 generation，激活通过原子指针提交；`files/home/` 下的会话、模型设置和工作区不在被替换的 rootfs 内。

启动时除 daemon 外，还根据经过完整 SHA-256 校验的内置 ZIP 验证以下五个固定用户空间文件，以及所需 Linux 别名链接和执行权限：

```text
/usr/bin/env
/usr/bin/bash
/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1
/usr/lib/aarch64-linux-gnu/libc.so.6
/usr/lib/aarch64-linux-gnu/libtinfo.so.6.4
```

检查范围是启动/终端的关键依赖，不是全部 Linux 文件。预期摘要按资产 SHA-256 缓存；DNS 刷新不会展开 ZIP 或遍历代际目录。

只有指针成功提交且保留记录与当前代际匹配后才回收历史环境，保留当前代际，以及安装前指针指向且具有有效完成标志的上一代际（如存在）；不清理未知条目或顶层符号链接。待删除的已完成代际先原子移动到宿主保留槽 `files/runtime/.gc-staging`，再删除内容。即使中断时槽内 `.ready` 已被删除，后续有效启动仍可继续清理。清理失败不撤回已提交环境，会在以后重试；`files/home/` 不属于回收范围。

## 安装与设备 smoke test

```bash
adb -s DEVICE install -r dist/android/atomcode-go-arm64-debug.apk
adb -s DEVICE install -r dist/android/atomcode-go-arm64-smoke-tests.apk
adb -s DEVICE shell am force-stop io.github.yanhuicsdn.atomcode
adb -s DEVICE shell am instrument -w \
  io.github.yanhuicsdn.atomcode.test/io.github.yanhuicsdn.atomcode.LocalRuntimeSmoke
```

测试使用实际 PRoot、Linux daemon、TLS 网关和 WebView，不配置模型凭据，不冒充真实模型端到端验收。设备首次解压需要约 224 MB，加上临时空间、APK 和后续工作区应预留更多空间；具体以本次 manifest 和 App 显示为准。

旧版 avdmanager 可能把 Android 36.1 镜像创建成 `target=android-0`，导致 ARM64 模拟器不启用 HVF。只针对新建的测试 AVD，把其 `.ini` 中 target 修正为 `android-36`。不要修改或清空已有用户 AVD。本项目测试用的 AVD 名为 `AtomCode_API36_Arm64`。

## 签名与升级

默认输出为 Android 本地 debug keystore 签名的调试包。它适合开发验证，不等于正式发行签名。debug keystore 不入库，不随 APK 分发。相同包名升级必须使用相同签名，并保持递增 versionCode；更换签名会导致无法覆盖安装，卸载会删除用户数据，应先导出工作区。

`assembleRelease` 生成未签名 release 包，不自动使用 debug key 冒充发行包。正式交付需用持久保存的独立密钥签名并记录证书摘要，完成真机和升级验收后才能称为正式发行。

## 当前边界

- 源码构建不含官方 CodingPlan 的闭源请求签名器，AtomGit 登录成功仍不能使用官方模型。请在“模型配置”中添加自己的兼容 API 模型；界面与错误提示会明确说明这一限制。
- 打开应用后自动安装或启动环境，就绪后进入全屏工作界面。可从系统通知栏停止引擎；主动停止后可使用原生界面导入、导出工作区。

- `minSdk 26`，仅 ARM64。`targetSdk 28` 与参考项目一致，满足私有目录 Linux 可执行文件的运行限制；这是侧载路径，不能直接当作当前 Play Store 上架包。
- 私有 Unix socket 连接 daemon；Android 宿主在随机回环端口提供 TLS，WebView 严格固定本应用证书，再完成 daemon token 到 HttpOnly/Secure Cookie 的交接。
- 不绑定 LAN，不自动打开远程 AtomCode 服务。外部链接交给浏览器，外部子资源阻止加载。
- 工作区导入生成新的 `imported-*` 项目，不覆盖已有文件；ZIP 上限 2 GB、10 万项。导出遇到符号链接或特殊文件明确失败，不能静默跳过。导入导出要求引擎停止。
- 不保存 daemon 原始输出日志；避免模型凭据、命令参数和工具输出进入 Android 日志。模型失败通过现有 WebUI 呈现。
- 前台服务不等于系统保证常驻。当前唤醒锁最多保持 30 分钟；Android 强制停止、后台限制和不同厂商行为仍需设备记录。
- 原生 PRoot 组件 ELF LOAD 对齐为 16 KB；此静态检查不能替代 16 KB 页大小设备运行验证。
- 公开分发前仍需完成第三方对应源码、构建配方与许可交付审核；仅有源代码 URL 不能替代适用许可证要求的完整对应源码。

界面测试期间保持设备解锁，先关闭 Android 首次全屏提示；模拟器连接实体键盘时，测试屏幕键盘缩放前应启用屏幕键盘。

## 补充宿主检查

工作区要求已安装并启用 Chromium 99 或更新版本的 System WebView，与 WebUI 构建目标一致。过旧、缺失或无法初始化的组件显示原生升级提示；这不代表 Android 8 已验收通过。ZIP 导入需要同时存放压缩归档和展开项目的额外临时空间，两者各限 2 GB；缺失中央目录、条目大小或 CRC 不符时拒绝提交。

在解锁的模拟器上运行隔离存储检查：

```bash
adb -s DEVICE shell am force-stop io.github.yanhuicsdn.atomcode
adb -s DEVICE shell am instrument -w -e storage true \
  io.github.yanhuicsdn.atomcode.test/io.github.yanhuicsdn.atomcode.LocalRuntimeSmoke
```

测试在新建缓存目录中使用真实内置运行环境，结束后删除该目录。覆盖回调取消和注入零可用空间，不能将结果当作强制杀进程或实际磁盘写满的验收证据。

本地源码清单（`python android/runtime/source_inventory.py`）合并 Ubuntu Base 的包状态和经校验的额外 Debian 包控制元数据。当前内置环境有 117 个 Ubuntu 二进制包、82 组源码包/版本及 3 个 Termux 原生组件；`source-inventory.json` 记录对应源码获取页面并反映被覆盖的旧版本，但不等于完整对应源码交付。

### 横竖屏与屏幕键盘

设备须保持解锁，先关闭首次全屏提示，并启用停靠式屏幕输入法。模拟器连接实体键盘时，应先打开屏幕键盘偏好，测试后恢复该全局设置；helper 不修改这项偏好。执行：

```bash
adb -s DEVICE shell am force-stop io.github.yanhuicsdn.atomcode
adb -s DEVICE shell am instrument -w -e layout true -e keyboard_rotation true \
  io.github.yanhuicsdn.atomcode.test/io.github.yanhuicsdn.atomcode.LocalRuntimeSmoke
```

只测横竖屏/长文本时，去掉 `-e keyboard_rotation true`，保留 `-e layout true`。键盘模式检查竖屏和横屏下 IME 确实可见，且 WebView/输入框未被其遮挡；若 Android 在 Activity 重建时隐藏输入法，测试会再次触摸输入框后检查新方向。不发送模型请求。helper 会恢复自身设置的方向和亮屏标志、删除自己的 canary；这只是局部布局覆盖，不代表完整手机验收。

### 独立 UID 安全探针

先按上文安装匹配的宿主 App 和设备测试 APK，再在本地构建、安装独立测试探针：

```bash
android/gradlew -p android :probe:assembleDebug --console=plain
adb -s DEVICE install -r -t android/probe/build/outputs/apk/debug/probe-debug.apk
python3 android/tests/run_security_probe.py --serial DEVICE \
  --adb "$ANDROID_HOME/platform-tools/adb" --output dist/android/evidence/security-DEVICE
```

`:probe` 不打入 `:app`，没有 shared UID 或启动图标，release variant 已禁用；其 manifest 为 test-only，因此安装须加 `-t`。请在没有用户/模型操作运行时执行协调脚本：它会停止并重启宿主运行环境，但不清空应用数据。脚本只传递公开端点/证书元数据，不传 token 或 Cookie，要求通过 7 项独立 UID 检查和 2 项宿主健康检查，结果保存到 `--output` 目录。范围覆盖活跃私有 socket、未认证 TLS 请求、Origin/Host 过滤、已占端口独占和平台证书信任；不能据此声称全部启动前端口抢占、上传或备份行为已验收。
