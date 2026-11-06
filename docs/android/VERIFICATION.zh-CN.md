# AtomCode-Go 验证与验收记录

日期：2026-11-07。状态：开发测试交付，**全部最终验收尚未完成**。本记录区分当前 APK 的实测、旧产物历史、模拟故障和外部阻塞，不把局部通过扩大为整体验收通过。

## 当前产物与源码

AtomCode-Go 0.1.0，ARM64，Android 调试签名。当前本地 APK SHA-256：

```text
28b2a6127be19aba8315d6b377428a3a1166f9ad4d4b28acb79fc5b45411d6e0
```

- Android 宿主、内置引擎/WebUI 及 payload 源码均为 `2cebadbc019c5c0c6a547983cbcbd9da7151f30d`。本轮重新编译 Rust 并组装 payload，没有复用旧的 `a05c031ef` / `atomcode-752a620892e947e2` payload。
- 宿主机升级协调脚本的 canary 精确字节传输修复为 `0f4d412ac`，不改变本 APK 的编译源码。
- 布局观察逻辑来自 `a6f9c2454553e03627c643a34e405dce84c7aa25`；该次修正测试观察方式，没有修改产品布局。
- 运行环境：`atomcode-7477b13242308dfe`。daemon SHA-256：`26b74a624f74ec537d98a749672c50141f5d222cca060d2ba1a27237f4fc46c5`。
- 调试证书 SHA-256：`82923019b3fc796d3ed78d14561ab1d8ae966986e40a1936076ac6d795b02d41`。私钥不进入源码或交付说明。

精确产物、测试源码与依赖摘要见本地 `delivery.json`、`SHA256SUMS`、`build-report.json` 和设备证据文件。

## 本地构建与受控重复构建

`atomcode-fixed-build-inputs.json`、`atomcode-fixed-build-results.json` 和 `atomcode-fixed-full-build.log` 记录 `2cebadbc0` 的完整本地 driver：外置 Cargo target 与运行环境输出起初均不存在，保留已校验的依赖缓存及工具链；实际选用了配置的 target，没有误用默认目录的旧 daemon。WebUI 402 项、Python 19 项和 JVM 31 项通过，Gradle 75 项任务全执行、0 缓存命中，完整 driver 退出 0。

`atomcode-repro-repeat-results.json` 和 `atomcode-repro-repeat-{daemon,payload,apk}.log` 记录同一宿主、工具链和提交下固定 `SOURCE_DATE_EPOCH=1793982551` 的重复构建：修改 WebUI 文件系统时间触发了实际 daemon 重编，重新生成 payload，clean 且禁用缓存的 APK 组装执行 36 项任务。daemon、APK 摘要均与上述当前产物相同；两次 rootfs ZIP 摘要均为 `fcb79df63b43c107a8a90ecb9d5c0d7193f63871ff91bc413878e72fac247566`，manifest 也相同。逐字节一致仅覆盖这次受控的同宿主重复构建，不代表跨宿主或全新机器验收。

此前同宿主的 `9ba42f01d` 隔离源码/空产物基线也已构建成功：首次 Ubuntu 下载摘要不符被拒绝，重新获取匹配原提交固定摘要的字节后完成，未改变固定摘要。该次早期基线本身不是逐字节重建比较。

## 当前设备证据

| 环境 | 系统 / ABI / 页大小 | 当前 APK 的结果 |
| --- | --- | --- |
| Up Mobile Mover Edition 真机 | Android 14 / API 34，ARM64，实测 4096 bytes | 当前包同签名原位升级、独立 UID 7 项、宿主 2 项和隔离缓存存储 10 项通过；未运行 UI 专项，当前手机 UI 和真实模型仍未通过 |
| sdk_gphone64_arm64 模拟器 | Android 16 / API 36，ARM64，4096 bytes | 下方已有模拟器运行记录属于历史 APK `15dd396b`，不声称当前包已有 4 KB 模拟器执行证据 |
| AtomCode_API36_16K_Arm64 官方镜像模拟器 | Android 16 / API 36，ARM64，运行时页大小实测 16384 bytes | 当前包真实升级提交前强杀/恢复、后续 12 项 smoke、独立 UID 7 项及宿主 2 项通过；不扩大为独立存储/旋转专项或真实模型通过 |

当前 `28b2a612` APK 的证据文件：

