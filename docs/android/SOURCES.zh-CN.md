# AtomCode-Go 源码与许可资料

已验证 APK 仍为源码 `2cebadbc0` 构建的 `28b2a612…`，内含原有 327 份依赖许可原文的索引保持不变。下述源码/许可资料是独立补充，没有重新编译或替换该 APK。运行环境、手机 UI 和真实模型等限制见 [VERIFICATION.zh-CN.md](VERIFICATION.zh-CN.md)。

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

记录的归档大小和 SHA-256 与固定描述文件一致。描述文件含 PGP 签名封装，但 `signatureVerified` 仍为 **false**：摘要一致与独立 OpenPGP 认证是不同结果。当前两组仅覆盖 Ubuntu 清单 82 组源码包/版本中的 **2/82**，不是完整对应源码交付，`completeCorrespondingSourceDelivered` 仍为 false。

## npm 产物固定

WebUI 锁已为此前缺失的 138 个独立条目补齐 `resolved` 和 SRI。原有 40 个 SRI 均与官方 `registry.npmjs.org` 精确版本元数据一致；8 个已有镜像 URL 保留，其摘要与官方产物相同。锁覆盖 178 个独立产物，以及 1 个通过父产物取得的 bundled 子包，包版本和依赖关系保持不变。

当前 `webui/package-lock.json` SHA-256：

```text
9f77410695db6bf9bc52b2a74d17c96e389fb5bbe8b1298109472f20a6523db1
```

补 pin 后 WebUI 构建、类型检查和 402 项测试通过，94 个生成文件的 SHA-256 均与此前相同。这是 WebUI 输出比较，没有重新编译 APK。离线命令 `python3 android/runtime/verify_frontend_lock.py` 已验证 178 个带摘要条目和 1 个 bundled 子包；`build-local.sh` 在下载或编译前执行它。真实旧 `2ce` 锁被拒绝，driver 测试确认拒绝前没有创建 venv 或运行构建工具，8 项检查器测试通过。产物 pin 与源码试点不改变模型可用性，也不证明完整产品源码交付。
