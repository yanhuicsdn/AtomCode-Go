# AtomCode-Go 项目接手说明

本项目基于 AtomCode `c4a2fbfceb5ee99907d875d3b6ec604a495cc219`，参考 dsh-Go `cf2f7c861e1098661a3a2039b3a4cb1633790102` 的 PRoot + Linux 本机运行方式。Android 宿主已实现，不再只是方案仓库。

## 当前实现

`android/` 包含本地构建、锁定依赖的运行环境打包、校验安装器、独立用户主目录、前台服务、进程清理、私有 Unix socket/TLS 网关、WebView 和工作区 ZIP 导入导出。daemon 继续使用原有 coding runtime 与 session store。产品名为 AtomCode-Go，打开自动安装/启动，就绪后全屏。

所有编译和测试在本机执行。GitHub 默认英文 README，GitCode 默认中文 README；两端包含中英文文档。APK、签名密钥、用户凭据、工具链及大型 payload 不进入 Git。

## 验证与限制

本次 APK 已通过 Android 14 ARM64 真机及 Android 16 ARM64 模拟器的 12 项本机 smoke test，包括更名、自动启动、全屏、输入法和快速重启。模拟器断网后同样通过本机检查。详见 [验收记录](VERIFICATION.zh-CN.md)，不能把首轮产物结果冒充新版或全部设备验收。

官方 CodingPlan 依赖未公开的签名组件。源码构建登录 AtomGit 后仍无法调用官方模型，需要配置自己的兼容模型 API。不要恢复占位签名、伪造认证或静默降级。

继续工作时先读 [最终目标与验收标准](GOAL-AND-ACCEPTANCE.zh-CN.md)、[本地构建说明](BUILD.zh-CN.md) 和根目录 `AGENTS.md`。保留用户主目录和模型配置，真机上不得清空 App 数据。补齐真实模型、Android 8、多厂商、16 KB、网络/生命周期/升级故障和正式发行证据后才能宣布最终验收通过。