- `atomcode-upgrade-fixed/upgrade-interruption.json`：从运行环境 `atomcode-752a620892e947e2` 原位升级到 `atomcode-7477b13242308dfe`，在观察到解压且尚未提交的窗口实际执行 `am force-stop`；进程退出后旧指针/ready/daemon 和主目录 84 字节 canary 均未变。之后自动恢复到新内置环境，ready/daemon 匹配 APK，旧代际/canary 保留，staging 清除，保留记录匹配。
- `atomcode-upgrade-fixed/smoke.txt`：恢复后 12 项通过，无 FAIL。`cleanup.json`：恢复后的指针及 staging 清除已核对，协调器恢复其记录的 IME 值 `1`，模拟器正常退出，AVD 保留。升级外层操作前的 IME 初值未记录，不作推断。升级 JSON 记录 API 36、`arm64-v8a`、实测页大小 `16384`。
- `atomcode-fixed-security/{foreign-uid-probe.txt,security-owner.txt,security-probe-summary.json}`：独立 UID 7 项、宿主 2 项通过。`environment.json` 核对已安装 APK 摘要 `28b2a612...`、匹配测试包、AVD 和实测页大小 `16384`；`cleanup.json` 记录正常退出、AVD 保留，本次 IME 前后均为 `1`、未写入该偏好。
- `atomcode-fixed-phone-{preflight,install,summary}.json`：已安装 APK 摘要匹配 `28b2a612...`，API 34、`arm64-v8a`、实测页大小 `4096`；同签名 `-r` 安装保留应用数据，运行环境从 `atomcode-752a620892e947e2` 升级为与最终 APK 匹配的 `atomcode-7477b13242308dfe`。`atomcode-fixed-phone-security/` 记录独立 UID 7 项、宿主 2 项通过，`atomcode-fixed-phone-storage.log` 记录 10 项通过，安装及测试协调器均退出 0。未卸载/清数据、运行 UI 专项、改变屏幕/IME、读取凭据或发送模型请求，手机保持连接。
- 这些本地证据位于 `/tmp/`。真实强杀只覆盖观察到的一次提交前解压窗口，不证明任意强杀时点、GC 中断、断电或全部升级验收通过。

## 历史产物证据

APK `15dd396bdab988ccffa1998badade33376ea3c5041c301d04a1890659f2b7339` 使用 Android 宿主 `ce6303f66`、引擎/WebUI `a05c031ef` 和运行环境 `atomcode-752a620892e947e2`。以下均保留为历史运行，不记成当前 APK 重跑：

- `atomcode-processfix-emulator-smoke-{1,2,3}.log`：连续 3 次，各 12 项通过，无 FAIL。
- `atomcode-processfix-{emulator,phone}-security/foreign-uid-probe.txt`：两端各 7 项通过；同目录 `security-owner.txt`：各 2 项通过。宿主检查还要求正向读到实际带标记的 PRoot 父进程，确认参数中没有 `ATOMCODE_DAEMON_TOKEN=` 赋值。
- `atomcode-processfix-{emulator,phone}-storage.log`：两端各 10 项通过。
- `atomcode-processfix-emulator-keyboard.log`：6 项通过，并保留两次生命周期证明重建但 monitor 未投递的观察。
- `atomcode-16k-environment.json`：`emulator-5562` / `AtomCode_API36_16K_Arm64` 已启动完成，API 36、`arm64-v8a`、实测页大小 `16384`；镜像包 `system-images;android-36;google_apis_ps16k;arm64-v8a`，revision 7，emulator 36.1.9。
- `atomcode-16k-install.json`：历史主 APK、设备测试 APK 和独立 UID 探测 APK 安装退出码均为 0；`atomcode-16k-smoke.log`：该历史包 12 项通过，无 FAIL。探测 APK 安装成功本身不是安全探测通过的证据。
- `atomcode-16k-security/foreign-uid-probe.txt`：7 项通过；同目录 `security-owner.txt`：2 项通过。`security-probe-summary.json` 记录设备 `emulator-5562`、7/2 项计数、未向探测端提供凭据和有限探测范围；协调器退出码 0。

前一宿主 `4b31953b0` / APK `580c500a468055ab7d6e04efba6034cfe7cddff3b0d5421460c1e96a776e12ee` 的补充证据继续保留：两端 `atomcode-recovery-final-{emulator,phone}-storage.log` 各 10 项；`atomcode-recovery-final-emulator-offline.log` 12 项；`atomcode-recovery-lifecycle-keyboard-{1,2}.log` 各 6 项及 monitor 观察。这些结果覆盖未改的安装/回收和布局代码，但不记成当前 APK 重新执行的专项。

