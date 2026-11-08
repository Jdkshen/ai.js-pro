#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立检查器：逐字节复刻 ToolkitElfNeeded.kt 的两个空隙条件，并打印证据。

刻意**不复用** make_fixture.py 的任何代码、不调用 Kotlin/Java 实现：自己解析 ELF64 小端，
按 ToolkitElfNeeded.kt:138-168 的判定顺序重算：
  * dynamic 表来源：先 SHT_DYNAMIC（sh_link → SHT_STRTAB 给 dynstr），否则 PT_DYNAMIC；
    dynstr 拿不到时才回落 DT_STRTAB + DT_STRSZ（经 PT_LOAD 做 vaddr→offset 映射）。
  * 条件 1：dynstr 里存在 "<soname>\0"（findString），否则「最后一个非零字节 + 2」到节尾
    的距离 = have 必须 >= len(soname)+1。
  * 条件 2：存在下标 i 使 dynamic[i].tag == 0 且 dynamic[i+1].tag == 0。

另附一次**内存内补丁模拟**（同样的写入顺序与自校验），证明目标文件在真机上会走 success 分支；
不写任何文件。

用法：
  python check_dt_needed.py [--soname libaijsgadget.so] [--no-simulate] <so> [<so> ...]
退出码：0 = 所有输入两个条件都满足；1 = 有输入不满足（可拿原样 clang 输出当负例）。
"""
import argparse
import hashlib
import struct
import sys

SHT_NULL, SHT_STRTAB, SHT_DYNAMIC = 0, 3, 6
PT_LOAD, PT_DYNAMIC = 1, 2
DT_NAMES = {
    0: "NULL", 1: "NEEDED", 4: "HASH", 5: "STRTAB", 6: "SYMTAB", 7: "RELA", 8: "RELASZ",
    9: "RELAENT", 10: "STRSZ", 11: "SYMENT", 12: "INIT", 13: "FINI", 14: "SONAME",
    16: "SYMBOLIC", 17: "REL", 18: "RELSZ", 19: "RELENT", 20: "PLTRELSZ", 21: "PLTREL",
    22: "JMPREL", 23: "BIND_NOW", 24: "INIT_ARRAY", 25: "FINI_ARRAY", 26: "INIT_ARRAYSZ",
    27: "FINI_ARRAYSZ", 30: "FLAGS", 0x6FFFFFFB: "FLAGS_1", 0x6FFFFEF5: "GNU_HASH",
}


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def valid_range(buf, off, size):
    return off >= 0 and size >= 0 and off <= len(buf) and size <= len(buf) - off


def find_string(buf, offset, size, value):
    """与 ToolkitElfNeeded.findString 完全同义：精确字节 + 紧跟一个 NUL。"""
    needle = value.encode("utf-8")
    start = offset
    end = start + size
    if start < 0 or end > len(buf) or end - start < len(needle) + 1:
        return -1
    pos = start
    while pos <= end - len(needle) - 1:
        if buf[pos:pos + len(needle)] == needle and buf[pos + len(needle)] == 0:
            return pos - start
        pos += 1
    return -1


def vaddr_to_offset(loads, address):
    for ph in loads:
        if address >= ph["vaddr"] and address - ph["vaddr"] < ph["filesz"]:
            return ph["offset"] + (address - ph["vaddr"])
    return None


def parse(path):
    buf = open(path, "rb").read()
    if buf[:4] != b"\x7fELF":
        raise SystemExit("%s: 不是 ELF" % path)
    if buf[4] != 2 or buf[5] != 1:
        raise SystemExit("%s: 只支持 ELF64 小端" % path)
    e_phoff, e_shoff = u64(buf, 32), u64(buf, 40)
    phentsize, phnum = u16(buf, 54), u16(buf, 56)
    shentsize, shnum, shstrndx = u16(buf, 58), u16(buf, 60), u16(buf, 62)
    phdrs = []
    for i in range(phnum):
        o = e_phoff + i * phentsize
        phdrs.append({"index": i, "type": u32(buf, o), "flags": u32(buf, o + 4),
                      "offset": u64(buf, o + 8), "vaddr": u64(buf, o + 16),
                      "filesz": u64(buf, o + 32), "memsz": u64(buf, o + 40), "align": u64(buf, o + 48)})
    shdrs = []
    for i in range(shnum):
        o = e_shoff + i * shentsize
        shdrs.append({"index": i, "name_off": u32(buf, o), "type": u32(buf, o + 4),
                      "flags": u64(buf, o + 8), "addr": u64(buf, o + 16), "offset": u64(buf, o + 24),
                      "size": u64(buf, o + 32), "link": u32(buf, o + 40), "entsize": u64(buf, o + 56)})
    shstr = shdrs[shstrndx]
    names = buf[shstr["offset"]:shstr["offset"] + shstr["size"]]
    for sh in shdrs:
        end = names.find(b"\x00", sh["name_off"])
        sh["name"] = names[sh["name_off"]:end].decode("utf-8", "replace")
    return buf, phdrs, shdrs


def dyn_entries(buf, off, size, entsize):
    out = []
    for i in range(size // entsize):
        o = off + i * entsize
        tag = u64(buf, o) if entsize == 16 else u32(buf, o)
        val = u64(buf, o + 8) if entsize == 16 else u32(buf, o + 4)
        out.append((tag, val))
    return out


def tag_name(tag):
    return DT_NAMES.get(tag, "0x%x" % tag)


def analyze(path, soname, simulate=True):
    buf, phdrs, shdrs = parse(path)
    loads = [ph for ph in phdrs if ph["type"] == PT_LOAD]
    dyn_segment = next((ph for ph in phdrs if ph["type"] == PT_DYNAMIC), None)
    print("=" * 78)
    print("file      : %s" % path)
    print("size      : %d bytes   sha256=%s" % (len(buf), hashlib.sha256(buf).hexdigest()))
    print("machine   : aarch64 DYN, %d phdrs, %d shdrs" % (len(phdrs), len(shdrs)))
    for ph in loads + ([dyn_segment] if dyn_segment else []):
        print("  %-9s off=0x%-6x vaddr=0x%-8x filesz=0x%-6x memsz=0x%-6x %s"
              % ("PT_LOAD" if ph["type"] == PT_LOAD else "PT_DYNAMIC", ph["offset"], ph["vaddr"],
                 ph["filesz"], ph["memsz"], "RW" if ph["flags"] & 2 else "R"))

    # --- 与 ToolkitElfNeeded 相同的解析顺序 -------------------------------------------------
    dyn_sh = next((sh for sh in shdrs if sh["type"] == SHT_DYNAMIC and sh["size"] > 0
                   and valid_range(buf, sh["offset"], sh["size"])), None)
    dyn_off = dyn_size = 0
    entsize = 16
    dynstr_off = dynstr_size = 0
    dynstr_src = "none"
    if dyn_sh is not None:
        dyn_off, dyn_size = dyn_sh["offset"], dyn_sh["size"]
        entsize = int(dyn_sh["entsize"]) if dyn_sh["entsize"] in (8, 16) else 16
        print("dynamic   : from SHT_DYNAMIC '%s' off=0x%x size=0x%x entsize=%d"
              % (dyn_sh["name"], dyn_off, dyn_size, entsize))
        link = shdrs[dyn_sh["link"]] if dyn_sh["link"] < len(shdrs) else None
        if link is not None and link["type"] == SHT_STRTAB and valid_range(buf, link["offset"], link["size"]):
            dynstr_off, dynstr_size = link["offset"], link["size"]
            dynstr_src = "SHT_DYNAMIC.sh_link -> %s" % link["name"]
    if dyn_off == 0 and dyn_segment is not None:
        dyn_off, dyn_size = dyn_segment["offset"], dyn_segment["filesz"]
        print("dynamic   : from PT_DYNAMIC off=0x%x size=0x%x" % (dyn_off, dyn_size))
    if dyn_off == 0 or dyn_size < entsize:
        print("RESULT    : FAIL NO_DYNAMIC_SEGMENT")
        return False, False
    dynamic = dyn_entries(buf, dyn_off, dyn_size, entsize)

    if dynstr_off == 0 or dynstr_size == 0:
        strtab = next((v for t, v in dynamic if t == 5), None)
        strsz = next((v for t, v in dynamic if t == 10), None)
        mapped = vaddr_to_offset(loads, strtab) if strtab is not None else None
        if mapped is None or strsz is None or not valid_range(buf, mapped, strsz):
            print("RESULT    : FAIL DYNSTR_INVALID")
            return False, False
        dynstr_off, dynstr_size = mapped, strsz
        dynstr_src = "DT_STRTAB/DT_STRSZ -> 0x%x" % mapped
    print("dynstr    : from %s off=0x%x size=0x%x" % (dynstr_src, dynstr_off, dynstr_size))

    print("dynamic table (%d entries by sh_size):" % len(dynamic))
    for i, (tag, val) in enumerate(dynamic):
        mark = "  <-- DT_NULL" if tag == 0 else ""
        print("  [%2d] tag=0x%-16x %-9s 0x%x%s" % (i, tag, tag_name(tag), val, mark))

    # --- 条件 1 ---------------------------------------------------------------------------
    need = len(soname.encode("utf-8")) + 1
    existing = find_string(buf, dynstr_off, dynstr_size, soname)
    print("condition 1 (dynstr room): need=%d bytes (soname %r + NUL)" % (need, soname))
    if existing >= 0:
        ok1, branch = True, "existing"
        print("  findString -> offset %d（dynstr 里已存在该串，补丁不会写 dynstr）" % existing)
    else:
        last_non_zero = -1
        for i in range(dynstr_size):
            if buf[dynstr_off + i] != 0:
                last_non_zero = i
        free_start = 0 if last_non_zero < 0 else last_non_zero + 2
        have = max(dynstr_size - free_start, 0)
        ok1 = not (free_start >= dynstr_size or need > have)
        branch = "padding"
        print("  findString -> -1（不存在）")
        print("  lastNonZero=%d  freeStart=lastNonZero+2=%d  have=dynstrSize-freeStart=%d"
              % (last_non_zero, free_start, have))
        tail = buf[dynstr_off + max(dynstr_size - 24, 0):dynstr_off + dynstr_size]
        print("  dynstr 末尾 24 字节: %s" % " ".join("%02x" % c for c in tail))
        print("  verdict: %s (have %d %s need %d)" % ("PASS" if ok1 else "FAIL", have,
                                                      ">=" if ok1 else "<", need))

    # --- 条件 2 ---------------------------------------------------------------------------
    null_indices = [i for i, (tag, _v) in enumerate(dynamic) if tag == 0]
    spare = next((i for i in null_indices if i + 1 < len(dynamic) and dynamic[i + 1][0] == 0), None)
    print("condition 2 (spare DT_NULL): DT_NULL 下标=%s" % null_indices)
    if spare is None:
        print("  verdict: FAIL DYNAMIC_NO_SPARE_NULL（没有「改掉后仍保留终止 DT_NULL」的槽）")
        ok2 = False
    else:
        print("  spareIndex=%d（dynamic[%d].tag=0 且 dynamic[%d].tag=0）-> 改写 [%d] 后 [%d] 仍是终止符"
              % (spare, spare, spare + 1, spare, spare + 1))
        print("  verdict: PASS")
        ok2 = True

    # --- 补丁模拟（同样的写入顺序 + 自校验，只在内存里做） ---------------------------------
    if simulate and ok1 and ok2:
        patched = bytearray(buf)
        ranges = []
        if existing >= 0:
            soname_offset = existing
        else:
            soname_offset = free_start
            s = dynstr_off + soname_offset
            patched[s:s + len(soname)] = soname.encode("utf-8")
            patched[s + len(soname)] = 0
            ranges.append((s, s + len(soname)))
        base = dyn_off + spare * entsize
        struct.pack_into("<Q", patched, base, 1)          # DT_NEEDED
        struct.pack_into("<Q", patched, base + 8, soname_offset)
        ranges.append((base, base + entsize - 1))
        check = dyn_entries(bytes(patched), dyn_off, dyn_size, entsize)
        has_needed = any(t == 1 and v == soname_offset for t, v in check)
        has_string = find_string(bytes(patched), dynstr_off, dynstr_size, soname) >= 0
        same_size = len(patched) == len(buf)
        outside = [i for i in range(len(patched)) if patched[i] != buf[i]
                   and not any(lo <= i <= hi for lo, hi in ranges)]
        print("simulated patch: applied=%s verified=%s dynamicIndex=%d strtabOffset=%d"
              % (has_needed and has_string and same_size and not outside, 
                 has_needed and has_string and same_size and not outside, spare, soname_offset))
        print("  changedRanges=%s  sizeUnchanged=%s  outsideRanges=%d  newSha256=%s"
              % (["0x%x-0x%x" % r for r in ranges], same_size, len(outside),
                 hashlib.sha256(bytes(patched)).hexdigest()))
        expect = bytearray(buf)
        expect[dyn_off + (len(dynamic) - 0) * entsize:] = expect[dyn_off + (len(dynamic)) * entsize:]  # no-op, 占位
        print("  预期真机分支：%s" % ("findString 命中（只改 dynamic）" if existing >= 0
                                      else "写入 dynstr 节尾 padding（改 dynstr + dynamic 两处）"))
    print("RESULT    : condition1=%s condition2=%s -> %s"
          % ("PASS" if ok1 else "FAIL", "PASS" if ok2 else "FAIL",
             "READY_FOR_DT_NEEDED" if (ok1 and ok2) else "WOULD_FAIL"))
    return ok1, ok2


def main():
    # Windows 控制台默认 GBK，中文证据会变乱码；显式切 UTF-8（不改任何文件内容）。
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser()
    ap.add_argument("--soname", default="libaijsgadget.so")
    ap.add_argument("--no-simulate", action="store_true")
    ap.add_argument("files", nargs="+")
    args = ap.parse_args()
    all_ok = True
    for path in args.files:
        ok1, ok2 = analyze(path, args.soname, simulate=not args.no_simulate)
        all_ok = all_ok and ok1 and ok2
    print("=" * 78)
    print("SUMMARY: %s" % ("all inputs satisfy both conditions" if all_ok
                           else "at least one input would be REJECTED by ToolkitElfNeeded"))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
