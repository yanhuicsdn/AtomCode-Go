# AtomCode-Go 验证与验收记录

日期：2026-11-07。状态：开发测试交付，**全部最终验收尚未完成**。本记录区分当前 APK 的实测、旧产物历史、模拟故障和外部阻塞，不把局部通过扩大为整体验收通过。

## 当前产物与源码

AtomCode-Go 0.1.0，ARM64，Android 调试签名。当前候选 APK SHA-256：

```text
f67bbd8c912f95e03a0942ee06bb99afa1481522ebedea163957d2028a45605a
```

- Android 宿主、引擎/WebUI 和 payload 编译源码：`bbec500c1a12c0a386d8f6f91748c4bda7aff47b`，源码干净，`SOURCE_DATE_EPOCH=1793993007`。后续独立测试 fixture 源码 `6ac05726a` 不改变本 APK 来源。
- 匹配测试 APK SHA-256：`313f0f496623df5dfe3f8192c6863471c439f10cb62e9969e52180f6e55e98dc`。
- 运行环境：`atomcode-23286d8bafd6bc29`。daemon SHA-256：`ac3b2348c3a2d2eebc10fb2962c7065e3a50bf4a544a87a5fa6584bd4eb5481d`。rootfs ZIP SHA-256：`5d056be642008fe5f0092069f8a7ac1ef29a438de16ce32a2378dfa3539d1e40`。
- 调试证书 SHA-256：`82923019b3fc796d3ed78d14561ab1d8ae966986e40a1936076ac6d795b02d41`；不包含私钥。
- 真机仍为基线 `28b2a612…` / 源码 `2cebadbc0` / 运行环境 `atomcode-7477b13242308dfe`，不是当前候选包。更早 `15dd396b…` / `atomcode-752a620892e947e2` 证据也单独保留。

当前本机构建记录位于 `dist/android-upload-candidate/`，用于标识验收工作区，不表示已提升为正式交付；其中 `SHA256SUMS`、`build-report.json`、`.tools/upload-candidate-artifacts.json` 和设备证据记录精确产物与输入摘要。

## 本地构建与受控重复构建

当前候选 `bbec500c1` / epoch `1793993007`：`dist/android-upload-candidate/evidence/full-build/` 记录完整本地 driver，使用已有产物的外置 target、工具链和依赖缓存。WebUI 构建/类型检查/402 项、Python 43 项及 JVM 31 项通过，debug 构建/lint 退出 0，Gradle 75 项任务全执行、0 FROM-CACHE；另有离线协调器宿主测试 15 项通过。这是完整 driver 执行，不冒充本轮空 target 或全新机器构建。

`evidence/apk-repeat/result.json`、`assemble.log` 记录同宿主、同源码/epoch、保持固定 payload 不变的 clean 禁缓存 APK 组装，两次 APK 摘要均为 `f67bbd8c…`。该次没有重新生成 daemon/rootfs，不是三件重建；下方旧三件基线不算当前候选的重建证据。

`atomcode-fixed-build-inputs.json`、`atomcode-fixed-build-results.json` 和 `atomcode-fixed-full-build.log` 记录基线 `2cebadbc0` 的完整本地 driver：外置 Cargo target 与运行环境输出起初均不存在，保留已校验的依赖缓存及工具链；实际选用了配置的 target，没有误用默认目录的旧 daemon。WebUI 402 项、Python 19 项和 JVM 31 项通过，Gradle 75 项任务全执行、0 缓存命中，完整 driver 退出 0。

`atomcode-repro-repeat-results.json` 和 `atomcode-repro-repeat-{daemon,payload,apk}.log` 记录同一宿主、工具链和提交下固定 `SOURCE_DATE_EPOCH=1793982551` 的重复构建：修改 WebUI 文件系统时间触发了实际 daemon 重编，重新生成 payload，clean 且禁用缓存的 APK 组装执行 36 项任务。daemon、APK 摘要均与基线 `28b2a612…` 相同；两次 rootfs ZIP 摘要均为 `fcb79df63b43c107a8a90ecb9d5c0d7193f63871ff91bc413878e72fac247566`，manifest 也相同。基线逐字节一致仅覆盖这次受控的同宿主重复构建，不代表跨宿主或全新机器验收。

