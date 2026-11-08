#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立解 AndroidManifest.xml（AXML）：确认 extractNativeLibs 的布尔值，以及 Provider 的进程归属。

自己写的最小 AXML 解析器（string pool + start element chunk），不依赖 aapt2/androidx。
用来把两条**推断**变成证据：
  ① APK 里 .so 是 DEFLATE 压缩存储，能不能装/能加载取决于 android:extractNativeLibs；
  ② Lead 说运行时证据用的是 providerProcess=":probe_only"（惰性进程，Provider 永不实例化）。
"""
import os
import struct
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = r"C:\Users\18101\Documents\ai-js-pro\.artifacts\dtprobe-app\out"
APKS = [("ok", os.path.join(BASE, "dtprobe-injected-ok.apk")),
        ("inert", os.path.join(BASE, "dtprobe-injected-inert.apk"))]

RES_STRING_POOL, RES_XML_START_ELEMENT = 0x0001, 0x0102
TYPE_STRING, TYPE_INT_DEC, TYPE_INT_HEX, TYPE_INT_BOOLEAN, TYPE_REFERENCE = 0x03, 0x10, 0x11, 0x12, 0x01


def parse_string_pool(buf, off):
    string_count, style_count, flags, strings_start, styles_start = struct.unpack_from("<5I", buf, off + 8)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from("<%dI" % string_count, buf, off + 28)
    base = off + strings_start
    out = []
    for o in offsets:
        p = base + o
        if utf8:
            n = buf[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | buf[p + 1]
                p += 2
            else:
                p += 1
            blen = buf[p]
            if blen & 0x80:
                blen = ((blen & 0x7F) << 8) | buf[p + 1]
                p += 2
            else:
                p += 1
            out.append(buf[p:p + blen].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", buf, p)[0]
                p += 2
            out.append(buf[p:p + n * 2].decode("utf-16-le", "replace"))
    return out


def parse_axml(blob):
    if struct.unpack_from("<H", blob, 0)[0] != 0x0003:
        return None, []
    off, strings, elements = 8, [], []
    while off + 8 <= len(blob):
        ctype, hsize, csize = struct.unpack_from("<HHI", blob, off)
        if csize == 0:
            break
        if ctype == RES_STRING_POOL:
            strings = parse_string_pool(blob, off)
        elif ctype == RES_XML_START_ELEMENT:
            name_idx = struct.unpack_from("<I", blob, off + 20)[0]
            attr_start, attr_size, attr_count = struct.unpack_from("<3H", blob, off + 24)
            attrs = []
            # ResXMLTree_attrExt 从 chunk 头(8) + lineNumber(4) + comment(4) = off+16 开始，
            # attributeStart 是「相对 attrExt 的字节偏移」（AOSP ResXMLTree::getAttributeNameID 的算法）
            for i in range(attr_count):
                a = off + 16 + attr_start + i * attr_size
                ans, aname, raw = struct.unpack_from("<3I", blob, a)
                dtype, data = blob[a + 15], struct.unpack_from("<I", blob, a + 16)[0]
                if dtype == TYPE_STRING:
                    value = strings[data] if data < len(strings) else "?"
                elif dtype == TYPE_INT_BOOLEAN:
                    value = "true" if data else "false"
                elif dtype == TYPE_INT_DEC:
                    value = str(data)
                elif dtype == TYPE_INT_HEX:
                    value = "0x%x" % data
                elif dtype == TYPE_REFERENCE:
                    value = "@0x%08x" % data
                else:
                    value = "type=0x%02x data=0x%x" % (dtype, data)
                attrs.append(((strings[aname] if aname < len(strings) else "?"), value))
            elements.append(((strings[name_idx] if name_idx < len(strings) else "?"), attrs))
        off += csize
    return strings, elements


for label, apk in APKS:
    with zipfile.ZipFile(apk) as z:
        manifest = z.read("AndroidManifest.xml")
    strings, elements = parse_axml(manifest)
    print("=" * 90)
    print("## [%s] %s  (manifest %d B, %d 个 start element, 字符串池 %d 项)" %
          (label, os.path.basename(apk), len(manifest), len(elements), len(strings or [])))
    if not elements:
        print("   解析失败")
        continue
    interesting = {"manifest", "application", "activity", "provider", "service", "receiver", "meta-data", "uses-permission"}
    # 注意：aapt2 的字符串池里属性名**不带 android: 前缀**（命名空间在 attribute 的 ns 字段里），
    # 所以这里把感兴趣元素的**全部**属性原样打印，不做过滤（过滤会漏掉 extractNativeLibs）。
    for name, attrs in elements:
        if name in interesting:
            print("   <%s> %s" % (name, "  ".join("%s=%s" % (k, v) for k, v in attrs) if attrs else "(无属性)"))
    app = next((attrs for n, attrs in elements if n == "application"), [])
    extract = next((v for k, v in app if k.endswith("extractNativeLibs")), None)
    print("   >>> application.extractNativeLibs = %s" %
          (extract if extract is not None else "(未声明 → 平台默认 true：安装时把 lib/**.so 解压到应用 lib 目录)"))
    for name, attrs in elements:
        if name == "provider":
            d = {k: v for k, v in attrs}
            print("   >>> provider: name=%s process=%s exported=%s authorities=%s" %
                  (d.get("name"), d.get("process", "(未声明 → 主进程)"), d.get("exported"), d.get("authorities")))
    for name, attrs in elements:
        if name == "activity":
            d = {k: v for k, v in attrs}
            print("   >>> activity: name=%s process=%s exported=%s" %
                  (d.get("name"), d.get("process", "(未声明 → 主进程)"), d.get("exported")))
