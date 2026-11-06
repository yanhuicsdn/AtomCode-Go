# AtomCode-Go

**把 Android 手机变成本机 AI 编程工作区。**

[简体中文](README.zh-CN.md) · [English](README.en.md) · [本地构建](docs/android/BUILD.zh-CN.md) · [验收记录](docs/android/VERIFICATION.zh-CN.md)

AtomCode-Go 将 AtomCode 的 Rust 引擎和 WebUI 装进 Android 应用，参考 [dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) 的本机运行方式，通过 PRoot 在手机上运行内置 Linux 环境。代码、命令进程和会话保存在手机中，模型推理由你配置的模型服务提供。

## 产品能力

- 打开应用后自动安装或启动内置本机环境，无需手动点击启动。
- 就绪后直接进入全屏编程界面；安装进度和可重试的错误使用原生状态界面展示。
- 复用 AtomCode 的流式对话、工具审批、文件编辑、终端和原生会话存储。
- 用户主目录与可替换的运行环境分离，环境更新时保留会话、模型设置和项目。
- 停止引擎后可导入、导出工作区 ZIP，检查归档路径，不静默覆盖已有项目。
- daemon 使用应用私有 Unix socket，WebView 通过固定本应用证书的回环 TLS 网关访问，并使用经过认证的 HttpOnly Cookie。
- 前台服务管理引擎进程，可从 Android 通知栏停止；系统后台限制仍然适用。

正常使用无需 root、Termux、电脑或远程 AtomCode 服务器。在线模型调用需要网络和用户自己的模型服务配置。

## 当前交付状态

当前是开发测试版本，全部最终验收尚未完成。当前 ARM64 候选包 `f67bbd8c…` 由源码 `bbec500c1` 在本机构建，已在**实测 4 KB 页的 AOSP Android 16 ARM64 模拟器**通过离线本机运行 12 项、上传适配器 7 项、独立 UID 7 项及宿主 2 项、活跃包 LocalTransport 备份拒绝，以及两份固定公开 PNG 的真实跨 UID 读取。在**官方 Android 16 ARM64 16 KB 模拟器**完成基线 `28b2a612…` 到当前候选包的真实提交前升级强杀，自动恢复后 12 项 smoke 通过；当前包额外的 16 KB 严格离线 12 项、独立 UID 7+2 和 chooser 7 组已通过；真实双 PNG 跨 UID FileReader/撤权也已通过；未在该 Google APIs 镜像测试备份。测试没有发送模型请求；拦截 picker 返回结果不证明实际 DocumentsUI 手选或模型附件上传。

**Android 14 的 Up Mobile Mover Edition 真机**仍为基线 APK `28b2a612…`，此前升级、安全和隔离存储通过记录保留为基线证据；当前 `f67bbd8c…` 候选包尚未安装到该手机或完成手机 UI 验收。应用声明最低支持 Android 8.0/API 26，Android 8 真机、更多厂商和 16 KB 真机仍未通过验收。首版沿用参考项目的 `targetSdk 28` 私有目录执行方案，面向侧载，不属于可直接提交 Play Store 的发行包。精确产物、失败和开放项见[验收记录](docs/android/VERIFICATION.zh-CN.md)。

### 模型认证限制

**源码构建不含 AtomCode 官方 CodingPlan 所需的闭源请求签名器。AtomGit 登录成功，也不能因此使用官方 CodingPlan 模型。** 请在“模型配置”中添加自己的兼容模型服务和 API Key。界面会明确提示缺失的构建能力。官方模型支持需要实际的官方签名组件，开启空的 feature 无法获得该能力。

当前开发交付保留模型验收未通过状态。真实模型流式回复、模型驱动审批/取消及真实对话恢复仍需兼容服务和实际端到端验证；本机引擎测试通过，不等于真实模型调用通过。

## 本地构建

编译、打包和测试全部在本机执行，不使用 GitHub Actions 或远程构建机器。

依赖：Rust 1.95.0、Node.js 22.12.0+、Python 3.9+、JDK 17+，以及包含 platform 36 和兼容 build-tools 的 Android SDK。

```bash
export JAVA_HOME=/absolute/path/to/jdk17/Contents/Home
export ANDROID_HOME=/absolute/path/to/android-sdk
bash android/build-local.sh
```

产物位于 `dist/android/`，包括 `atomcode-go-arm64-debug.apk`、设备测试 APK、哈希和运行环境构建报告。生成的 APK、工具链、凭据和大型运行环境不进入 Git。安装、签名与测试命令见[构建说明](docs/android/BUILD.zh-CN.md)。

## 实现结构

```text
Android Activity + 前台服务
  ├─ 校验安装器 → 不可变 Linux 运行环境
  ├─ PRoot → AtomCode daemon → 原有 coding runtime
  ├─ 私有 Unix socket ↔ 本机 TLS 网关 ↔ 全屏 WebView
  └─ 独立用户主目录 → 会话、模型设置、工作区
```

Android 负责安装、进程生命周期和展示，AtomCode 保持 agent 与会话状态的唯一所有权，不另建第二个 agent 或对话存储。

## 数据与发行

数据位于应用私有目录，卸载应用会删除它们，请提前导出工作区。更新运行环境时保留独立用户主目录。覆盖升级 APK 需要兼容的签名密钥和版本号。

当前本地 APK 使用 Android 调试签名。正式公开发行前，还需完成发行签名、完整对应源码与许可交付，以及剩余设备矩阵验收。

## 源码与说明

- [最终目标与验收标准](docs/android/GOAL-AND-ACCEPTANCE.zh-CN.md)
- [构建说明：中文](docs/android/BUILD.zh-CN.md) / [English](docs/android/BUILD.en.md)
- [验收记录：中文](docs/android/VERIFICATION.zh-CN.md) / [English](docs/android/VERIFICATION.en.md)
- [架构约束](AGENTS.md)与[引擎架构](docs/architecture.md)
- [GitHub 仓库](https://github.com/yanhuicsdn/AtomCode-Go)
- [GitCode 仓库](https://gitcode.com/superyan/AtomCode-Go)

Android 宿主参考 dsh-Go 的架构独立实现，没有复制其代码或品牌素材。AtomCode 引擎及本仓库采用 [MIT 许可证](LICENSE)；分发的运行依赖保留各自许可证，包括 PRoot 的 GPL-2.0、talloc 的 LGPL-3.0-or-later。版本、摘要与源码地址记录在[运行环境依赖清单](android/runtime/dependencies.json)中。

工作区要求 Android System WebView 99 或更新版本；组件缺失或不兼容时显示原生升级提示。其余系统和模型验收状态见验收记录。
