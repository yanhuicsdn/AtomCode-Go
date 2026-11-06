# Android 项目接手说明

## 当前状态

本仓库基于 AtomCode 上游 `c4a2fbfceb5ee99907d875d3b6ec604a495cc219`。Android 移植尚未实现：目前只有目标、验收标准和架构调研，没有 APK，也没有经过 Android 真机验证。请先阅读 [最终目标与验收标准](GOAL-AND-ACCEPTANCE.zh-CN.md) 和仓库根目录的 `AGENTS.md`，按目标继续完成实现，不能将现有 WebUI 测试当作 Android 验收。

参考项目为 <https://github.com/ccch1mneyyy/dsh-Go>，调研版本 `cf2f7c861e1098661a3a2039b3a4cb1633790102`。其本机引擎使用 PRoot 和 Linux 用户空间；AtomCode 现有 daemon 可提供内嵌 WebUI、token 认证和 Cookie 交接。Android 进程身份认证、运行环境与真机兼容性仍需设计和验证。复制参考项目代码时须先核对许可证。

## 已验证与未验证

在原机器上，`webui` 目录执行 `npm ci`、`npm run build`、`npm run typecheck` 和 `npm test`，构建与类型检查成功，402 个测试通过。`npm ci` 报告 8 个依赖漏洞（2 moderate、5 high、1 critical）；应在新环境重新审查实际影响，不能将扫描结果等同于已确认可利用。构建输出和依赖目录由 Git 忽略，需要在新机器重新生成。

原机器未检测到 Rust、Java、Android SDK 或已连接的 Android 设备；没有构建 Rust daemon、APK、PRoot 运行环境，也没有完成真机测试。设备和模型配置均未包含在仓库中。

## 建议的接续步骤

1. 在新机器克隆本仓库并安装项目要求的 Rust、Node.js、Java、Android SDK/NDK 及构建工具；核对 `AGENTS.md` 的构建和测试约束。
2. 参考 dsh-Go 的运行环境构建和 Android 启动机制，先验证包含 WebUI 的 ARM64 AtomCode Linux 构建能在 Android PRoot 中启动并完成本地认证。
3. 实现 Android 安装器、运行时管理、WebView、工作区和恢复机制，严格遵循目标文档中的安全与数据保护条件。
4. 按目标文档执行自动化、模拟器和 ARM64 真机验收，记录设备、APK 哈希及失败项；未验证的项目不得标记为通过。

给接手的编程代理的任务：**按照 `docs/android/GOAL-AND-ACCEPTANCE.zh-CN.md` 实现完整的 AtomCode Android 本机运行项目，持续执行适当验证，明确报告尚未通过的验收项。**
