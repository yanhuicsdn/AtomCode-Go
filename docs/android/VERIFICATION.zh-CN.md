# AtomCode-Go 验证与验收记录

日期：2026-11-06。状态：开发测试交付，**全部最终验收尚未完成**。本记录区分当前 APK 的实测、旧产物历史、模拟故障和外部阻塞，不把局部通过扩大为整体验收通过。

## 当前产物与源码

AtomCode-Go 0.1.0，ARM64，Android 调试签名。当前本地 APK SHA-256：

```text
15dd396bdab988ccffa1998badade33376ea3c5041c301d04a1890659f2b7339
```

- Android 宿主及本轮设备测试源码：`ce6303f66325f991096deec101a6329aba0fa4f8`。
- 布局观察逻辑来自 `a6f9c2454553e03627c643a34e405dce84c7aa25`；该次修正测试观察方式，没有修改产品布局。
- 内置引擎/WebUI 构建源码仍为 `a05c031ef27156cc733c0b144047ee562325f4d3`，本轮没有重编 Rust。
- 运行环境：`atomcode-752a620892e947e2`。daemon SHA-256：`39393afd3622e7f32b39fd4d2cc254fb2d897fca6faa246c3075b382e29949da`。
- 调试证书 SHA-256：`82923019b3fc796d3ed78d14561ab1d8ae966986e40a1936076ac6d795b02d41`。私钥不进入源码或交付说明。

精确产物、测试源码与依赖摘要见本地 `delivery.json`、`SHA256SUMS`、`build-report.json` 和设备证据文件。

## 当前设备证据

| 环境 | 系统 / ABI / 页大小 | 当前 APK 的结果 |
| --- | --- | --- |
| Up Mobile Mover Edition 真机 | Android 14 / API 34，ARM64，4096 bytes | 当前包 10 项存储、独立 UID 7 项及宿主前后 2 项通过，包含对实际运行父进程 argv 的检查。UI 仍待用户解锁后复测，不能记为当前包 UI 通过 |
| sdk_gphone64_arm64 模拟器 | Android 16 / API 36，ARM64，4096 bytes | 当前包连续 3 次在线 smoke，各 12 项通过；存储 10 项、独立 UID 7 项、宿主 2 项和键盘/旋转 6 项通过。当前离线专项尚无重跑结果；旧包证据见下方范围说明 |
| AtomCode_API36_16K_Arm64 官方镜像模拟器 | Android 16 / API 36，ARM64，运行时页大小实测 16384 bytes | 当前包 12 项 smoke、独立 UID 7 项及宿主前后 2 项全部通过，无 FAIL；不扩大为独立存储/旋转专项、真实模型或全部 16 KB 设备验收通过 |

当前 `ce6303f66` APK 的证据文件：

- `atomcode-processfix-emulator-smoke-{1,2,3}.log`：连续 3 次，各 12 项通过，无 FAIL。
- `atomcode-processfix-{emulator,phone}-security/foreign-uid-probe.txt`：两端各 7 项通过；同目录 `security-owner.txt`：各 2 项通过。宿主检查还要求正向读到实际带标记的 PRoot 父进程，确认参数中没有 `ATOMCODE_DAEMON_TOKEN=` 赋值。
- `atomcode-processfix-{emulator,phone}-storage.log`：两端各 10 项通过。
- `atomcode-processfix-emulator-keyboard.log`：6 项通过，并保留两次生命周期证明重建但 monitor 未投递的观察。
- `atomcode-16k-environment.json`：`emulator-5562` / `AtomCode_API36_16K_Arm64` 已启动完成，API 36、`arm64-v8a`、实测页大小 `16384`；镜像包 `system-images;android-36;google_apis_ps16k;arm64-v8a`，revision 7，emulator 36.1.9。
- `atomcode-16k-install.json`：主 APK、设备测试 APK 和独立 UID 探测 APK 安装退出码均为 0；`atomcode-16k-smoke.log`：当前包 12 项通过，无 FAIL。探测 APK 安装成功本身不是安全探测通过的证据。
- `atomcode-16k-security/foreign-uid-probe.txt`：7 项通过；同目录 `security-owner.txt`：2 项通过。`security-probe-summary.json` 记录设备 `emulator-5562`、7/2 项计数、未向探测端提供凭据和有限探测范围；协调器退出码 0。

