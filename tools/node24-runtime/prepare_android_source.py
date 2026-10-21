"""Apply the reviewed Android arm64 fixes to a separate Node build tree.

The pinned vendor directory stays unchanged. Unknown edits are rejected; reruns
accept only the exact result of these transformations.
"""
import hashlib
import json
from pathlib import Path
import sys


def patch(source, relative, expected_hash, replacements):
    path = source / relative
    current = path.read_bytes()
    backup = path.with_name(path.name + '.ajs-upstream')
    original = backup.read_bytes() if backup.exists() else current
    if hashlib.sha256(original).hexdigest() != expected_hash:
        raise SystemExit('Unknown upstream content: ' + str(path))
    updated = original.decode('utf-8')
    for old, new, *counts in replacements:
        expected_count = counts[0] if counts else 1
        if updated.count(old) != expected_count:
            raise SystemExit('Patch context mismatch: ' + relative)
        updated = updated.replace(old, new)
    result = updated.encode('utf-8')
    if current not in (original, result):
        raise SystemExit('Unrecognized local edits: ' + str(path))
    if current != result:
        if not backup.exists():
            backup.write_bytes(original)
        path.write_bytes(result)
    digest = hashlib.sha256(result).hexdigest()
    print(relative + ': ' + digest)
    return digest


def main():
    source = Path(sys.argv[1]).resolve()
    vendor = Path(__file__).resolve().parents[1] / 'third_party' / 'node-v24.22.0'
    if source == vendor.resolve():
        raise SystemExit('Use a separate build tree; pinned vendor sources are immutable')
    reviewed = {}
    reviewed['tools/v8_gypfiles/toolchain.gypi'] = patch(source, 'tools/v8_gypfiles/toolchain.gypi',
          'a1c257b371f3deca51e9cd4f1189206d99c28cb64a3c4d53b5d920dc02053059', [
        ("['v8_control_flow_integrity==1', {\n            'cflags': [ '-mbranch-protection=standard' ],",
         "['v8_control_flow_integrity==1 and ((_toolset==\"target\" and target_arch==\"arm64\") or (_toolset==\"host\" and host_arch==\"arm64\"))', {\n            'cflags': [ '-mbranch-protection=standard' ],")
    ])
    reviewed['deps/zlib/cpu_features.c'] = patch(source, 'deps/zlib/cpu_features.c',
          '7e80a4d297e5eb6e83b8a9c384ecb7b572ccfe63acdd70fd8bc703ce714d3d21', [
        ('#if defined(ARMV8_OS_ANDROID)\n#include <cpu-features.h>\n#elif defined(ARMV8_OS_LINUX)',
         '#if defined(ARMV8_OS_ANDROID) && !defined(__aarch64__)\n#include <cpu-features.h>\n#elif defined(ARMV8_OS_LINUX) || (defined(ARMV8_OS_ANDROID) && defined(__aarch64__))'),
        ('#if defined(ARMV8_OS_ANDROID) && defined(__aarch64__)\n    uint64_t features = android_getCpuFeatures();\n    arm_cpu_enable_crc32 = !!(features & ANDROID_CPU_ARM64_FEATURE_CRC32);\n    arm_cpu_enable_pmull = !!(features & ANDROID_CPU_ARM64_FEATURE_PMULL);',
         '#if defined(ARMV8_OS_ANDROID) && defined(__aarch64__)\n    /* Bionic exposes getauxval since API 18. Use kernel capability bits,\n     * without a separate NDK cpufeatures library or privileged registers. */\n    unsigned long features = getauxval(AT_HWCAP);\n    arm_cpu_enable_crc32 = !!(features & HWCAP_CRC32);\n    arm_cpu_enable_pmull = !!(features & HWCAP_PMULL);')
    ])
    # The target OS also filters host sources in upstream gyp. Linux x64 host
    # mksnapshot needs these POSIX/simulator implementations for OS=android.
    conditions = [
        ('((_toolset=="host" and host_arch=="arm64" or _toolset=="target" and target_arch=="arm64") and (OS in "linux mac openharmony")) or ((_toolset=="host" and host_arch=="x64" or _toolset=="target" and target_arch=="x64") and (OS in "linux mac openharmony"))', 1),
        ('((_toolset=="host" and host_arch=="arm64" or _toolset=="target" and target_arch=="arm64") and (OS in "linux mac ios openharmony")) or ((_toolset=="host" and host_arch=="x64" or _toolset=="target" and target_arch=="x64") and (OS in "linux mac openharmony"))', 1),
        ('(_toolset=="host" and host_arch=="x64" or _toolset=="target" and target_arch=="x64") and (OS in "linux mac win openharmony")', 2),
    ]
    reviewed['tools/v8_gypfiles/v8.gyp'] = patch(source, 'tools/v8_gypfiles/v8.gyp',
          '7874211f71530098ad8220db00a111f33183732d7500335c7cc362937e1c3209', [
        (condition, '(' + condition + ') or (_toolset=="host" and host_arch=="x64" and host_os=="linux")', count)
        for condition, count in conditions
    ])
    # Preserve full upstream pin validation in the separate build tree, rather
    # than checking only the archive while reusing an already modified cache.
    pins_path = Path(__file__).with_name('source-filehashes.json')
    pins = json.loads(pins_path.read_text(encoding='utf-8'))
    for relative, upstream in pins.items():
        path = source / relative
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != reviewed.get(relative, upstream):
            raise SystemExit('Node source pin mismatch: ' + relative)
    print(f'Verified {len(pins)} Node source pins, including {len(reviewed)} reviewed patches')


if __name__ == '__main__':
    main()