此前同宿主的 `9ba42f01d` 隔离源码/空产物基线也已构建成功：首次 Ubuntu 下载摘要不符被拒绝，重新获取匹配原提交固定摘要的字节后完成，未改变固定摘要。该次早期基线本身不是逐字节重建比较。

## 当前设备证据

| 环境 | 系统 / ABI / 页大小 | 当前 APK 的结果 |
| --- | --- | --- |
| Up Mobile Mover Edition 真机 | Android 14 / API 34，ARM64，实测 4096 bytes | 仍为基线 `28b2a612…`，升级、原生 7+2 和缓存存储 10 项保持基线来源；当前候选 `f67bbd8c…` 未安装或 UI 复测 |
| sdk_gphone64_arm64 模拟器 | Android 16 / API 36，ARM64，4096 bytes | 已列 `15dd396b` 运行保持历史，不转移为当前候选结果 |
| AOSP AtomCode_Backup_AOSP36_Arm64 模拟器 | Android 16 / API 36，ARM64，实测 4096 bytes；官方 `system-images;android-36;default;arm64-v8a` revision 2 | 当前 f67/313 严格离线 12 项、chooser 7 组、安全 7+2、活跃包 LocalTransport NOT_ALLOWED 及真实双 PNG/FileReader 5 项与拒读/清理通过 |
| AtomCode_API36_16K_Arm64 官方镜像模拟器 | Android 16 / API 36，ARM64，实测 16384 bytes；`google_apis_ps16k` revision 7 | 当前 f67/313 一次真实 28b2→f67 提交前升级强杀/恢复及随后 12 项 smoke 通过；额外严格离线 12 项、安全 7+2 和 raw chooser 7 组通过；真实双 PNG FileReader/撤权也通过，备份未测，旧存储/低空间/旋转不算重跑 |

当前 f67/313 本地证据位于 `.tools/candidate-device-tests/`：

- `aosp4k/file-chooser-raw-summary.json`：明确 raw result、退出 0，7 组真实 Activity/WebView 适配器检查通过，覆盖单/多选 MIME、异常/不可信结果、callback 替换/重入、Activity 重建 request code 隔离、主/子 frame 错误、实际 stop/start 后旧 WebView/client 回调、后台 runtime origin 消失。这一套选择 URI 为合成值。首次 pretty 输出已有 7 PASS/无 FAIL，host 错把缺少的 raw 标记作为失败；原报告保留，不改写成协调器通过。
- `aosp4k/offline-current/`：首次真实运行保留 9 PASS 后键盘失败。只读/UI 诊断定位到系统首次全屏提示，`offline-current-ime-diagnostic/` 记录正常点击“Got it”。随后 `offline-current-confirmed/` 在产品和严格断言不变的条件下 8.963 秒通过 12 项，并实际观察无 active/default/connected 网络；`offline-current-confirmed-outer/` 记录真实重连及恢复外层 WiFi `0`、飞行 `1`、mobile-data `1`、IME `0`，备份状态不变。不能描述为修改产品或放宽断言修复。
- `aosp4k/security-backup-active/verified-run/summary.json`：独立 UID 7 项、认证宿主 2 项通过。包专属 LocalTransport 备份请求前后保持同一活跃 lease/PID，`stopped=false`、`notLaunched=false`，排除停包混杂；Backup Manager 保持启用，返回 `NOT_ALLOWED`。父目录更早的包状态 host 解析停止记录保留，它不证明安全/备份已通过。本项不覆盖全部备份/D2D/厂商路径。
- `.tools/backup-aosp-evidence/20261106T190716Z-bbb9f1f8/summary.json`：独立 allow-backup 对照包实际备份固定 101 字节 canary、删除后通过唯一离线 LocalTransport 恢复完全一致的字节/摘要；证明该本地后端有效，不是真实产品数据恢复。首次生产 `28b2` 拒绝受 stopped 状态混杂，保持基线来源；上方活跃 f67 case 排除了该混杂。
- `aosp4k/content-read-current/`：独立 fixture `fe89c22b…`（源码后收录于 `6ac05726a`）5 项通过：真实 DOM 点击/生产 OPEN_DOCUMENT、主/Provider UID 10151/10154 间的两份 70 字节 PNG 真实 FileReader 字节及 base64 匹配、生产 `allowContentAccess=false` 未改、同一存活主进程显式撤权后新 open 被 SecurityException 拒绝，以及 DOM 清理。独立前后拒读检查均通过，固定 canary 目录不存在，两进程退出，外层设置不变。范围是测试提供 picker 结果，不是 DocumentsUI 手选或服务端/模型上传。
- `16k/upgrade-f67-once/upgrade-interruption.json`、`upgrade-f67-outer/summary.json`：基线 `atomcode-7477b13242308dfe` 到 `atomcode-23286d8bafd6bc29` 的一次原位升级；观察到非空 staging、尚无 ready/current 提交后实际 `am force-stop`，旧指针/ready/daemon 和 home 下 84 字节 canary 不变，部分 staging 仍在。重启自动恢复新环境，ready/daemon 匹配，旧代际/canary 保留，staging 清除，保留记录匹配，默认 smoke 12 项通过。协调器及同一 handle 退出 0，IME 恢复 `1`。只覆盖一次观察到的提交前窗口，不证明任意中断、GC 强杀或断电。