前一宿主 `4b31953b0` / APK `580c500a468055ab7d6e04efba6034cfe7cddff3b0d5421460c1e96a776e12ee` 的补充证据继续保留：两端 `atomcode-recovery-final-{emulator,phone}-storage.log` 各 10 项；`atomcode-recovery-final-emulator-offline.log` 12 项；`atomcode-recovery-lifecycle-keyboard-{1,2}.log` 各 6 项及 monitor 观察。这些结果覆盖未改的安装/回收和布局代码，但不记成当前 APK 重新执行的专项。

同一旧包的 `atomcode-recovery-final-emulator-smoke.log` 曾在 10 项通过后停止失败，报告“不能确认旧引擎是否已退出”；该失败不删除，也不记为 12 项通过。当前包增加进程终止期间身份复核后，已通过上述 3 次完整在线运行。更早手机 UI 渲染/键盘初始化的锁屏失败也保留，仍需要当前包解锁后的实际 UI 证据。

测试使用真实 PRoot、Linux daemon、TLS 网关、WebView、PTY 和设备文件系统，没有用远程引擎替代本机执行。12 项 smoke 覆盖自动启动/健康哈希、TLS/Cookie、错误证书拒绝、401/跨 Origin 拒绝、真实命令/Git/退出码、revision 文件编辑、WebView、全屏、键盘缩放、显式停止及重启/代际轮换。离线通过不代表离线模型推理。

历史产物 `a2483445cde4fc4b6171f0da15413b5d8c2239edbeeac6df1c52de2e98fcdaf5` 曾通过真机 12 项 smoke 和 5 项局部布局检查。它们是旧 APK 证据，**不能证明当前包已通过真机 UI 复测**。

## 安装、损坏恢复与回收

当前包两端各 10 项存储测试均使用可删除的新缓存根和真实内置资产，不修改用户主目录或模型凭据，覆盖：

- 安装验证；取消提取后旧指针/环境/主目录保留，staging 清理；注入零可用空间预检；daemon 损坏检测/修复。
- 逐个损坏 env、真实路径 bash、动态加载器、libc 和 libtinfo 后启动校验拒绝；重新安装修复非 daemon 损坏且保留主目录。
- 多次成功安装后保留当前环境与上一个已完成指针，仅回收更旧的宿主管理目录；清理失败可观察，已提交环境仍有效，维护恢复后可重试。
- `.gc-staging` 缺少 `.ready` 的模拟部分删除可以续清；顶层槽为符号链接/非目录时拒绝处理；内部链接删除不跟随外部目标。
- ZIP 往返保留 UTF-8、空目录和执行位，重复导入不覆盖项目；截断/路径穿越拒绝且无残留 staging。

关键文件的期望摘要从完整 SHA-256 校验后的 APK rootfs ZIP 派生，并按资源哈希缓存。`isCurrent()` 检查这 5 个真实路径文件、执行权限和关键 Linux 链接；权限/链接要求对应当前锁定的 ARM64 Linux 布局，daemon 另行验证。这不是全部 Linux 文件完整性校验。DNS 刷新的 `rootfs()` 不解压源 ZIP、不遍历整棵环境目录。

`runtime/retention` 先持久化并绑定新的 active 名称；只有 current 成功提交且与记录匹配后才回收。旧目录先原子移动到固定宿主槽 `.gc-staging` 再删除。未知名称、坏 marker 和顶层符号链接不作为旧环境删除，主目录不参与回收。previous 是上一个**已完成的指针**，不代表全部旧字节在本次重新验证，也不代表自动回滚通过。