同一旧包的 `atomcode-recovery-final-emulator-smoke.log` 曾在 10 项通过后停止失败，报告“不能确认旧引擎是否已退出”；该失败不删除，也不记为 12 项通过。历史包 `15dd396b` 增加进程终止期间身份复核后，通过上述 3 次完整在线运行。更早手机 UI 渲染/键盘初始化的锁屏失败也保留，仍需要当前包解锁后的实际 UI 证据。

测试使用真实 PRoot、Linux daemon、TLS 网关、WebView、PTY 和设备文件系统，没有用远程引擎替代本机执行。12 项 smoke 覆盖自动启动/健康哈希、TLS/Cookie、错误证书拒绝、401/跨 Origin 拒绝、真实命令/Git/退出码、revision 文件编辑、WebView、全屏、键盘缩放、显式停止及重启/代际轮换。离线通过不代表离线模型推理。

历史产物 `a2483445cde4fc4b6171f0da15413b5d8c2239edbeeac6df1c52de2e98fcdaf5` 曾通过真机 12 项 smoke 和 5 项局部布局检查。它们是旧 APK 证据，**不能证明当前包已通过真机 UI 复测**。

## 安装、损坏恢复与回收

当前 APK 在 4 KB 真机通过 10 项隔离存储检查，使用新内置 payload 和可删除的应用缓存根，真实用户主目录及模型凭据不参与测试。历史 APK `15dd396b` 曾在两台 4 KB 设备通过相同 10 项，当前包尚无模拟器独立存储重跑。检查覆盖：

- 安装验证；取消提取后旧指针/环境/主目录保留，staging 清理；注入零可用空间预检；daemon 损坏检测/修复。
- 逐个损坏 env、真实路径 bash、动态加载器、libc 和 libtinfo 后启动校验拒绝；重新安装修复非 daemon 损坏且保留主目录。
- 多次成功安装后保留当前环境与上一个已完成指针，仅回收更旧的宿主管理目录；清理失败可观察，已提交环境仍有效，维护恢复后可重试。
- `.gc-staging` 缺少 `.ready` 的模拟部分删除可以续清；顶层槽为符号链接/非目录时拒绝处理；内部链接删除不跟随外部目标。
- ZIP 往返保留 UTF-8、空目录和执行位，重复导入不覆盖项目；截断/路径穿越拒绝且无残留 staging。

关键文件的期望摘要从完整 SHA-256 校验后的 APK rootfs ZIP 派生，并按资源哈希缓存。`isCurrent()` 检查这 5 个真实路径文件、执行权限和关键 Linux 链接；权限/链接要求对应当前锁定的 ARM64 Linux 布局，daemon 另行验证。这不是全部 Linux 文件完整性校验。DNS 刷新的 `rootfs()` 不解压源 ZIP、不遍历整棵环境目录。

`runtime/retention` 先持久化并绑定新的 active 名称；只有 current 成功提交且与记录匹配后才回收。旧目录先原子移动到固定宿主槽 `.gc-staging` 再删除。未知名称、坏 marker 和顶层符号链接不作为旧环境删除，主目录不参与回收。previous 是上一个**已完成的指针**，不代表全部旧字节在本次重新验证，也不代表自动回滚通过。

维护失败经 `maintenanceWarning()` 和安装进度提示呈现，不把已提交环境误报为安装失败，后续有效启动重试。取消回调、零可用空间和部分 GC 槽均为受控注入/构造，**不代表实际磁盘写满、GC 中真实强杀或断电验收**。上方新包测试增加的真实进程终止证据仅限提交前解压阶段。

首次 `atomcode-upgrade-baseline/upgrade-interruption.json` 因 canary 为零字节，在准备阶段以 `digest-source-not-regular-file` 停止，尚未安装升级或进入强杀窗口，不能当作升级失败。协调器修复 `0f4d412ac` 使用无 PTY 的 shell-v2 并核对 canary 精确字节，成功重跑保留了 84 字节。此前失败不能证明 `adb exec-in` 必然无法使用。

ZIP 输入和展开内容各限 2 GB，需为两者预留临时空间。中央目录必须完整，条目大小/CRC 通过后才提交项目；截断回归在旧实现上失败、修复后通过。

