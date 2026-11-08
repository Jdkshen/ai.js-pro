#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为 ToolkitElfNeeded 的「就地加 DT_NEEDED」成功路径造 arm64-v8a 夹具（P1-4 dt_needed 前置）。

背景（apps/app/src/main/java/com/jdkshen/aijspro/mcp/ToolkitElfNeeded.kt:149-168）要求目标 .so 同时满足：
  条件 1  .dynstr 里已存在 "libaijsgadget.so"，**或** 节尾有 >=18 个 NUL（判定是「最后一个非零字节 + 2」
          到节尾的距离 >= 17）；否则 DYNSTR_NO_ROOM:17xN。
  条件 2  dynamic 表里存在「连续两个 DT_NULL」（tag=0），补丁把前一个改写成 DT_NEEDED，
          后一个继续当终止符；否则 DYNAMIC_NO_SPARE_NULL。
clang 直接编出来的极小 .so **两个条件都不满足**（本脚本会顺手产出这个对照组）。

做法（不依赖 patchelf）：
  1. NDK clang 编 src/dtprobe.c → 真 arm64 .so（-nostdlib，dynamic 里没有 DT_NEEDED，补丁结果一眼可辨）；
  2. 在 .dynamic 之后「插入」16 字节（多一个 DT_NULL 条目）——插入点正好是 RW PT_LOAD 的文件尾，
     所以新增映射的字节只有我们追加的内容，不动任何既有字节的语义；
  3. 在 .dynamic 之后追加一份 **dynstr 副本 + 64 字节 NUL**（可选再带 "libaijsgadget.so"），
     .dynstr 的 sh_offset/sh_size、DT_STRTAB/DT_STRSZ 指向副本；
  4. PT_DYNAMIC 与 RW PT_LOAD 的 p_filesz/p_memsz 同步扩到新尾；PT_LOAD 的 p_offset/p_vaddr 不动
     （同页，p_offset ≡ p_vaddr mod p_align 保持成立）；
  5. 插入点之后的**非分配**节（.comment/.symtab/.shstrtab/.strtab）与节头表整体平移，更新各自
     sh_offset 与 e_shoff —— 这是构建期一次性重排，与 ToolkitElfNeeded 运行时「不移动字节」无关。

产出（都在本目录）：
  libdtprobe.so              主夹具：走「节尾 padding」分支（补丁会真的写字符串）
  libdtprobe-preexisting.so  变体：dynstr 里已存在 libaijsgadget.so（走 findString 命中分支）
  libdtprobe-raw-no-gap.so   对照组：clang 原样输出，两个条件都不满足（预期 DYNSTR_NO_ROOM:17x0）