- `16k/{offline-current,security-current,chooser-current}/`：各单次、同一 handle 终态，严格离线 12、独立 UID 7+2、明确 raw chooser 7 组（result code -1）均无 FAIL。`final-stages-outer/outer-cleanup.json` 为 passed：已安装 App/tests/probe f67/313/67e 摘要不变、主包 PID 消失，WiFi `1` / 飞行 `0` / mobile-data `1` / IME `1` 恢复，实际 CELLULAR 重连。在该 Google APIs 镜像未执行任何备份命令；chooser 选择仍是合成值。

- `16k/content-read-current/upload-fixture-summary.json`：相同 immutable fixture fe89 与产品 f67，在实测 16384 字节页环境 12.548 秒通过真实 FileReader 5 项。主/Provider UID 10213/10216 不同，两份 70 字节 PNG 字节/base64 匹配，生产 `allowContentAccess=false` 未改，同进程撤权后拒绝新 open，独立前后拒读检查通过，DOM/canary 清理完成且精确目录不存在。无模型请求、凭据读取、主包安装/清数据或设置修改。范围仍为拦截 picker 回传，不是 DocumentsUI 手选或模型上传。

基线 APK SHA-256 `28b2a6127be19aba8315d6b377428a3a1166f9ad4d4b28acb79fc5b45411d6e0` 编译源码 `2cebadbc019c5c0c6a547983cbcbd9da7151f30d`，运行环境 `atomcode-7477b13242308dfe`，daemon `26b74a624f74ec537d98a749672c50141f5d222cca060d2ba1a27237f4fc46c5`。保留证据如下，均不记为 f67 重跑：

- `atomcode-upgrade-fixed/upgrade-interruption.json`：从运行环境 `atomcode-752a620892e947e2` 原位升级到 `atomcode-7477b13242308dfe`，在观察到解压且尚未提交的窗口实际执行 `am force-stop`；进程退出后旧指针/ready/daemon 和主目录 84 字节 canary 均未变。之后自动恢复到新内置环境，ready/daemon 匹配 APK，旧代际/canary 保留，staging 清除，保留记录匹配。
- `atomcode-upgrade-fixed/smoke.txt`：恢复后 12 项通过，无 FAIL。`cleanup.json`：恢复后的指针及 staging 清除已核对，协调器恢复其记录的 IME 值 `1`，模拟器正常退出，AVD 保留。升级外层操作前的 IME 初值未记录，不作推断。升级 JSON 记录 API 36、`arm64-v8a`、实测页大小 `16384`。
- `atomcode-fixed-security/{foreign-uid-probe.txt,security-owner.txt,security-probe-summary.json}`：独立 UID 7 项、宿主 2 项通过。`environment.json` 核对已安装 APK 摘要 `28b2a612...`、匹配测试包、AVD 和实测页大小 `16384`；`cleanup.json` 记录正常退出、AVD 保留，本次 IME 前后均为 `1`、未写入该偏好。
- `atomcode-fixed-phone-{preflight,install,summary}.json`：已安装 APK 摘要匹配 `28b2a612...`，API 34、`arm64-v8a`、实测页大小 `4096`；同签名 `-r` 安装保留应用数据，运行环境从 `atomcode-752a620892e947e2` 升级为与该基线 APK 匹配的 `atomcode-7477b13242308dfe`。`atomcode-fixed-phone-security/` 记录独立 UID 7 项、宿主 2 项通过，`atomcode-fixed-phone-storage.log` 记录 10 项通过，安装及测试协调器均退出 0。未卸载/清数据、运行 UI 专项、改变屏幕/IME、读取凭据或发送模型请求，手机保持连接。
- `atomcode-real-space-final/{real-space-summary.json,device-test.txt,launch.json,backing.json,install.json,cleanup.json}`：基线 `28b2a612...` App 与另行构建的 `9e1ddc61...` 测试 APK 在我方 16 KB 模拟器通过真实低空间 5 项；下方记录实际空间、数据保持、释放后重装及清理数值。
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