维护失败经 `maintenanceWarning()` 和安装进度提示呈现，不把已提交环境误报为安装失败，后续有效启动重试。取消回调、零可用空间和部分 GC 槽均为受控注入/构造，**不代表实际磁盘写满、进程强杀或断电验收**。

ZIP 输入和展开内容各限 2 GB，需为两者预留临时空间。中央目录必须完整，条目大小/CRC 通过后才提交项目；截断回归在旧实现上失败、修复后通过。

## 独立 UID 探测范围

当前包在已测三种环境（4 KB 真机、4 KB 模拟器、本次 16 KB 模拟器）各用不同 UID 的探测 APK 实际证明 7 项：UID 与主应用不同；访问运行中私有 Unix socket 被 `EACCES` 拒绝；未认证 sessions 请求拒绝；外来 Origin 拒绝；伪造 Host 拒绝；不能绑定网关当前占用的回环端口；默认平台 TLS 信任拒绝应用自签名证书。每种环境的宿主在探测前后均能认证访问健康的私有 socket，另有 2 项通过；检查同时正向核对了实际运行父进程的命令行没有 daemon token 赋值。

探测未获得或打印 token、Cookie、模型凭据。已占用端口绑定失败不等于启动前抢占/伪造监听者竞态验收；这些有限测试不证明所有恶意应用、上传、外部导航或备份攻防均已覆盖。

## 键盘、旋转与 WebView

模拟器专项用真实输入框点击、系统键盘可见性及 WebView/键盘几何位置，检查竖屏输入、横屏和返回竖屏、临时长文本滚动、同一代际及工作区 canary。当前包重跑 6 项通过；此前宿主包连续两次各 6 项通过的记录另行保留。`retained before tap=false` 表明旋转后重新点击恢复键盘再验证不遮挡，**不证明键盘在重建期间始终显示**，也不证明未发送草稿持久化。

新观察证明：有新 Activity 创建/恢复并稳定进入请求方向，但 `ActivityMonitor` 未投递实例。测试改用生命周期和稳定配置共同证明重建，未改产品布局，也未放宽重建、输入框可见和同代际要求。最早旧包那次无细分观察的横屏失败仍不能追溯定因，不能直接归为已定位的 monitor 漏报。

当前手机 UI 因锁屏未通过，解锁后须重跑。此前仅模拟器验证禁用/缺失 WebView 后进程存活、原生升级提示可见且无 WebView 节点，组件已恢复。最低语法目标 Chromium 99，旧/未知版本另有 JVM 测试；这些不是 Android 8 实机证据。

## 本地检查与源码清单

- 当前 APK 修正终止中的进程身份检查：有界重试并复核 UID、启动时间和状态，不把单个 `/proc` 成员不可读直接当作进程已消失，无法确认时继续拒绝；控制凭据通过允许的环境变量传递，不放入长期存活进程的 argv。受控诊断只含阶段、异常类型/errno 和状态布尔值，不含命令行、环境内容或凭据。
- 最新 JVM XML 合计 **31 项通过**，0 failures、0 errors、0 skipped，其中新进程所有权回归 9 项；本地调试构建和 lint 通过，主应用 4 warnings、探测应用 2 warnings。
- 本轮格式检查退出 0，`Cargo.lock` 中 `hkdf`、`hmac`、`zeroize_derive` 三项禁包数量均为 0。
- Python 运行环境/源码清单此前 12 项通过；相关实现本轮未改，不称为本轮重新执行。
- 此前对应基线的 WebUI 构建、类型检查和 402 项测试；daemon 402 项 nextest；coding 1,245 项通过、10 项跳过；`bash gates/compile.sh` 和格式门均有通过记录。本轮未改 Rust、Cargo 清单/锁或 Rust 门，沿用未变源码证据，不称为本轮重新执行。
- 此前 npm 官方 audit 从旧锁的 8 项降至 0 项；不代表当前不存在漏洞。
- 经校验输入的清单有 117 个 Ubuntu 二进制包（82 组源码包/版本）和 3 个 Termux 原生组件，反映实际 overlay 而非 Base 的旧 dpkg 状态。完整对应源码归档/许可交付仍未完成。
- Node 构建预检为 22.12+。全部编译在本机，源码发布跳过 CI，不把 APK 放入代码仓库；GitHub 默认英文、GitCode 默认中文，两边保留两种语言。

