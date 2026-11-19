# HAP 兼容矩阵

生成时间: 2026-11-19 02:55  样本数: 7

| 文件 | 判定码 | 等级 | 包名 | 模块 | 类型 | ABI | 字节码 | 设备类型 | 依赖 | 签名 | 依据 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| legal-edge.hap | OK | 可运行 | com.test.edge | edge | feature |  | PANDA | phone|default |  | unsigned |  |
| legal-normal.hap | OK | 可运行 | com.ohos.filepicker | audiopicker | feature |  | PANDA | default|tablet |  | unsigned |  |
| sec-bad-abi.hap | ABI_MISSING | 明确不支持 | com.test.badabi | badabi | feature | mips | PANDA | phone|default |  | unsigned | mips |
| sec-bomb.hap | SECURITY_REJECTED | 明确不支持 | - | - | - |  | - | - |  | unknown | 疑似压缩炸弹：rawfile/fat.bin |
| sec-too-many-entries.hap | SECURITY_REJECTED | 明确不支持 | - | - | - |  | - | - |  | unknown | 条目数超过上限 5000 |
| sec-traversal-abs.hap | SECURITY_REJECTED | 明确不支持 | - | - | - |  | - | - |  | unknown | 条目路径越界：/etc/evil.txt |
| sec-traversal-dir.hap | SECURITY_REJECTED | 明确不支持 | - | - | - |  | - | - |  | unknown | 条目路径越界：../../evil/evil.txt |
