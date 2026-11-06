# AtomCode-Go 源码与许可资料

当前候选 APK `f67bbd8c…` 由源码 `bbec500c1` 构建，保留原有 327 份依赖许可原文的索引，包内缺失计数仍为 26 项（20 Cargo、6 npm）。基线 `28b2a612…` / `2cebadbc0` 单独保留。下述源码/许可材料为分离补充，未注入 APK 原文索引；10 个外部版本取得原文、3 个未解决不抵消原有 26 项缺失。运行环境、手机 UI 和真实模型限制见 [VERIFICATION.zh-CN.md](VERIFICATION.zh-CN.md)。

## 许可原文补充资料

[补充 manifest](../../android/runtime/supplemental-notices/manifest.json) 记录 13 个外部包版本，其中 10 项 `resolved`、3 项 `unresolved`。这里的 `resolved` 仅表示已取得并关联版本的许可原文，不保证完整许可集合、法律合规或完整对应源码。9 份去重原文及支持元数据均按原始字节保留。

manifest 关联产物版本/摘要、上游固定 commit、原始路径、请求/最终 URL 和原文摘要，区分 npm 实际包名与 alias 安装路径，并把 `napi-wasm` 记录为 `lightningcss-wasm` 父产物内的 bundled 子包。`boolbase`、`dlv`、`napi-wasm` 保持未解决；SPDX 声明或通用 MIT/ISC 模板不能替代缺失原文。13 个 workspace 包仅引用已有[仓库 MIT 许可](../../LICENSE)，不冒充 13 份独立许可文件。

在仓库根目录离线执行：

```sh
python3 android/runtime/verify_supplemental_notices.py
```

校验检查随附字节及记录的锁/元数据/源码关联，不重新散列补充资料中未包含的二进制归档，也不认证发布者签名。本补充资料尚未接入 APK 打包流程。

## Ubuntu 精确版本源码试点

[source_archives.py](../../android/runtime/source_archives.py) 提供两种操作：`plan` 对已取得的 `.dsc` 离线生成固定清单，核对其中 Source/Version 与 `source-inventory.json`，记录归档大小和 SHA-256；`fetch` 接收单个固定 manifest，下载或复核描述文件和源码归档，不解压、不执行。

| 源码包 | 清单中的精确版本 | 固定 manifest |
| --- | --- | --- |
| Git | `1:2.43.0-1ubuntu7.4` | [git.json](../../android/runtime/source-manifests/git.json) |
| Bash | `5.2.21-2ubuntu4` | [bash.json](../../android/runtime/source-manifests/bash.json) |

这两组已实际取得：每个包各有 `.dsc`、上游 `orig` 归档和 Debian 打包归档，共 6 文件、**13,878,273 字节**。收据位于 `dist/android/source-archives/`。在仓库根目录获取或复核单组的示例：

```sh
python3 android/runtime/source_archives.py fetch \
  --manifest android/runtime/source-manifests/git.json \
  --out dist/android/source-archives
```

Bash 使用相同命令，仅把参数替换为 `--manifest android/runtime/source-manifests/bash.json`。`plan` 还可用 `--version` 选择清单中的精确版本，适用于当前 OpenSSL 同时存在的 `3.0.13-0ubuntu3.15`、`3.0.13-0ubuntu3.16`。收据名称为 `<package>-<sha256(version)>-source-receipt.json`，不同版本不会互相覆盖；本试点尚未取得任一 OpenSSL 源码组。

记录的归档大小和 SHA-256 与固定描述文件一致。描述文件含 PGP 签名封装，但 `signatureVerified` 仍为 **false**：摘要一致与独立 OpenPGP 认证是不同结果。当前两组仅覆盖 Ubuntu 清单 82 组源码包/版本中的 **2/82**；另行取得的 3 个原生上游归档和 Termux 框架也不补齐整个产品的对应源码集合，`completeCorrespondingSourceDelivered` 仍为 false。

## npm 产物固定

WebUI 锁已为此前缺失的 138 个独立条目补齐 `resolved` 和 SRI。原有 40 个 SRI 均与官方 `registry.npmjs.org` 精确版本元数据一致；8 个已有镜像 URL 保留，其摘要与官方产物相同。锁覆盖 178 个独立产物，以及 1 个通过父产物取得的 bundled 子包，包版本和依赖关系保持不变。

当前 `webui/package-lock.json` SHA-256：

```text
9f77410695db6bf9bc52b2a74d17c96e389fb5bbe8b1298109472f20a6523db1
```

补 pin 后 WebUI 构建、类型检查和 402 项测试通过，94 个生成文件的 SHA-256 均与此前相同。该次锁更新步骤仅比较 WebUI 输出；当前 f67 APK 随后使用该固定锁实际编译，来源在验收记录单独列出。离线命令 `python3 android/runtime/verify_frontend_lock.py` 已验证 178 个带摘要条目和 1 个 bundled 子包；`build-local.sh` 在下载或编译前执行它。真实旧 `2ce` 锁被拒绝，driver 测试确认拒绝前没有创建 venv 或运行构建工具，8 项检查器测试通过。产物 pin 与源码试点不改变模型可用性，也不证明完整产品源码交付。

## 原生依赖源码资料

[原生源码 pin 清单](../../android/runtime/source-manifests/native.json) 登记四份独立取得的归档：PRoot `5.1.107.102`（552,401 字节）、talloc `2.5.0`（690,172 字节）、libandroid-shmem `0.7`（8,891 字节），以及固定在 commit `b734bc49dc0eedb2c454e1c5d3c48d35d81d4bc9` 的 Termux 框架（9,628,066 字节）。三个上游源码归档合计 **1,251,464 字节**。manifest 记录 URL、大小、SHA-256 和三个固定 aarch64 二进制输入的版本/摘要。其 `pinned-termux-native-source-materials` kind 与 Ubuntu `.dsc` 格式不同，`source_archives.py` 不负责获取这份清单。

框架快照保留全部 **10,769 个 tracked blob**：归档内 10,767 个与 Git 原字节一致，两个 PowerShell 文件按 `.gitattributes` 导出为 CRLF，另行保留固定 commit 的原始 LF 文件。选定配方目录包含四个 recipe/helper 文件（含 `termux-chroot`），没有独立补丁文件。这覆盖框架的跟踪源码，不包含 Git 历史或编译器/容器环境；版本和摘要匹配也不证明原始预编译二进制的构建过程。

归档、许可原文、元数据、本地 ELF 修改实现及 `receipt-20261106T182110Z-73b525e0.json` 通过 `dist/android/native-source-archives/` 交付，这些交付文件不提交 Git。对应 `SHA256SUMS-20261106T182110Z-73b525e0` 有 **35 个校验项**，不包含校验文件自身；跟踪的 manifest 保留可复取 pin。

talloc 配方记录 `GPL-3.0`，依赖元数据记录 `LGPL-3.0-or-later`，保留的上游 `LICENSE` 和完整 `talloc.h` 则提供独立的 LGPL 原文证据。仅记录这些差异，不推导法律结论。签名尚未独立认证，原生二进制未重新编译，整个产品的完整对应源码仍未交付。