候选 `f67bbd8c…` 未重跑独立缓存存储 10 项或真实低空间 5 项。基线 `28b2a612…` 曾在 4 KB 真机使用其内置 payload 通过缓存隔离 10 项，不涉及真实用户 home/模型凭据，并在我方 16 KB 模拟器通过下方真实低空间检查；历史 `15dd396b` 曾在两台 4 KB 设备通过相同缓存 10 项。它们保持产物独立的基线来源；10 项覆盖：

- 安装验证；取消提取后旧指针/环境/主目录保留，staging 清理；注入零可用空间预检；daemon 损坏检测/修复。
- 逐个损坏 env、真实路径 bash、动态加载器、libc 和 libtinfo 后启动校验拒绝；重新安装修复非 daemon 损坏且保留主目录。
- 多次成功安装后保留当前环境与上一个已完成指针，仅回收更旧的宿主管理目录；清理失败可观察，已提交环境仍有效，维护恢复后可重试。
- `.gc-staging` 缺少 `.ready` 的模拟部分删除可以续清；顶层槽为符号链接/非目录时拒绝处理；内部链接删除不跟随外部目标。
- ZIP 往返保留 UTF-8、空目录和执行位，重复导入不覆盖项目；截断/路径穿越拒绝且无残留 staging。

关键文件的期望摘要从完整 SHA-256 校验后的 APK rootfs ZIP 派生，并按资源哈希缓存。`isCurrent()` 检查这 5 个真实路径文件、执行权限和关键 Linux 链接；权限/链接要求对应当前锁定的 ARM64 Linux 布局，daemon 另行验证。这不是全部 Linux 文件完整性校验。DNS 刷新的 `rootfs()` 不解压源 ZIP、不遍历整棵环境目录。

`runtime/retention` 先持久化并绑定新的 active 名称；只有 current 成功提交且与记录匹配后才回收。旧目录先原子移动到固定宿主槽 `.gc-staging` 再删除。未知名称、坏 marker 和顶层符号链接不作为旧环境删除，主目录不参与回收。previous 是上一个**已完成的指针**，不代表全部旧字节在本次重新验证，也不代表自动回滚通过。

维护失败经 `maintenanceWarning()` 和安装进度提示呈现，不把已提交环境误报为安装失败，后续有效启动重试。取消回调、零可用空间和部分 GC 槽均为受控注入/构造，**不代表实际磁盘写满、GC 中真实强杀或断电验收**。上方当前 f67 升级新增的真实进程终止证据仅限提交前解压；旧 28b2 case 保持基线来源。

### 真实低空间预检

基线工具/测试源码 `c5700c128`、`28b2a612...` App 与另行构建测试 APK SHA-256 `9e1ddc61153500744a7f9e85ee531a99907ac5ea01320d39081cb1aceff67115` **仅执行一次、5/5 通过**，环境限 `AtomCode_API36_16K_Arm64`。fixture 位于应用 **files** 下的唯一独立目录，普通 `File.getUsableSpace()` 读取实际 6,228,115,456-byte 数据文件系统，不使用缓存 fixture 或覆写空间值。启动未传 `-data`/`-datadir`；`.ini`、生成的 qemu 配置与活动 userdata 文件描述符均指向默认目录及所检查的宿主文件系统，实际页大小 `16384`。

