# -*- coding: utf-8 -*-
"""构造 A1 安全闭环测试样本：三类恶意 + 合法对照。
样本只落本地 analysis/samples/security/，不进设备正式导入目录。
预期码与 tools/hapcheck.ps1 判定规则对齐。
"""
import os, shutil, zipfile, json, io, sys

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(BASE, "analysis", "samples", "security")
os.makedirs(OUT, exist_ok=True)

PANDA_MAGIC = b"PANDA" + b"\x00" * 58  # 前 64 字节含 PANDA 魔数
OLD_MAGIC = b"OLDABC" + b"\x00" * 59  # 非 PANDA 魔数：模拟老 fmt 字节码

def minimal_module(name, abis=None):
    return {
        "app": {"bundleName": f"com.test.{name}", "minAPIVersion": 9, "targetAPIVersion": 11},
        "module": {
            "name": name,
            "type": "feature",
            "deviceTypes": ["phone", "default"],
            "abilities": [{"name": "EntryAbility"}],
            "dependencies": [],
        },
    }

def write_hap(path, entries, module=None, abi_so=None, abc_bytes=PANDA_MAGIC):
    """entries: list of (name, bytes). abi_so: dict abi -> so bytes. abc_bytes: modules.abc 内容。"""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        if module is not None:
            z.writestr("module.json", json.dumps(module, ensure_ascii=False))
            z.writestr("modules.abc", abc_bytes)
        if abi_so:
            for abi, data in abi_so.items():
                z.writestr(f"libs/{abi}/libx.so", data)
        for name, data in entries:
            z.writestr(name, data)

manifest = []

# 1) 目录穿越
p = os.path.join(OUT, "sec-traversal-dir.hap")
write_hap(p, [("../../evil/evil.txt", b"pwned")], minimal_module("traversal"))
manifest.append(("sec-traversal-dir.hap", "SECURITY_REJECTED", "条目名含 ../ 目录逃逸"))

# 2) 绝对路径 / 盘符
p = os.path.join(OUT, "sec-traversal-abs.hap")
write_hap(p, [("/etc/evil.txt", b"abs"), ("C:\\win\\evil.txt", b"drive")], minimal_module("abs"))
manifest.append(("sec-traversal-abs.hap", "SECURITY_REJECTED", "绝对路径/盘符条目"))

# 3) 条目数超限：5001 个空条目（压缩后极小，不占实盘 GB）
p = os.path.join(OUT, "sec-too-many-entries.hap")
entries = [(f"rawfile/f{i}.txt", b"") for i in range(5001)]
write_hap(p, entries, minimal_module("many"))
manifest.append(("sec-too-many-entries.hap", "SECURITY_REJECTED", "条目数 > 5000"))

# 4) 压缩炸弹：12MB 零字节条目（DEFLATE 压到极小，解压比 >100）
p = os.path.join(OUT, "sec-bomb.hap")
write_hap(p, [("rawfile/fat.bin", b"\x00" * (12 * 1024 * 1024))], minimal_module("bomb"))
manifest.append(("sec-bomb.hap", "SECURITY_REJECTED", "解压体积/压缩比超限"))

# 5) 错 ABI：仅 x86_64 占位 so，白名单无交集 -> ABI_MISSING（兼容判定，非安全拒绝）
p = os.path.join(OUT, "sec-bad-abi.hap")
write_hap(p, [], minimal_module("badabi"), abi_so={"mips": b"\x7fELF"})
manifest.append(("sec-bad-abi.hap", "ABI_MISSING", "包内 ABI 不在白名单"))

# 6) 老字节码：abc 存在但魔数非 PANDA -> BYTECODE_UNSUPPORTED（兼容判定）
p = os.path.join(OUT, "compat-bad-magic.hap")
write_hap(p, [], minimal_module("oldmagic"), abc_bytes=OLD_MAGIC)
manifest.append(("compat-bad-magic.hap", "BYTECODE_UNSUPPORTED", "abc 魔数不在当前引擎支持列表"))

# 合法对照：从现有 OK 样本复制
samples_dir = os.path.join(BASE, "analysis", "samples")
for src_name, label in [("AudioPicker.hap", "legal-normal")]:
    src = os.path.join(samples_dir, src_name)
    if os.path.exists(src):
        dst = os.path.join(OUT, f"{label}.hap")
        shutil.copy2(src, dst)
        manifest.append((f"{label}.hap", "OK", "合法正常包对照（不误杀）"))

# 阈值边界：条目数 5000、体积在限内的合法包
p = os.path.join(OUT, "legal-edge.hap")
entries = [(f"rawfile/f{i}.txt", b"x") for i in range(4998)]
write_hap(p, entries, minimal_module("edge"))
manifest.append(("legal-edge.hap", "OK", "条目数恰在上限内（边界对照，含 2 骨架条目共 5000）"))

print("=== 样本构造清单 ===")
for fname, code, note in manifest:
    size = os.path.getsize(os.path.join(OUT, fname))
    print(f"{fname:28} -> {code:20} {size:>9} B  {note}")

# 写预期码清单，供预言机对照
with open(os.path.join(OUT, "expected.txt"), "w", encoding="utf-8") as f:
    for fname, code, note in manifest:
        f.write(f"{fname}\t{code}\t{note}\n")