用法： python make_fixture.py [--clang <clang.exe>] [--outdir .] [--keep-raw]
"""
import argparse
import hashlib
import os
import shutil
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GADGET_SONAME = "libaijsgadget.so"
DYNSTR_PAD = 64          # 追加到 dynstr 末尾的 NUL（条件 1 只需要 >=18，留余量便于人工核对）
EXTRA_NULLS = 1          # 追加的 DT_NULL 条目数（1 → 与原 DT_NULL 构成「连续两个」）

# ---------------------------------------------------------------- ELF64 小工具

SHT_NULL, SHT_PROGBITS, SHT_STRTAB, SHT_DYNAMIC, SHT_DYNSYM = 0, 1, 3, 6, 11
PT_LOAD, PT_DYNAMIC = 1, 2
SHF_ALLOC = 0x2


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def p64(b, o, v):
    struct.pack_into("<Q", b, o, v)


def p32(b, o, v):
    struct.pack_into("<I", b, o, v)


class ProgramHeader(object):
    def __init__(self, buf, off):
        self.index = None
        self.type = u32(buf, off)
        self.flags = u32(buf, off + 4)
        self.offset = u64(buf, off + 8)
        self.vaddr = u64(buf, off + 16)
        self.filesz = u64(buf, off + 32)
        self.memsz = u64(buf, off + 40)
        self.align = u64(buf, off + 48)
        self.entry_off = off


class SectionHeader(object):
    def __init__(self, buf, off, index):
        self.index = index
        self.name_off = u32(buf, off)
        self.type = u32(buf, off + 4)
        self.flags = u64(buf, off + 8)
        self.addr = u64(buf, off + 16)
        self.offset = u64(buf, off + 24)
        self.size = u64(buf, off + 32)
        self.link = u32(buf, off + 40)
        self.info = u32(buf, off + 44)
        self.addralign = u64(buf, off + 48)
        self.entsize = u64(buf, off + 56)
        self.entry_off = off
        self.name = "?"


def parse_elf(buf):
    if buf[:4] != b"\x7fELF":
        raise SystemExit("不是 ELF")
    if buf[4] != 2 or buf[5] != 1:
        raise SystemExit("本脚本只处理 ELF64 小端（ledger: class=%d data=%d）" % (buf[4], buf[5]))
    e_phoff = u64(buf, 32)
    e_shoff = u64(buf, 40)
    e_phentsize = u16(buf, 54)
    e_phnum = u16(buf, 56)
    e_shentsize = u16(buf, 58)
    e_shnum = u16(buf, 60)
    e_shstrndx = u16(buf, 62)
    phdrs = [ProgramHeader(buf, e_phoff + i * e_phentsize) for i in range(e_phnum)]
    for i, ph in enumerate(phdrs):
        ph.index = i
    shdrs = [SectionHeader(buf, e_shoff + i * e_shentsize, i) for i in range(e_shnum)]
    shstr = shdrs[e_shstrndx]
    strtab = buf[shstr.offset:shstr.offset + shstr.size]
    for sh in shdrs:
        end = strtab.find(b"\x00", sh.name_off)
        sh.name = strtab[sh.name_off:end].decode("utf-8", "replace")
    return {
        "phoff": e_phoff, "shoff": e_shoff, "phentsize": e_phentsize, "phnum": e_phnum,
        "shentsize": e_shentsize, "shnum": e_shnum, "shstrndx": e_shstrndx,
        "phdrs": phdrs, "shdrs": shdrs,
    }


def dyn_entries(buf, off, size, entsize):
    out = []
    for i in range(size // entsize):
        o = off + i * entsize
        tag = u64(buf, o) if entsize == 16 else u32(buf, o)
        val = u64(buf, o + 8) if entsize == 16 else u32(buf, o + 4)
        out.append((tag, val))
    return out


# ---------------------------------------------------------------- clang

def find_clang(explicit=None):
    cands = []
    if explicit:
        cands.append(explicit)
    if os.environ.get("DT_NEEDED_CLANG"):
        cands.append(os.environ["DT_NEEDED_CLANG"])
    for ndk_root in (os.environ.get("ANDROID_NDK_HOME"), os.environ.get("ANDROID_NDK_ROOT"),
                     r"C:\Android\ndk-r26d", r"C:\Android\ndk"):
        if ndk_root:
            cands.append(os.path.join(ndk_root, "toolchains", "llvm", "prebuilt",
                                      "windows-x86_64", "bin", "clang.exe"))
            cands.append(os.path.join(ndk_root, "toolchains", "llvm", "prebuilt",
                                      "linux-x86_64", "bin", "clang"))
    for c in cands:
        if c and os.path.isfile(c):
            return c
    return shutil.which("clang") or shutil.which("clang.exe")


def compile_raw(clang, src, out):
    # -z max-page-size=16384：所有 PT_LOAD 的 p_align=0x4000，符合 Android 15+ 的 16KiB 页要求
    # （4K 页设备同样兼容；文件内偏移与本脚本的布局假设都不变，只有 vaddr 变大）。
    args = [clang, "--target=aarch64-linux-android21", "-shared", "-fPIC", "-nostdlib", "-O1",
            "-Wl,-soname,libdtprobe.so", "-Wl,-z,max-page-size=16384", "-o", out, src]
    proc = subprocess.run(args, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit("clang 失败：%s\n%s\n%s" % (" ".join(args), proc.stdout, proc.stderr))
    return proc


# ---------------------------------------------------------------- 手术

def build_variant(raw, out_path, with_preexisting_string):
    buf = bytearray(raw)
    elf = parse_elf(buf)

    dyn = next((sh for sh in elf["shdrs"] if sh.type == SHT_DYNAMIC and sh.size > 0), None)
    if dyn is None:
        raise SystemExit("没有 SHT_DYNAMIC")
    entsize = dyn.entsize or 16
    strtab = elf["shdrs"][dyn.link] if dyn.link < len(elf["shdrs"]) else None
    if strtab is None or strtab.type != SHT_STRTAB:
        raise SystemExit("SHT_DYNAMIC.sh_link 不是 SHT_STRTAB")
    entries = dyn_entries(buf, dyn.offset, dyn.size, entsize)
    if entries[-1] != (0, 0):
        raise SystemExit("原 dynamic 表最后一个条目不是 DT_NULL，本脚本不处理")

    insert_at = dyn.offset + dyn.size
    # 插入点必须是「最后一个 PT_LOAD 的文件尾」，这样新增映射的字节只有我们追加的内容。
    dyn_load = next((ph for ph in elf["phdrs"]
                     if ph.type == PT_LOAD and ph.offset <= dyn.offset < ph.offset + ph.filesz), None)
    if dyn_load is None:
        raise SystemExit(".dynamic 不在任何 PT_LOAD 内")
    tail_end = dyn_load.offset + dyn_load.filesz
    if tail_end != insert_at:
        raise SystemExit("插入点 0x%x 不等于宿主 PT_LOAD 末尾 0x%x（本脚本只处理这种紧凑布局）"
                         % (insert_at, tail_end))
    if dyn_load.memsz != dyn_load.filesz:
        raise SystemExit("宿主 PT_LOAD 有 .bss（memsz 0x%x > filesz 0x%x），本脚本不处理"
                         % (dyn_load.memsz, dyn_load.filesz))
    for sh in elf["shdrs"]:
        if sh.type == SHT_NULL:
            continue
        if (sh.flags & SHF_ALLOC) and sh.offset >= insert_at and sh.size > 0:
            raise SystemExit("插入点之后还有分配节 %s，本脚本不处理" % sh.name)

    new_dyn_size = dyn.size + EXTRA_NULLS * entsize
    new_str_offset = insert_at + EXTRA_NULLS * entsize
    new_str_copy = bytes(buf[strtab.offset:strtab.offset + strtab.size])
    extra_string = (GADGET_SONAME.encode() + b"\x00") if with_preexisting_string else b""
    new_str = new_str_copy + extra_string + b"\x00" * DYNSTR_PAD
    new_str_size = len(new_str)
    new_tail_offset = new_str_offset + new_str_size
    shift = new_tail_offset - insert_at

    # 1) 插入 DT_NULL 槽 + 新 dynstr + 平移尾部
    patched = bytearray(buf[:insert_at])
    patched += b"\x00" * (EXTRA_NULLS * entsize)
    patched += new_str
    patched += buf[insert_at:]
    assert len(patched) == len(buf) + shift

    # 2) 平移插入点之后的节（全是非分配节）与节头表。
    #    节头表整体位于插入点之后，所以每个 shdr 条目自身的位置也 +shift（这一步曾经写错：
    #    用旧 entry_off 写会让整张表串位，llvm-readelf 会读出垃圾段名/偏移）。
    def shdr_pos(sh):
        return sh.entry_off + shift

    assert elf["shoff"] > insert_at, "节头表在插入点之前，本脚本不处理"
    for sh in elf["shdrs"]:
        if sh.type == SHT_NULL:
            continue
        if sh.offset >= insert_at:
            p64(patched, shdr_pos(sh) + 24, sh.offset + shift)
    p64(patched, 40, elf["shoff"] + shift)

    # 3) .dynamic 变长；.dynstr 指向副本
    new_str_vaddr = dyn_load.vaddr + (new_str_offset - dyn_load.offset)
    p64(patched, shdr_pos(dyn) + 32, new_dyn_size)       # sh_size
    p64(patched, shdr_pos(strtab) + 16, new_str_vaddr)   # sh_addr
    p64(patched, shdr_pos(strtab) + 24, new_str_offset)  # sh_offset
    p64(patched, shdr_pos(strtab) + 32, new_str_size)    # sh_size

    # 4) PT_DYNAMIC / RW PT_LOAD 扩容（只覆盖我们追加的字节；phdr 表在插入点之前，位置不变）
    elf2 = parse_elf(patched)
    for ph in elf2["phdrs"]:
        if ph.type == PT_DYNAMIC and ph.offset == dyn.offset:
            p64(patched, ph.entry_off + 32, new_dyn_size)
            p64(patched, ph.entry_off + 40, new_dyn_size)
        if ph.type == PT_LOAD and ph.offset == dyn_load.offset:
            p64(patched, ph.entry_off + 32, new_tail_offset - dyn_load.offset)
            p64(patched, ph.entry_off + 40, new_tail_offset - dyn_load.offset)

    # 5) dynamic 表里 DT_STRTAB/DT_STRSZ 指向副本
    for i, (tag, _val) in enumerate(entries):
        o = dyn.offset + i * entsize
        if tag == 5:   # DT_STRTAB
            p64(patched, o + 8, new_str_vaddr) if entsize == 16 else p32(patched, o + 4, new_str_vaddr)
        elif tag == 10:  # DT_STRSZ
            p64(patched, o + 8, new_str_size) if entsize == 16 else p32(patched, o + 4, new_str_size)

    with open(out_path, "wb") as fh:
        fh.write(patched)

    h = hashlib.sha256(patched).hexdigest()
    print("--- %s" % os.path.basename(out_path))
    print("    size=%d sha256=%s" % (len(patched), h))
    print("    .dynamic off=0x%x size=0x%x (%d entries)  .dynstr off=0x%x size=0x%x vaddr=0x%x"
          % (dyn.offset, new_dyn_size, new_dyn_size // entsize, new_str_offset, new_str_size, new_str_vaddr))
    print("    RW PT_LOAD offset=0x%x vaddr=0x%x filesz=memsz=0x%x  shift=%d"
          % (dyn_load.offset, dyn_load.vaddr, new_tail_offset - dyn_load.offset, shift))
    print("    branch=%s" % ("dynstr already contains the soname" if with_preexisting_string else "write into dynstr tail padding"))
    return patched


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clang", default=None)
    ap.add_argument("--outdir", default=HERE)
    ap.add_argument("--src", default=os.path.join(HERE, "src", "dtprobe.c"))
    args = ap.parse_args()

    clang = find_clang(args.clang)
    if not clang:
        raise SystemExit("找不到 clang：用 --clang 指定，或设 ANDROID_NDK_HOME")
    print("clang = %s" % clang)
    outdir = os.path.abspath(args.outdir)
    scratch = os.path.join(outdir, "scratch")
    os.makedirs(scratch, exist_ok=True)
    raw_path = os.path.join(scratch, "raw.so")
    compile_raw(clang, os.path.abspath(args.src), raw_path)
    raw = open(raw_path, "rb").read()
    print("raw = %s  size=%d sha256=%s" % (raw_path, len(raw), hashlib.sha256(raw).hexdigest()))

    shutil.copyfile(raw_path, os.path.join(outdir, "libdtprobe-raw-no-gap.so"))
    build_variant(raw, os.path.join(outdir, "libdtprobe.so"), False)
    build_variant(raw, os.path.join(outdir, "libdtprobe-preexisting.so"), True)
    print("\n下一步：python check_dt_needed.py libdtprobe.so libdtprobe-preexisting.so libdtprobe-raw-no-gap.so")


if __name__ == "__main__":
    main()
