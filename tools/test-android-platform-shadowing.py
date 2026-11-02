import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("shadowing", Path(__file__).with_name("check-android-platform-shadowing.py"))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

def dex(names, defined):
    string_ids = 112; type_ids = string_ids + len(names) * 4; class_defs = type_ids + len(names) * 4
    data = bytearray(class_defs + len(defined) * 32)
    data[:8] = b"dex\n035\0"
    for offset, value in [(40, 0x12345678), (56, len(names)), (60, string_ids), (64, len(names)), (68, type_ids), (96, len(defined)), (100, class_defs)]: struct.pack_into("<I", data, offset, value)
    for index, name in enumerate(names):
        struct.pack_into("<I", data, string_ids + index * 4, len(data)); struct.pack_into("<I", data, type_ids + index * 4, index)
        encoded = name.encode("ascii"); data.extend(bytes([len(encoded)]) + encoded + b"\0")
    for index, type_id in enumerate(defined): struct.pack_into("<I", data, class_defs + index * 32, type_id)
    return data

class PlatformShadowingTest(unittest.TestCase):
    def archive(self, entries):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        path = Path(temporary.name) / "input.zip"
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in entries.items(): archive.writestr(name, data)
        return module.inspect(path)
    def test_framework_references_are_allowed(self):
        report = self.archive({"classes.dex": dex(["Lapp/Test;", "Landroid/content/res/XmlResourceParser;"], [0])})
        self.assertTrue(report["passed"]); self.assertEqual(1, report["classDefinitions"])
    def test_multidex_platform_definition_is_rejected(self):
        report = self.archive({"classes.dex": dex(["Lapp/Test;"], [0]), "classes2.dex": dex(["Lorg/xmlpull/v1/XmlPullParser;"], [0])})
        self.assertFalse(report["passed"]); self.assertEqual("classes2.dex", report["platformDefinitions"][0]["entry"])
    def test_upstream_jar_stubs_are_detected(self):
        report = self.archive({"android/util/AttributeSet.class": b"class", "com/reandroid/Reader.class": b"class"})
        self.assertFalse(report["passed"])
    def test_real_implementation_is_allowed(self):
        self.assertTrue(self.archive({"com/reandroid/arsc/chunk/TableBlock.class": b"class"})["passed"])
    def test_malformed_dex_fails_closed(self):
        with self.assertRaises(ValueError): self.archive({"classes.dex": b"dex\n035\0"})

if __name__ == "__main__": unittest.main()