## 模型与平台阻塞

AtomGit 登录后的真实模型请求失败。共享 provider factory 返回 `SourceBuildGatewayUnsupported`：公开源码没有官方 CodingPlan 闭源请求签名实现，登录不能补齐该构建能力。用户选择暂不配置兼容模型服务，先交付 APK 并明确标注模型未通过。

**真实模型流式回复、模型驱动审批/拒绝、取消及完整对话恢复均未通过验收。** 空会话 metadata 保留不是真实对话恢复；上游隐藏空会话，测试仅直接解析 native session ID 检查 metadata。

官方 API 36 的 Google APIs 16 KB ARM64 镜像 revision 7 已安装并启动，运行时页大小实测 `16384` bytes；当前 APK 的 12 项本机 smoke、独立 UID 7 项及宿主 2 项在该环境通过。这是明确的单一 16 KB 模拟器执行证据，不再只有 ELF LOAD 静态对齐。尚不能据此声明 16 KB 真机、其他 16 KB 镜像、独立存储/旋转专项或真实模型验收通过。Android 8、更多厂商、IPv6/外部 HTTPS、休眠网络恢复和真实模型仍未验收。

## 最终验收矩阵

| 目标 | 已有证据与开放项 |
| --- | --- |
| 1 可复现交付 | 固定源码、锁、依赖哈希、ELF 审计和源码清单已提供；干净机器重建、正式签名和完整对应源码/许可交付未完成 |
| 2 安装启动 | 当前包 4 KB 模拟器连续 3 次在线各 12 项、两端各 10 项存储通过；新增官方 16 KB 模拟器安装及 12 项 smoke 通过；旧包离线证据单独保留；实际磁盘耗尽、升级硬中断/强杀及当前手机 UI 复测未完成 |
| 3 本机执行 | 当前包在已测 4 KB/16 KB 环境的实际 PRoot/daemon、PTY/Git/文件有证据；此前模拟器断网本机流程保留，当前包离线专项和手机离线专项未重跑 |
| 4 安全认证 | 固定证书、Cookie/401/跨源和三种已测 4 KB/16 KB 环境各 7+2 项独立 UID 检查有证据；启动前抢占/伪造服务、上传、备份和完整攻防未完成 |
| 5 核心工作流 | 本机终端与文件操作通过；真实模型及模型驱动工具/取消未通过 |
| 6 会话数据 | 当前模拟器工作区及空会话 metadata 重启保留；当前两端取消重装、损坏修复和归档 API 通过；**真实对话恢复从未通过**，硬中断、系统文件选择和强杀恢复待验收 |
| 7 手机可用性 | 当前包模拟器 smoke 全屏/键盘和 6 项键盘/旋转专项通过；手机解锁 UI 尚未重跑，旧包结果不能替代；完整输入/草稿/代码块/文件选择矩阵未完成 |
| 8 生命周期 | 旧包在线停止失败保留；新进程身份复核有 9 项 JVM 回归及当前模拟器连续 3 次完整启停通过；长时间后台、强制停止和厂商行为未完成 |
| 9 兼容网络 | 有 Android 14 真机/Android 16 模拟器的 4 KB 证据，新增官方 API 36 ARM64 镜像 revision 7 的实际 16 KB 启动、12 项 smoke 及 7+2 项独立 UID 检查；不代表所有 16 KB 设备，Android 8、多厂商、16 KB 其余专项及完整网络矩阵未完成 |
| 10 验证证据 | 当前哈希、源码和通过/失败/阻塞记录分开保留；不能宣布全部最终验收完成 |