## 独立 UID 探测范围

当前包在实测 16 KB 模拟器和 4 KB 真机均通过独立 UID 7 项检查：UID 与主应用不同；访问运行中私有 Unix socket 被 `EACCES` 拒绝；未认证 sessions 请求拒绝；外来 Origin 拒绝；伪造 Host 拒绝；不能绑定网关当前占用的回环端口；默认平台 TLS 信任拒绝应用自签名证书。两端宿主在探测前后均能认证访问健康的私有 socket，各另有 2 项通过。历史 APK `15dd396b` 曾在三种环境（4 KB 真机、4 KB 模拟器、该 16 KB 模拟器）通过相同 7+2 项，并正向核对实际运行父进程的命令行没有 daemon token 赋值；这些运行保持历史来源。

探测未获得或打印 token、Cookie、模型凭据。已占用端口绑定失败不等于启动前抢占/伪造监听者竞态验收；这些有限测试不证明所有恶意应用、上传、外部导航或备份攻防均已覆盖。

## 键盘、旋转与 WebView

模拟器专项用真实输入框点击、系统键盘可见性及 WebView/键盘几何位置，检查竖屏输入、横屏和返回竖屏、临时长文本滚动、同一代际及工作区 canary。历史 APK `15dd396b` 通过 6 项；新包有 smoke 键盘缩放检查，尚未重跑独立旋转专项。更早宿主包连续两次各 6 项通过的记录继续单独保留。`retained before tap=false` 表明旋转后重新点击恢复键盘再验证不遮挡，**不证明键盘在重建期间始终显示**，也不证明未发送草稿持久化。

新观察证明：有新 Activity 创建/恢复并稳定进入请求方向，但 `ActivityMonitor` 未投递实例。测试改用生命周期和稳定配置共同证明重建，未改产品布局，也未放宽重建、输入框可见和同代际要求。最早旧包那次无细分观察的横屏失败仍不能追溯定因，不能直接归为已定位的 monitor 漏报。

当前手机 UI 因锁屏未通过，解锁后须重跑。此前仅模拟器验证禁用/缺失 WebView 后进程存活、原生升级提示可见且无 WebView 节点，组件已恢复。最低语法目标 Chromium 99，旧/未知版本另有 JVM 测试；这些不是 Android 8 实机证据。

## 本地检查与源码清单

- 当前 APK 修正终止中的进程身份检查：有界重试并复核 UID、启动时间和状态，不把单个 `/proc` 成员不可读直接当作进程已消失，无法确认时继续拒绝；控制凭据通过允许的环境变量传递，不放入长期存活进程的 argv。受控诊断只含阶段、异常类型/errno 和状态布尔值，不含命令行、环境内容或凭据。
- 完整本地 driver 的 WebUI 构建/类型检查/402 项测试、Python 19 项及 JVM **31 项**通过，JVM 0 failures，其中进程所有权回归 9 项；调试构建和 lint 完成，探测 APK 的此前 lint 记录属于历史，不冒充本次 driver 执行。
- 本轮格式检查退出 0，`Cargo.lock` 中 `hkdf`、`hmac`、`zeroize_derive` 三项禁包数量均为 0。
- `atomcode-repro-daemon-no-proxy-tests.log`：设置 `NO_PROXY=localhost,127.0.0.1,::1` 后 daemon nextest 402 项全部通过。首次宿主代理运行的 `atomcode-repro-daemon-tests.log` 继续保留：本地 Ollama 拒绝连接诊断收到代理 HTTP 502 而失败，347 项未执行；通过的重跑将回环地址排除在该宿主代理之外。
- `bash gates/compile.sh` 通过（`atomcode-repro-compile-gate.log`），格式检查也通过。Rust 源码自 `9b7299d2a` 后未变；此前 coding 1,245 项通过/10 项跳过仍是早期证据，不称为新执行。
- 此前 npm 官方 audit 从旧锁的 8 项降至 0 项；不代表当前不存在漏洞。
- 经校验输入的清单有 117 个 Ubuntu 二进制包（82 组源码包/版本）和 3 个 Termux 原生组件，反映实际 overlay 而非 Base 的旧 dpkg 状态。APK 新增 `assets/runtime/licenses/dependencies/index.json` 和 327 份保留原始字节的去重文本，选择范围为 290 个 Cargo 包、165 个已安装 npm 包，包含开发依赖字体。Cargo normal/build 可达图为 workspace feature 合并后的超集，已安装 npm 条目也不证明最终 bundle 保留集合。20 个 Cargo、6 个 npm 包未找到包内许可文本；仓库共享 MIT 单独索引，不抵消这些缺失项。完整对应源码归档仍未交付，SPDX 和源码 URL 不等于完整原文或对应源码。
- Node 构建预检为 22.12+。全部编译在本机，源码发布跳过 CI，不把 APK 放入代码仓库；GitHub 默认英文、GitCode 默认中文，两边保留两种语言。

