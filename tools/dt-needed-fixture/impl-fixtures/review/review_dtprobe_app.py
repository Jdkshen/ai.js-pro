#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立复核 Lead 的 dt_needed 取证（.artifacts/dtprobe-app/）。

本人自己写的 ELF64 / ZIP 解析（不调用、不采信 Lead 的 check_elf.py / verify_patched.py，
也不采信 apk_inject_gadget 的自报），检查：
  ① APK 内 lib/arm64-v8a/libdtprobe.so 与输入 .so 大小是否一致
  ② 新增 DT_NEEDED 是否指向 libaijsgadget.so，且该串确实落在 .dynstr 区间内
  ③ DT_NULL 终止符是否保留
  ④ 变化字节是否**全部**落在实现语义声明的两个区间（dynstr 写入区 + dynamic 条目区）
  ⑤ 用 linker64 视角（PT_LOAD/PT_DYNAMIC/DT_*/RELRO/hash/reloc）判断该 .so 是否仍可加载
另外附带：APK 层面（压缩方式/对齐/签名/gadget 是否在位/清单关键串）与「orig → padded」步不搬字节的检查。
"""
import hashlib
import os
import struct
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = r"C:\Users\18101\Documents\ai-js-pro\.artifacts\dtprobe-app"
PADDED = os.path.join(BASE, "native-padded", "libdtprobe-padded.so")
ORIG = os.path.join(BASE, "native", "libdtprobe-orig.so")
APK = os.path.join(BASE, "out", "dtprobe-injected-ok.apk")
APK_INERT = os.path.join(BASE, "out", "dtprobe-injected-inert.apk")
LIB_ENTRY = "lib/arm64-v8a/libdtprobe.so"
GADGET_ENTRY = "lib/arm64-v8a/libaijsgadget.so"
SONAME = "libaijsgadget.so"
PT_LOAD, PT_DYNAMIC, PT_GNU_RELRO = 1, 2, 0x6474E552
SHT_STRTAB, SHT_DYNAMIC = 3, 6
# 标准 ELF dynamic tag（这一版是我按 glibc/elf.h 校过的，上一版把 20/23 标错了）
DT = {0: "NULL", 1: "NEEDED", 2: "PLTRELSZ", 3: "PLTGOT", 4: "HASH", 5: "STRTAB", 6: "SYMTAB",
      7: "RELA", 8: "RELASZ", 9: "RELAENT", 10: "STRSZ", 11: "SYMENT", 12: "INIT", 13: "FINI",
      14: "SONAME", 15: "RPATH", 16: "SYMBOLIC", 17: "REL", 18: "RELSZ", 19: "RELENT",
      20: "PLTREL", 21: "DEBUG", 22: "TEXTREL", 23: "JMPREL", 24: "BIND_NOW", 25: "INIT_ARRAY",
      26: "FINI_ARRAY", 27: "INIT_ARRAYSZ", 28: "FINI_ARRAYSZ", 29: "RUNPATH", 30: "FLAGS",
      0x6FFFFFF0: "VERSYM", 0x6FFFFFF9: "RELACOUNT", 0x6FFFFFFA: "RELCOUNT",
      0x6FFFFFFB: "FLAGS_1", 0x6FFFFFFC: "VERDEF", 0x6FFFFFFD: "VERDEFNUM",
      0x6FFFFFFE: "VERNEED", 0x6FFFFFFF: "VERNEEDNUM", 0x6FFFFEF5: "GNU_HASH"}

FAILS = []
WARNS = []


def ok(flag, label, detail=""):
    print("  [%s] %s%s" % ("PASS" if flag else "FAIL", label, ("  -> " + detail) if detail else ""))
    if not flag:
        FAILS.append(label + (" (" + detail + ")" if detail else ""))
    return flag


def warn(label, detail=""):
    print("  [WARN] %s%s" % (label, ("  -> " + detail) if detail else ""))
    WARNS.append(label + (" (" + detail + ")" if detail else ""))


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def sha256(b):
    return hashlib.sha256(b).hexdigest()


class Elf:
    def __init__(self, buf, label):
        self.buf = buf
        self.label = label
        self.ok_elf = buf[:4] == b"\x7fELF" and len(buf) >= 64
        self.cls = buf[4] if len(buf) > 5 else 0
        self.data = buf[5] if len(buf) > 6 else 0
        self.type = u16(buf, 16) if self.ok_elf else 0
        self.machine = u16(buf, 18) if self.ok_elf else 0
        self.phoff, self.shoff = (u64(buf, 32), u64(buf, 40)) if self.ok_elf else (0, 0)
        self.phentsize, self.phnum = ((u16(buf, 54), u16(buf, 56)) if self.ok_elf else (0, 0))
        self.shentsize, self.shnum, self.shstrndx = ((u16(buf, 58), u16(buf, 60), u16(buf, 62))
                                                     if self.ok_elf else (0, 0, 0))
        self.phdrs, self.shdrs = [], []
        # 防御：不是 ELF / 头越界时不要按垃圾值解析（Frida 的 config.so 就不是 ELF）
        if self.ok_elf and (self.phentsize < 56 or self.phoff + self.phnum * self.phentsize > len(buf)
                            or self.shentsize < 64 or self.shoff + self.shnum * self.shentsize > len(buf)):
            self.ok_elf = False
            self.phnum = self.shnum = 0
        for i in range(self.phnum):
            o = self.phoff + i * self.phentsize
            self.phdrs.append({"i": i, "type": u32(buf, o), "flags": u32(buf, o + 4),
                               "offset": u64(buf, o + 8), "vaddr": u64(buf, o + 16),
                               "filesz": u64(buf, o + 32), "memsz": u64(buf, o + 40),
                               "align": u64(buf, o + 48), "off_hdr": o})
        for i in range(self.shnum):
            o = self.shoff + i * self.shentsize
            self.shdrs.append({"i": i, "name_off": u32(buf, o), "type": u32(buf, o + 4),
                               "flags": u64(buf, o + 8), "addr": u64(buf, o + 16),
                               "offset": u64(buf, o + 24), "size": u64(buf, o + 32),
                               "link": u32(buf, o + 40), "entsize": u64(buf, o + 56)})
        self.names = b""
        if self.shstrndx < len(self.shdrs):
            sh = self.shdrs[self.shstrndx]
            self.names = buf[sh["offset"]:sh["offset"] + sh["size"]]
        for sh in self.shdrs:
            end = self.names.find(b"\x00", sh["name_off"])
            sh["name"] = self.names[sh["name_off"]:end].decode("utf-8", "replace")

    @property
    def loads(self):
        return [p for p in self.phdrs if p["type"] == PT_LOAD]

    @property
    def dynamic_segment(self):
        return next((p for p in self.phdrs if p["type"] == PT_DYNAMIC), None)

    def dyn_entries(self):
        off, size, entsize, src = 0, 0, 16, "none"
        sh = next((s for s in self.shdrs if s["type"] == SHT_DYNAMIC and s["size"] > 0), None)
        if sh is not None:
            off, size = sh["offset"], sh["size"]
            entsize = int(sh["entsize"]) if sh["entsize"] in (8, 16) else 16
            src = "SHT_DYNAMIC '%s' off=0x%x size=0x%x entsize=%d" % (sh["name"], off, size, entsize)
        elif self.dynamic_segment is not None:
            off, size = self.dynamic_segment["offset"], self.dynamic_segment["filesz"]
            src = "PT_DYNAMIC off=0x%x size=0x%x" % (off, size)
        out = []
        for i in range(size // entsize):
            o = off + i * entsize
            tag = u64(self.buf, o) if entsize == 16 else u32(self.buf, o)
            val = u64(self.buf, o + 8) if entsize == 16 else u32(self.buf, o + 4)
            out.append((tag, val))
        return out, off, size, entsize, src

    def dynstr(self, dynamic):
        sh = next((s for s in self.shdrs if s["type"] == SHT_DYNAMIC and s["size"] > 0), None)
        if sh is not None and sh["link"] < len(self.shdrs):
            link = self.shdrs[sh["link"]]
            if link["type"] == SHT_STRTAB and link["offset"] + link["size"] <= len(self.buf):
                return link["offset"], link["size"], "section .dynstr (sh_link)"
        strtab = next((v for t, v in dynamic if t == 5), None)
        strsz = next((v for t, v in dynamic if t == 10), None)
        if strtab is None or strsz is None:
            return 0, 0, "missing"
        mapped = self.vaddr_to_offset(strtab)
        if mapped is None:
            return 0, 0, "DT_STRTAB unmapped"
        return mapped, strsz, "DT_STRTAB/DT_STRSZ vaddr=0x%x -> file 0x%x" % (strtab, mapped)

    def vaddr_to_offset(self, addr):
        for p in self.loads:
            if p["vaddr"] <= addr < p["vaddr"] + p["filesz"]:
                return p["offset"] + (addr - p["vaddr"])
        return None

    def cstr(self, offset, limit=256):
        end = self.buf.find(b"\x00", offset, min(offset + limit, len(self.buf)))
        if end < 0:
            return None
        return self.buf[offset:end].decode("utf-8", "replace")


def eager(path):
    print("=" * 92)
    print("## %s" % path)
    buf = open(path, "rb").read()
    print("   size=%d sha256=%s" % (len(buf), sha256(buf)))
    e = Elf(buf, path)
    print("   ELF class=%d data=%d type=%d machine=0x%x phnum=%d shnum=%d" %
          (e.cls, e.data, e.type, e.machine, e.phnum, e.shnum))
    dynamic, dyn_off, dyn_size, entsize, src = e.dyn_entries()
    print("   dynamic: %s" % src)
    str_off, str_size, str_src = e.dynstr(dynamic)
    print("   dynstr : off=0x%x size=0x%x via %s" % (str_off, str_size, str_src))
    need = len(SONAME) + 1
    existing = -1
    for i in range(0, max(str_size - need + 1, 0)):
        if buf[str_off + i:str_off + i + len(SONAME)] == SONAME.encode() and buf[str_off + i + len(SONAME)] == 0:
            existing = i
            break
    last_nz = -1
    for i in range(str_size):
        if buf[str_off + i]:
            last_nz = i
    free = 0 if last_nz < 0 else last_nz + 2
    have = max(str_size - free, 0)
    print("   条件1: existing=%d lastNonZero=%d freeStart=%d have=%d need=%d -> %s" %
          (existing, last_nz, free, have, need, "PASS" if (existing >= 0 or have >= need) else "FAIL"))
    nulls = [i for i, (t, _v) in enumerate(dynamic) if t == 0]
    spare = next((i for i in nulls if i + 1 < len(dynamic) and dynamic[i + 1][0] == 0), None)
    print("   条件2: DT_NULL 下标=%s spareIndex=%s" % (nulls, spare))
    return buf, e


print("#" * 92)
print("# 独立复核：Lead 的 dtprobe 取证（本次复核不采信任何工具自报，全部字节级重算）")
print("#" * 92)

padded_buf, padded_elf = eager(PADDED)
orig_buf, orig_elf = eager(ORIG)

# ---------------------------------------------------------------- APK 内目标 .so
with zipfile.ZipFile(APK) as z:
    entries = {i.filename: i for i in z.infolist()}
    info = entries[LIB_ENTRY]
    out_buf = z.read(LIB_ENTRY)
    gadget_info = entries.get(GADGET_ENTRY)
    gadget = z.read(GADGET_ENTRY) if gadget_info else None
    config_info = entries.get("lib/arm64-v8a/libaijsgadget.config.so")
    config = z.read(config_info.filename) if config_info else None
    manifest = z.read("AndroidManifest.xml")
    names = z.namelist()

print()
print("=" * 92)
print("## APK 内 %s" % LIB_ENTRY)
print("   size=%d sha256=%s" % (len(out_buf), sha256(out_buf)))
print("   zip: method=%d compress_size=%d header_offset=%d" % (info.compress_type, info.compress_size, info.header_offset))
data_off = info.header_offset + 30 + len(info.filename) + len(info.extra)
print("   data offset=0x%x  %%4096=0x%x  %%16384=0x%x" % (data_off, data_off % 4096, data_off % 16384))
out_elf = Elf(out_buf, LIB_ENTRY)

print()
print("### ① 大小一致")
ok(len(out_buf) == len(padded_buf), "APK 内 .so 大小 == 输入 padded .so",
   "in=%d out=%d" % (len(padded_buf), len(out_buf)))
ok(len(out_buf) == len(padded_buf), "文件大小未改变（实现承诺 sizeUnchanged）")

print()
print("### ② 新增 DT_NEEDED 指向 %s 且串在 .dynstr 内" % SONAME)
dyn, dyn_off, dyn_size, entsize, dyn_src = out_elf.dyn_entries()
str_off, str_size, str_src = out_elf.dynstr(dyn)
print("   dynamic 源: %s" % dyn_src)
print("   dynstr  源: %s  off=0x%x size=0x%x" % (str_src, str_off, str_size))
needed = []
for i, (tag, val) in enumerate(dyn):
    if tag == 1:
        s = out_elf.cstr(str_off + val) if str_off + val < len(out_buf) else None
        inside = str_off <= str_off + val < str_off + str_size
        needed.append((i, val, s, inside))
        print("   [%2d] DT_NEEDED value(offset in strtab)=%d -> %r  串落在 dynstr 区间内=%s" % (i, val, s, inside))
strings = [n[2] for n in needed]
ok(SONAME in strings, "DT_NEEDED 含 %s" % SONAME, "实际=%s" % strings)
target = next((n for n in needed if n[2] == SONAME), None)
ok(bool(target) and target[3], "该 DT_NEEDED 的串确实落在 [dynstr, dynstr+size) 内",
   "" if not target else "offset=%d dynstr=[0x%x,0x%x)" % (target[1], str_off, str_off + str_size))
if target:
    abs_off = str_off + target[1]
    raw = out_buf[abs_off:abs_off + len(SONAME) + 1]
    ok(raw == SONAME.encode() + b"\x00", "字节级确认串内容", "%r @0x%x" % (raw, abs_off))
    # 与 padded 输入比较：这个 offset 在输入里是否也是同一个位置（说明没搬 dynstr）
    in_str_off, in_str_size, _ = padded_elf.dynstr(padded_elf.dyn_entries()[0])
    print("   输入 padded 的 dynstr: off=0x%x size=0x%x（偏移是否相同=%s）" %
          (in_str_off, in_str_size, in_str_off == str_off))

print()
print("### ③ DT_NULL 终止符保留")
nulls = [i for i, (t, _v) in enumerate(dyn) if t == 0]
ok(len(nulls) > 0, "dynamic 表里有 DT_NULL", "下标=%s" % nulls)
if target:
    after = [i for i in nulls if i > target[0]]
    ok(bool(after), "新增 DT_NEEDED 之后仍有 DT_NULL 终止符",
       "NEEDED 在 [%d]，其后的 DT_NULL 下标=%s" % (target[0], after))
    ok(after and after[0] == target[0] + 1, "终止符紧跟在新增条目之后（顺序合法）")
print("   dynamic 表（按 sh_size 计 %d 项）:" % len(dyn))
for i, (tag, val) in enumerate(dyn):
    label = DT.get(tag, "0x%x" % tag)
    extra = ""
    if tag == 1:
        extra = " -> %r" % out_elf.cstr(str_off + val)
    print("     [%2d] 0x%-14x %-9s 0x%x%s" % (i, tag, label, val, extra))

print()
print("### ④ 变化字节是否全部落在实现声明的区间内")
diff = [i for i in range(len(out_buf)) if out_buf[i] != padded_buf[i]]
print("   实际变化字节数=%d" % len(diff))
declared = []
if target:
    start = str_off + target[1]
    declared.append((start, start + len(SONAME)))          # 写入的串 + NUL
dyn_base = dyn_off + target[0] * entsize if target else None
if dyn_base is not None:
    declared.append((dyn_base, dyn_base + entsize - 1))    # dynamic 条目
print("   实现语义声明的区间（本脚本独立算出）: %s" %
      ", ".join("0x%x-0x%x" % d for d in declared))
outside = [i for i in diff if not any(lo <= i <= hi for lo, hi in declared)]
ok(not outside, "所有变化字节都在声明区间内",
   "越界字节=%s" % (["0x%x" % i for i in outside[:10]] if outside else "无"))
for lo, hi in declared:
    changed = [i for i in diff if lo <= i <= hi]
    print("     区间 0x%x-0x%x 内变化 %d 字节" % (lo, hi, len(changed)))
print("   变化明细（offset: in -> out）:")
for i in diff[:24]:
    print("     0x%-6x %02x -> %02x" % (i, padded_buf[i], out_buf[i]))
if len(diff) > 24:
    print("     ... 共 %d 处" % len(diff))

print()
print("### ⑤ linker64 视角：这个 .so 还能不能加载")
print("   ELF header 检查")
ok(out_elf.cls == 2, "ELFCLASS64")
ok(out_elf.data == 1, "ELFDATA2LSB（小端）")
ok(out_elf.type == 3, "e_type=ET_DYN（共享库）", "type=%d" % out_elf.type)
ok(out_elf.machine == 0xB7, "e_machine=AArch64", "0x%x" % out_elf.machine)
ok(out_elf.phentsize == 56, "e_phentsize=56")
ok(out_elf.shoff + out_elf.shnum * out_elf.shentsize <= len(out_buf), "节头表在文件内")
print("   PT_LOAD 表")
for p in out_elf.loads:
    flags = "".join(c for c, m in (("R", 4), ("W", 2), ("X", 1)) if p["flags"] & m)
    cong = (p["offset"] - p["vaddr"]) % p["align"] == 0 if p["align"] else False
    print("     [%d] off=0x%-6x vaddr=0x%-8x filesz=0x%-6x memsz=0x%-6x align=0x%-6x %s congruence=%s" %
          (p["i"], p["offset"], p["vaddr"], p["filesz"], p["memsz"], p["align"], flags, cong))
    ok(cong, "PT_LOAD #%d p_offset ≡ p_vaddr (mod p_align)" % p["i"])
    ok(p["filesz"] <= p["memsz"], "PT_LOAD #%d filesz <= memsz" % p["i"])
    ok(p["offset"] + p["filesz"] <= len(out_buf), "PT_LOAD #%d 文件范围在文件内" % p["i"])
loads_sorted = all(out_elf.loads[i]["vaddr"] < out_elf.loads[i + 1]["vaddr"] for i in range(len(out_elf.loads) - 1))
ok(loads_sorted, "PT_LOAD 按 vaddr 递增")
for a, b in zip(out_elf.loads, out_elf.loads[1:]):
    if a["vaddr"] + a["memsz"] > b["vaddr"]:
        warn("PT_LOAD #%d/#%d 虚拟地址区间重叠" % (a["i"], b["i"]))
dyn_seg = out_elf.dynamic_segment
print("   PT_DYNAMIC")
ok(dyn_seg is not None, "存在 PT_DYNAMIC")
if dyn_seg:
    print("     off=0x%x vaddr=0x%x filesz=0x%x memsz=0x%x" %
          (dyn_seg["offset"], dyn_seg["vaddr"], dyn_seg["filesz"], dyn_seg["memsz"]))
    ok(out_elf.vaddr_to_offset(dyn_seg["vaddr"]) is not None, "PT_DYNAMIC 落在某个 PT_LOAD 内")
    ok(dyn_seg["filesz"] % 16 == 0, "PT_DYNAMIC p_filesz 是 16 的倍数", "0x%x" % dyn_seg["filesz"])
    ok(dyn_seg["offset"] == dyn_off, "节表 .dynamic 与 PT_DYNAMIC 指向同一处", "section=0x%x seg=0x%x" % (dyn_off, dyn_seg["offset"]))
print("   DT_* 一致性")
ok(str_off > 0 and str_size > 0, "DT_STRTAB / DT_STRSZ 可解析")
ok(out_elf.vaddr_to_offset(next((v for t, v in dyn if t == 5), 0)) is not None,
   "DT_STRTAB 指向**已映射**区间（不在文件尾裸奔）")
# 注意：夹具**故意**留了备用槽，所以后面可能还有 DT_NULL；这里只要求「第一个 DT_NULL 之后全是 DT_NULL」
terminated = bool(nulls) and all(t == 0 for t, _v in dyn[nulls[0]:])
ok(terminated, "dynamic 表被 DT_NULL 正确终止（第一个 DT_NULL 之后没有非零条目）",
   "第一个 DT_NULL 下标=%s，全表 DT_NULL=%s（多出来的那个是备用槽，bionic 读到第一个就停）" %
   (nulls[0] if nulls else None, nulls))
symtab = next((v for t, v in dyn if t == 6), None)
syment = next((v for t, v in dyn if t == 11), None)
ok(symtab is not None and out_elf.vaddr_to_offset(symtab) is not None, "DT_SYMTAB 已映射")
ok(syment == 24, "DT_SYMENT=24", "实际=%s" % syment)
has_hash = any(t in (4, 0x6FFFFEF5) for t, _v in dyn)
ok(has_hash, "存在 DT_HASH 或 DT_GNU_HASH（bionic 符号查找需要）")
for t, v in dyn:
    if t in (4, 0x6FFFFEF5):
        ok(out_elf.vaddr_to_offset(v) is not None, "hash 表 (tag=0x%x) 已映射" % t)
rela = next((v for t, v in dyn if t == 7), None)          # DT_RELA
relasz = next((v for t, v in dyn if t == 8), None)        # DT_RELASZ
jmprel = next((v for t, v in dyn if t == 23), None)       # DT_JMPREL
pltrelsz = next((v for t, v in dyn if t == 2), None)      # DT_PLTRELSZ
pltrel = next((v for t, v in dyn if t == 20), None)       # DT_PLTREL
if pltrel is not None:
    ok(pltrel == 7, "DT_PLTREL == DT_RELA(7)", "实际=%d" % pltrel)
if jmprel is not None and pltrelsz:
    off = out_elf.vaddr_to_offset(jmprel)
    ok(off is not None and off + pltrelsz <= len(out_buf), "DT_JMPREL 区间已映射且在文件内",
       "vaddr=0x%x sz=0x%x" % (jmprel, pltrelsz))
if rela is not None:
    ok(out_elf.vaddr_to_offset(rela) is not None, "DT_RELA 已映射")
    if relasz:
        off = out_elf.vaddr_to_offset(rela)
        ok(off + relasz <= len(out_buf), "DT_RELA 区间在文件内", "off=0x%x sz=0x%x" % (off, relasz))
if jmprel is not None:
    ok(out_elf.vaddr_to_offset(jmprel) is not None, "DT_JMPREL 已映射")
relro = next((p for p in out_elf.phdrs if p["type"] == PT_GNU_RELRO), None)
if relro:
    inside = any(p["vaddr"] <= relro["vaddr"] and relro["vaddr"] + relro["memsz"] <= p["vaddr"] + p["memsz"]
                 for p in out_elf.loads)
    print("   GNU_RELRO vaddr=0x%x memsz=0x%x 完全落在某个 PT_LOAD 的**内存**范围内=%s" %
          (relro["vaddr"], relro["memsz"], inside))
    if not inside:
        warn("GNU_RELRO 超出 PT_LOAD 的 memsz（bionic mprotect 会按页取整，通常仍可，但值得记录）")
for sh in out_elf.shdrs:
    if sh["type"] == 0:
        continue
    if sh["offset"] + sh["size"] > len(out_buf):
        ok(False, "节 %s 的文件范围越界" % sh["name"])
ok(all(sh["type"] == 0 or sh["offset"] + sh["size"] <= len(out_buf) for sh in out_elf.shdrs),
   "所有节头文件范围在文件内")

print()
print("### 附：APK 层面")
ok(GADGET_ENTRY in names, "APK 内有 %s（DT_NEEDED 的目标必须存在，否则运行期找不到）" % GADGET_ENTRY,
   "" if not gadget_info else "%d B (uncompressed)" % gadget_info.file_size)
if gadget:
    g = Elf(gadget, GADGET_ENTRY)
    ok(g.cls == 2 and g.machine == 0xB7, "gadget 是 AArch64 ELF64", "class=%d machine=0x%x type=%d" % (g.cls, g.machine, g.type))
    ok(b"frida" in gadget.lower() or b"frida" in gadget, "gadget 内含 frida 字样（佐证是 Frida Gadget）")
if config:
    print("   libaijsgadget.config.so = %d B, 头 32 字节=%s" %
          (len(config), " ".join("%02x" % b for b in config[:32])))
if gadget:
    g_align = [p["align"] for p in g.loads]
    ok(all(a == 0x4000 for a in g_align), "gadget 的 PT_LOAD p_align 全是 0x4000（16 KiB 页合规）",
       "实际=%s" % ["0x%x" % a for a in g_align])
lib_align = [p["align"] for p in out_elf.loads]
print("   目标 .so 的 PT_LOAD p_align=%s -> 16 KiB 页设备合规=%s" %
      (["0x%x" % a for a in lib_align], all(a == 0x4000 for a in lib_align)))
if not all(a == 0x4000 for a in lib_align):
    warn("目标 .so 是 4 KiB 对齐（K40 是 4K 页所以能加载）；16 KiB 页设备上会被 apk_inject_gadget 的 "
         "native 检查标成 ELF_PT_LOAD_ALIGNMENT_16K，且 Android 15+ 16K 设备要求 16K 对齐")
ok(info.compress_type == zipfile.ZIP_DEFLATED, "APK 内 .so 是压缩存储（解压后才可加载）",
   "method=%d" % info.compress_type)
print("   data offset %%4096 = 0x%x（压缩存储时对齐无意义；若清单是 extractNativeLibs=false 才会有问题）" % (data_off % 4096))
for probe in (b"extractNativeLibs", b"probe_only", b"dtprobe"):
    hit16 = probe in manifest or probe.decode().encode("utf-16-le") in manifest
    print("   清单原始字节含 %-20r : %s" % (probe, hit16))
ok(b"CERT.RSA" in "".join(names).encode() or any(n.startswith("META-INF/CERT") for n in names),
   "APK 带 v1 签名（META-INF/CERT.*）")

print()
print("### 附：orig(5024B) -> padded(5536B) 这一步是否「不搬既有字节」")
same_prefix = orig_buf == padded_buf[:len(orig_buf)]
print("   orig 是 padded 的前缀（= 只在尾部追加 + 就地改写）=%s" % same_prefix)
d = [i for i in range(len(orig_buf)) if orig_buf[i] != padded_buf[i]]
print("   共同长度内被改写的字节数=%d，位置=%s" % (len(d), ["0x%x" % i for i in d[:20]]))
print("   追加字节数=%d" % (len(padded_buf) - len(orig_buf)))

print()
print("=" * 92)
print("## 结论")
print("   FAIL 项：%d" % len(FAILS))
for f in FAILS:
    print("     - %s" % f)
print("   WARN 项：%d" % len(WARNS))
for w in WARNS:
    print("     - %s" % w)
if not FAILS:
    print("   -> 本脚本未能证伪：目标 .so 结构自洽、新增 DT_NEEDED 有效、变化字节全在声明区间内")
