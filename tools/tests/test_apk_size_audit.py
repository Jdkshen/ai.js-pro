import importlib.util
import io
import json
import os
import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "audit_apk_size", Path(__file__).parents[1] / "audit-apk-size.py")
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


class ApkSizeAuditTest(unittest.TestCase):
    def setUp(self):
        # Use flat unique files in the process temp dir. This also works in the
        # restricted workspace sandbox, where creating a new sub-directory is not
        # allowed but writing into an existing temp directory is.
        self.root = Path(tempfile.gettempdir())
        self.created = []

    def tearDown(self):
        for path in reversed(self.created):
            try:
                path.unlink()
            except FileNotFoundError:
                pass

    def temp_file(self, suffix):
        handle, name = tempfile.mkstemp(prefix="apk-size-audit-", suffix=suffix, dir=self.root)
        os.close(handle)
        path = Path(name)
        self.created.append(path)
        return path

    def make_apk(self, name="app.apk"):
        # mkstemp creates the file in an existing directory; ZipFile then truncates it.
        path = self.temp_file(".apk")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("classes.dex", b"dex\n035\0" + b"x" * 512)
            archive.writestr("lib/arm64-v8a/libfoo.so", b"\0" * 4096)
            archive.writestr("assets/sample/中文.js", b"console.log(1);\n" * 32)
            archive.writestr("assets/sample/readme.txt", b"hello\n" * 16)
            archive.writestr("res/layout/main.xml", b"<x/>\n" * 8)
            archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        return path

    def clear_utf8_flags(self, path):
        raw = bytearray(path.read_bytes())
        for signature, flag_offset in ((b"PK\x03\x04", 6), (b"PK\x01\x02", 8)):
            offset = 0
            while True:
                offset = raw.find(signature, offset)
                if offset < 0:
                    break
                flags = struct.unpack_from("<H", raw, offset + flag_offset)[0]
                struct.pack_into("<H", raw, offset + flag_offset, flags & ~0x800)
                offset += 4
        path.write_bytes(raw)

    def test_groups_raw_and_compressed_totals(self):
        result = audit.analyze_apk(self.make_apk(), top=3)
        self.assertEqual(result["zip"]["entries"], 6)
        self.assertGreater(result["zip"]["raw_bytes"], result["zip"]["compressed_bytes"])
        groups = {item["group"]: item for item in result["groups"]}
        self.assertIn("dex", groups)
        self.assertIn("lib/arm64-v8a", groups)
        self.assertIn("assets/sample", groups)
        self.assertIn("res", groups)
        self.assertEqual(groups["assets/sample"]["entries"], 2)
        self.assertEqual(len(result["top_entries"]), 3)
        self.assertAlmostEqual(
            result["zip"]["compressed_ratio"],
            result["zip"]["compressed_bytes"] / result["zip"]["raw_bytes"])

    def test_valid_utf8_name_is_counted_even_without_zip_flag(self):
        path = self.make_apk()
        result = audit.analyze_apk(path)
        self.assertEqual(result["utf8_names"]["non_ascii_valid"], 1)
        self.assertEqual(result["utf8_names"]["utf8_flag_non_ascii"], 1)
        self.assertEqual(result["utf8_names"]["invalid"], 0)

        self.clear_utf8_flags(path)
        result = audit.analyze_apk(path)
        self.assertEqual(result["utf8_names"]["non_ascii_valid"], 1)
        self.assertEqual(result["utf8_names"]["utf8_flag_entries"], 0)
        self.assertEqual(result["utf8_names"]["non_ascii_without_flag"], 1)
        self.assertEqual(result["utf8_names"]["invalid"], 0)

    def test_invalid_utf8_name_is_counted(self):
        path = self.make_apk()
        raw = path.read_bytes()
        needle = "assets/sample/中文.js".encode("utf-8")
        replacement = b"assets/sample/" + b"\xff" * 6 + b".js"
        self.assertEqual(len(needle), len(replacement))
        self.assertIn(needle, raw)
        path.write_bytes(raw.replace(needle, replacement))
        result = audit.analyze_apk(path)
        self.assertGreaterEqual(result["utf8_names"]["invalid"], 1)
        self.assertTrue(result["utf8_names"]["invalid_samples"])

    def test_compare_and_budget(self):
        baseline = audit.analyze_apk(self.make_apk("baseline.apk"))
        current = audit.analyze_apk(self.make_apk("current.apk"))
        comparison = audit.compare_with_baseline(current, baseline)
        self.assertEqual(comparison["compressed_bytes_delta"], 0)

        failures = audit.evaluate_budget(current, baseline, max_total_mib=0.0001)
        self.assertTrue(failures)
        failures = audit.evaluate_budget(current, baseline, max_growth_mib=0)
        self.assertFalse(failures)
        failures = audit.evaluate_budget(
            {"zip": {"compressed_bytes": 2000}}, {"zip": {"compressed_bytes": 1000}},
            max_growth_percent=10)
        self.assertTrue(failures)
        failures = audit.evaluate_budget(
            {"zip": {"compressed_bytes": 1000}}, None, max_growth_mib=1)
        self.assertTrue(failures)

    def test_report_and_cli_json(self):
        path = self.make_apk()
        result = audit.analyze_apk(path)
        text = audit.format_report(result)
        self.assertIn("Compressed contribution", text)
        self.assertIn("Non-ASCII names valid UTF-8: 1", text)
        self.assertIn("BUDGET: PASS", text)

        json_path = self.temp_file(".json")
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            code = audit.main([str(path), "--json", str(json_path), "--top", "2"])
        self.assertEqual(code, 0)
        payload = json.loads(json_path.read_text(encoding="utf-8"))
        self.assertEqual(payload["current"]["zip"]["entries"], 6)
        self.assertEqual(len(payload["current"]["top_entries"]), 2)
        self.assertIn("UTF-8 entry names", stdout.getvalue())

    def test_classify_entry(self):
        self.assertEqual(audit.classify_entry("classes2.dex"), "dex")
        self.assertEqual(audit.classify_entry("lib/x86/libflutter.so"), "lib/x86")
        self.assertEqual(audit.classify_entry("assets/sample/a.js"), "assets/sample")
        self.assertEqual(audit.classify_entry("assets/template.apk"), "assets/template.apk")
        self.assertEqual(audit.classify_entry("resources.arsc"), "manifest/resources")


if __name__ == "__main__":
    unittest.main()