## 模型与平台阻塞

AtomGit 登录后的真实模型请求失败。共享 provider factory 返回 `SourceBuildGatewayUnsupported`：公开源码没有官方 CodingPlan 闭源请求签名实现，登录不能补齐该构建能力。用户选择暂不配置兼容模型服务，先交付 APK 并明确标注模型未通过。

**真实模型流式回复、模型驱动审批/拒绝、取消及完整对话恢复均未通过验收。** 空会话 metadata 保留不是真实对话恢复；上游隐藏空会话，测试仅直接解析 native session ID 检查 metadata。

官方 API 36 的 Google APIs 16 KB ARM64 镜像 revision 7 已安装并启动，运行时页大小实测 `16384` bytes；当前 APK 的真实提交前强杀/恢复、12 项本机 smoke、独立 UID 7+2 项在该环境通过。这是明确的单一 16 KB 模拟器执行证据，不再只有 ELF LOAD 静态对齐。尚不能据此声明 16 KB 真机、其他 16 KB 镜像、独立存储/旋转专项或真实模型验收通过。Android 8、更多厂商、IPv6/外部 HTTPS、休眠网络恢复和真实模型仍未验收。

## 最终验收矩阵

| 目标 | 已有证据与开放项 |
| --- | --- |
| 1 可复现交付 | 完整空外置 target 本地构建及受控同宿主固定 epoch 重建得到相同 daemon/rootfs/APK；跨宿主/全新机器、正式签名及完整对应源码/许可交付未完成 |
| 2 安装启动 | 当前 16 KB 包真实提交前解压强杀、旧状态保留、自动恢复及 12 项 smoke 通过；当前 4 KB 真机同签名升级及隔离存储 10 项通过，旧包记录独立保留。实际磁盘耗尽、其他中断窗口、断电和当前手机 UI 未完成 |
| 3 本机执行 | 当前 16 KB 实际 PRoot/daemon、PTY/Git/文件通过；当前真机环境完成升级及原生健康/存储检查。完整 4 KB 模拟器、离线及手机 UI 专项仍属历史或本包未执行 |
| 4 安全认证 | 当前 smoke 固定证书/Cookie/401/跨源及当前 16 KB 模拟器、4 KB 真机独立 UID 各 7+2 通过，三环境 `15dd396b` 结果保持历史来源；启动前伪造服务、上传、备份和完整攻防未完成 |
| 5 核心工作流 | 本机终端与文件操作通过；真实模型及模型驱动工具/取消未通过 |
| 6 会话数据 | 当前 16 KB 升级在真实提交前强杀后保留主目录 canary/旧代际并恢复，smoke 重启保留工作区；当前手机隔离取消/修复/归档通过，不涉及真实主目录；**真实对话恢复从未通过**，任意中断和系统文件选择仍待验收 |
| 7 手机可用性 | 当前 16 KB smoke 全屏/键盘缩放通过，6 项独立旋转属于历史；当前手机解锁 UI、完整输入/草稿/代码块/文件选择矩阵未完成 |
| 8 生命周期 | 当前提交前强杀恢复和 smoke 显式停止/重启通过，进程所有权 JVM 9 项回归通过；历史停止失败保留，长时间后台、其他强停场景和厂商行为未完成 |
| 9 兼容网络 | 当前包有单一实测 16 KB API 36 ARM64 配置的恢复/12 项 smoke/7+2，以及 API 34 ARM64/4 KB 真机原生 7+2/存储 10 项；4 KB 模拟器执行保持历史。当前手机 UI、Android 8、多厂商、所有 16 KB 设备/其他专项及完整网络矩阵未完成 |
| 10 验证证据 | 当前哈希、源码和通过/失败/阻塞记录分开保留；不能宣布全部最终验收完成 |
