"""Policy regression cases for the Android 16KB release gate."""
import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location('page_check', Path(__file__).with_name('check-android-16kb.py'))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def elf(*headers):
    ident = b'\x7fELF\x02\x01\x01' + bytes(9)
    data = struct.pack('<16sHHIQQQIHHHHHH', ident, 3, 183, 1, 0, 64, 0, 0, 64, 56, len(headers), 0, 0, 0)
    return data + b''.join(struct.pack('<IIQQQQQQ', *h) for h in headers)


def load(start=0, size=0x4000, alignment=0x4000):
    return (1, 6, start, start, start, size, size, alignment)


class PageGateTest(unittest.TestCase):
    def test_rejects_4kb_load(self):
        self.assertTrue(gate.inspect_elf(elf(load(alignment=4096)))['issues'])

    def test_accepts_16kb_and_64kb(self):
        for alignment in [16384, 65536]:
            self.assertFalse(gate.inspect_elf(elf(load(alignment=alignment)))['issues'])

    def test_rejects_relro_covering_writable_data(self):
        relro = (0x6474e552, 4, 0x4000, 0x4000, 0x4000, 0x5000, 0x5000, 1)
        self.assertTrue(gate.inspect_elf(elf(load(0x4000, 0x8000), relro))['issues'])

    def test_accepts_isolated_relro_even_with_unaligned_end(self):
        relro = (0x6474e552, 4, 0x4000, 0x4000, 0x4000, 0x5000, 0x5000, 1)
        self.assertFalse(gate.inspect_elf(elf(load(0x4000, 0x5000), load(0xc000), relro))['issues'])

    def test_node_runtime_assets_cannot_bypass_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'app.apk'
            with zipfile.ZipFile(apk, 'w', compression=zipfile.ZIP_DEFLATED) as z:
                z.writestr('lib/arm64-v8a/libflutter.so', elf(load()))
                z.writestr('assets/source-engine-upgrade/node24/arm64-v8a/libc++_shared.so', elf(load(alignment=4096)))
            self.assertFalse(gate.check(apk)['ok'])

    def test_checks_zip_alignment_only_for_uncompressed_native_libraries(self):
        with tempfile.TemporaryDirectory() as directory:
            for compression, expected in [(zipfile.ZIP_STORED, False), (zipfile.ZIP_DEFLATED, True)]:
                apk = Path(directory) / 'app.apk'
                with zipfile.ZipFile(apk, 'w', compression=compression) as z:
                    z.writestr('lib/arm64-v8a/libflutter.so', elf(load()))
                self.assertEqual(gate.check(apk)['ok'], expected)

    def test_rejects_malformed_elf(self):
        with self.assertRaises(ValueError):
            gate.inspect_elf(b'not an ELF')


if __name__ == '__main__':
    unittest.main()
