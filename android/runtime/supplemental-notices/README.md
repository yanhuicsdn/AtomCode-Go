# Supplemental dependency notices / 第三方许可补充资料

This source/notice supplement preserves original notice bytes for 10 external package versions. Three external packages remain unresolved; 13 workspace packages reference the existing repository MIT license. `resolved` means a version-associated original text was obtained, not that complete corresponding source has been delivered. This supplement does not change the verified APK or its 327-text notice index.

本补充资料保留 10 个外部包版本的许可原文；另有 3 个外部包保持未解决，13 个 workspace 包只引用仓库已有 MIT 许可。`resolved` 仅表示已取得并关联版本的原文，不代表完整对应源码已经交付。本资料不改变已验证 APK 或其中的 327 份原文索引。

Run from the repository root, without network access:

```sh
python3 android/runtime/verify_supplemental_notices.py
```

The verifier checks the supplied bytes, lock/metadata identities and recorded source relationships. Binary archives are not included or rehashed by this offline check; publisher signatures, legal compliance and complete corresponding source are outside its result. Original URLs, final URLs and hashes remain in `manifest.json`; evidence responses are preserved under `evidence/`.

离线校验检查随附字节、锁/元数据身份及记录的来源关系，不重新散列未随附的二进制归档，也不认证发布者签名、法律合规或完整对应源码。原始 URL、最终 URL 和摘要见 `manifest.json`，元数据原响应保存在 `evidence/`。