真实安装一次内置环境并记录 fixture 的指针、ready、daemon 和 home canary 后，顺序同步写入 **4,196,466,128 bytes**，实际分配 **4,196,474,880 bytes**，耗时 7,158 ms。真实可用空间降至 **318,488,576 < 所需 335,266,352 bytes**，仍高于 **256 MiB** 保留线。实际安装器报空间不足，**提取回调为 0**，旧 fixture 指针/ready/daemon/canary 均不变。删除填充后可用空间恢复为 4,514,959,360 bytes，真实重装成功且 canary 保留。限制未放宽：文件系统最多 16 GiB、填充最多 6 GiB/120 秒、初始宿主余量至少 8 GiB，设备/宿主时限 240/360 秒。[构建说明](BUILD.zh-CN.md#真实低空间预检仅我方模拟器)提供带显式确认的命令；host 拒绝手机，并在本地拒绝会落入默认套件的旧测试 APK。

填充文件和精确 fixture 已删除。客体可用空间由测试前 4,758,884,352 恢复至 4,758,827,008 bytes，相差 57,344 bytes，处于正常系统写入量级。IME 前后均为 `1`，模拟器正常关闭且保留 AVD。宿主 qcow2 保留增长：宿主空闲由 68,650,450,944 变为 64,180,596,736 bytes，**不声称宿主空间全部回收**。本次未操作手机、生产 APK、真实用户 home、UI 或模型。该基线未在 f67 重跑；它证明独立 fixture 的真实低空间**预检**，不代表物理 NAND/零空间耗尽、提取中途 ENOSPC 或真实用户 home 恢复验收通过。

首次 `atomcode-upgrade-baseline/upgrade-interruption.json` 因 canary 为零字节，在准备阶段以 `digest-source-not-regular-file` 停止，尚未安装升级或进入强杀窗口，不能当作升级失败。协调器修复 `0f4d412ac` 使用无 PTY 的 shell-v2 并核对 canary 精确字节，成功重跑保留了 84 字节。此前失败不能证明 `adb exec-in` 必然无法使用。

ZIP 输入和展开内容各限 2 GB，需为两者预留临时空间。中央目录必须完整，条目大小/CRC 通过后才提交项目；截断回归在旧实现上失败、修复后通过。

## 独立 UID 探测范围

当前 f67 候选在实测 AOSP 4 KB 和官方镜像 16 KB 模拟器分别通过独立 UID 7 项：UID 不同；访问活跃私有 Unix socket 明确被 `EACCES` 拒绝；未认证 sessions 拒绝；外来 Origin 拒绝；伪造 Host 拒绝；不能绑定网关当前占用的回环端口；默认 TLS 信任拒绝自签名证书。两环境宿主探测前后认证访问健康私有 socket，分别另 2 项通过。基线 `28b2a612…` 在 16 KB 模拟器和 4 KB 真机通过 7+2；历史 `15dd396b` 在三环境通过相同范围，并正向检查实际 runtime 父进程参数没有 daemon token 赋值。均不转移成新产物证据。

探测不获得或打印 token、Cookie、模型凭据。已占端口绑定失败不证明完整启动前伪造监听/抢占竞态。上方活跃包 LocalTransport 和固定 PNG 检查新增了有限备份/内容证据，不代表全部上传、外部导航、备份/D2D/厂商或恶意 App 场景。

## 键盘、旋转与 WebView

模拟器专项用真实输入框点击、系统键盘可见性及 WebView/键盘几何位置，检查竖屏输入、横屏和返回竖屏、临时长文本滚动、同一代际及工作区 canary。历史 APK `15dd396b` 通过 6 项；当前 f67 有 AOSP 4 KB 和 16 KB smoke 键盘缩放覆盖，尚未重跑独立旋转专项。更早宿主包连续两次各 6 项通过的记录继续单独保留。`retained before tap=false` 表明旋转后重新点击恢复键盘再验证不遮挡，**不证明键盘在重建期间始终显示**，也不证明未发送草稿持久化。

新观察证明：有新 Activity 创建/恢复并稳定进入请求方向，但 `ActivityMonitor` 未投递实例。测试改用生命周期和稳定配置共同证明重建，未改产品布局，也未放宽重建、输入框可见和同代际要求。最早旧包那次无细分观察的横屏失败仍不能追溯定因，不能直接归为已定位的 monitor 漏报。

当前 f67 手机安装/UI 尚未通过，手机仍保留 28b2，需明确解锁后实际验收。上方 AOSP 首次全屏提示失败、正常确认和严格离线通过均保留，不描述为产品布局修复。此前模拟器基线禁用/缺失 WebView 后观察进程存活、原生升级提示及无 WebView 节点，组件已恢复；最低语法目标 Chromium 99 有旧/未知版本 JVM 覆盖，不是 Android 8 执行证据。

## 本地检查与源码清单

- 当前 APK 修正终止中的进程身份检查：有界重试并复核 UID、启动时间和状态，不把单个 `/proc` 成员不可读直接当作进程已消失，无法确认时继续拒绝；控制凭据通过允许的环境变量传递，不放入长期存活进程的 argv。受控诊断只含阶段、异常类型/errno 和状态布尔值，不含命令行、环境内容或凭据。
- 完整本地 driver 的 WebUI 构建/类型检查/402 项测试、Python 43 项及 JVM **31 项**通过，JVM 0 failures，其中进程所有权回归 9 项；调试构建和 lint 完成，探测 APK 的此前 lint 记录属于历史，不冒充本次 driver 执行。
- 本轮格式检查退出 0，`Cargo.lock` 中 `hkdf`、`hmac`、`zeroize_derive` 三项禁包数量均为 0。
- 基线 `28b2` 记录 `atomcode-repro-daemon-no-proxy-tests.log`：设置 `NO_PROXY=localhost,127.0.0.1,::1` 后 daemon nextest 402 项全部通过。首次宿主代理运行的 `atomcode-repro-daemon-tests.log` 继续保留：本地 Ollama 拒绝连接诊断收到代理 HTTP 502 而失败，347 项未执行；通过的重跑将回环地址排除在该宿主代理之外。
- 当前源码编译门通过（`evidence/full-build/compile-gate.log`）；基线 `atomcode-repro-compile-gate.log` 和格式检查单独保留。Rust 源码自 `9b7299d2a` 后未变；此前 coding 1,245 项通过/10 项跳过仍是早期证据，不称为新执行。
- 此前 npm 官方 audit 从旧锁的 8 项降至 0 项；不代表当前不存在漏洞。
- 经校验输入的清单有 117 个 Ubuntu 二进制包（82 组源码包/版本）和 3 个 Termux 原生组件，反映实际 overlay 而非 Base 的旧 dpkg 状态。当前 APK 保留 `assets/runtime/licenses/dependencies/index.json` 和 327 份保留原始字节的去重文本，选择范围为 290 个 Cargo 包、165 个已安装 npm 包，包含开发依赖字体。Cargo normal/build 可达图为 workspace feature 合并后的超集，已安装 npm 条目也不证明最终 bundle 保留集合。20 个 Cargo、6 个 npm 包未找到包内许可文本；仓库共享 MIT 单独索引，不抵消这些缺失项。完整对应源码归档仍未交付，SPDX 和源码 URL 不等于完整原文或对应源码。
- Node 构建预检为 22.12+。全部编译在本机，源码发布跳过 CI，不把 APK 放入代码仓库；GitHub 默认英文、GitCode 默认中文，两边保留两种语言。
- 前端锁现已固定全部 178 个独立归档，1 个 bundled 子包由父归档摘要保护。原有摘要经官方精确版本元数据核对，新增 138 组 URL/SHA-512 未改变版本或依赖关系。WebUI 构建、类型检查和 402 项测试通过，94 个生成文件摘要与之前一致；8 项离线预检测试及真实旧锁在网络/构建前被拒绝的检查通过。锁单独比较当时未替换基线 `28b2a612`；当前 f67 完整构建随后使用了该固定锁。
- 当前完整构建宿主工具测试 43 项通过，另有离线协调器 15 项，包含源码子集、许可补充资料和锁预检；当前编译门通过。[源码材料](SOURCES.zh-CN.md) 记录实际取得 Ubuntu 82 组源码/版本中的 2 组（6 文件、13,878,273 字节），仅校验摘要一致，未独立认证 OpenPGP 签名；另有 10 项关联版本的许可原文补充、3 项未解决外部包及 13 个 workspace 共享 MIT 引用。APK 原有 327 份原文索引未变，完整对应源码交付仍未完成。

## 模型与平台阻塞

AtomGit 登录后的真实模型请求失败。共享 provider factory 返回 `SourceBuildGatewayUnsupported`：公开源码没有官方 CodingPlan 闭源请求签名实现，登录不能补齐该构建能力。本次开发交付保持模型验收未通过，仍需配置兼容服务并实际验证。

**真实模型流式回复、模型驱动审批/拒绝、取消及完整对话恢复均未通过验收。** 空会话 metadata 保留不是真实对话恢复；上游隐藏空会话，测试仅直接解析 native session ID 检查 metadata。

官方 API 36 Google APIs 16 KB ARM64 revision 7 启动后实际 `PAGE_SIZE=16384`，当前 f67 完成真实 28b2→f67 提交前强杀/恢复及 12 项 smoke；额外严格离线 12 项、安全 7+2 和 raw chooser 7 组通过；当前 16 KB 真实双 PNG FileReader/撤权也通过；未在该 Google APIs 镜像测试备份。基线 28b2 的安全 7+2 和真实低空间 5 项仍是基线。此为实际测量执行，不是仅 ELF 对齐，只证明单一模拟器配置。16 KB 真机、Android 8、更多厂商、当前手机安装/UI、独立旋转、IPv6/外部 HTTPS、休眠网络恢复和真实模型仍未验收。

## 最终验收矩阵

| 目标 | 已有证据与开放项 |
| --- | --- |
| 1 可复现交付 | 当前完整本地 driver 和同宿主 clean APK 组装得到相同 f67，重复组装复用 daemon/rootfs；旧 28b2 三件重建保持基线。跨宿主/新机器、正式签名及完整对应源码/许可未完成 |
| 2 安装启动 | 当前实测 16 KB 28b2→f67 真实提交前强杀/恢复及 smoke 12 项、AOSP 4 KB 离线启动通过；缓存 10 项/真实低空间 5 项保持 28b2 基线。当前手机安装/UI、任意中断、耗尽、中途 ENOSPC、断电未完成 |
| 3 本机执行 | 当前 AOSP 4 KB 严格离线 12 项与实测 16 KB 恢复 smoke 12 项使用真实 PRoot/daemon、PTY/Git/文件；当前 16 KB 严格离线 12 项也通过，真机仍为 28b2；不声称离线模型推理 |
| 4 安全认证 | 当前 AOSP 4 KB 与 16 KB smoke、安全 7+2 通过；AOSP 4 KB 活跃包 LocalTransport NOT_ALLOWED 排除 stopped 混杂，独立 101 字节对照真实恢复；固定 PNG 跨 UID 真读/撤权通过。两环境真实固定 PNG 读取/撤权均通过，16 KB 备份未测；启动前竞态、DocumentsUI 手选/任意 Provider、D2D/厂商及完整攻防未完成 |
| 5 核心工作流 | 本机命令/文件和有限的真实附件 FileReader 读取通过；仍需兼容模型服务，流式回复、模型驱动工具、审批/拒绝与取消未通过 |
| 6 会话数据 | 当前 16 KB 真实提交前强杀保留旧指针/ready/daemon 及 home 84 字节 canary，恢复新环境并通过工作区 smoke；旧隔离数据测试分开。真实对话恢复从未通过，任意中断/实际手动选取待验收 |
| 7 手机可用性 | 当前 AOSP 4 KB chooser 7 组、正常确认首次系统全屏提示后离线全屏/键盘通过，16 KB 恢复/离线 smoke 和 chooser 7 组通过；独立旋转属历史。当前真机 UI、草稿/代码块和 DocumentsUI 手选待验收 |
| 8 生命周期 | 当前真实提交前强杀恢复、smoke 停止/重启与 chooser 旧回调/代际隔离通过，JVM 9 项进程所有权回归通过；历史停止失败保留，长后台、其他强杀窗口和厂商待验收 |
| 9 兼容网络 | 当前 API 36 ARM64 实测 AOSP 4 KB 离线/chooser/安全/内容/活跃本地备份和官方 16 KB 恢复/12 smoke 证据；当前 16 KB 离线 12/安全 7+2/chooser 7 组也通过，真实固定 PNG 读取/撤权也通过、备份未测，真机仍为 28b2。Android 8、多厂商/16 KB 真机、IPv6/外部 HTTPS 和完整网络矩阵未完成 |
| 10 验证证据 | 当前 f67/313 摘要/源码、原始失败尝试及 28b2/15dd 基线分开保留；不能宣布全部最终验收完成 |
