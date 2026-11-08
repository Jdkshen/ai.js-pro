#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""补充复核：Lead 的两个注入产物（ok / inert）到底差在哪，以及 ":probe_only" 出现在哪个 APK。

Lead 的运行时证据是「改用 providerProcess=":probe_only"（惰性进程）重做」之后拿到的，
而让我复核的是 injected-ok.apk。这里独立确认：两份 APK 的目标 .so 是否同一份（字节级），
以及 probe_only 出现在哪一份的清单里——避免把「结构复核」和「运行时证据」对错产物。
"""
import hashlib
import os
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = r"C:\Users\18101\Documents\ai-js-pro\.artifacts\dtprobe-app\out"
OK = os.path.join(BASE, "dtprobe-injected-ok.apk")
INERT = os.path.join(BASE, "dtprobe-injected-inert.apk")


def entries(path):
    with zipfile.ZipFile(path) as z:
        return {i.filename: (z.read(i.filename), i.compress_type, i.file_size) for i in z.infolist()}


ok, inert = entries(OK), entries(INERT)
print("== 目标 .so 是否同一份")
for name in ("lib/arm64-v8a/libdtprobe.so", "lib/arm64-v8a/libaijsgadget.so",
             "lib/arm64-v8a/libaijsgadget.config.so", "assets/aijs-gadget.json",
             "AndroidManifest.xml", "classes.dex", "classes2.dex"):
    a = ok.get(name)
    b = inert.get(name)
    ha = hashlib.sha256(a[0]).hexdigest()[:16] if a else "-"
    hb = hashlib.sha256(b[0]).hexdigest()[:16] if b else "-"
    print("   %-44s ok=%s(%dB) inert=%s(%dB) %s" %
          (name, ha, a[2] if a else 0, hb, b[2] if b else 0, "SAME" if ha == hb else "DIFFERENT"))

print("== 清单里出现的关键串（在 AXML 原始字节里按 UTF-8 / UTF-16LE 两种编码找）")
for label, blob in (("ok", ok["AndroidManifest.xml"][0]), ("inert", inert["AndroidManifest.xml"][0])):
    for probe in ("probe_only", "extractNativeLibs", "dtprobe", "providerProcess", "aijsgadget"):
        hit = (probe.encode() in blob) or (probe.encode("utf-16-le") in blob)
        print("   [%-5s] %-18s %s" % (label, probe, hit))
print("== 清单里所有可读 ASCII 串（>=5 字符，取前 40 条，用于说明差异来源）")
for label, blob in (("ok", ok["AndroidManifest.xml"][0]), ("inert", inert["AndroidManifest.xml"][0])):
    found, cur = [], bytearray()
    for byte in blob:
        if 32 <= byte < 127:
            cur.append(byte)
        else:
            if len(cur) >= 5:
                found.append(cur.decode("ascii"))
            cur = bytearray()
    if len(cur) >= 5:
        found.append(cur.decode("ascii"))
    print("   [%s] %s" % (label, sorted(set(found))[:40]))
