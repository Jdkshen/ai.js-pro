import importlib.util
import json
import struct
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("xiangqi_release", Path(__file__).parents[1] / "xiangqi-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "project"
        self.root.mkdir()
        (self.root / "project.json").write_text(json.dumps({"versionName": "0.6.27", "versionCode": 27}))
        (self.root / "main.js").write_text("// @engine quickjs\nvar current = 1;\n")
        release.prepare(self.root)

    def apk(self, change=None):
        target = Path(self.tmp.name) / "test.apk"
        with zipfile.ZipFile(target, "w") as out:
            for file in self.root.rglob("*"):
                if not file.is_file():
                    continue
                name = file.relative_to(self.root).as_posix()
                data = file.read_bytes()
                if change:
                    data = change(name, data)
                if data is not None:
                    out.writestr(release.APK_PREFIX + name, data)
        return target

    def test_exact_package_and_deterministic_prepare(self):
        first = (self.root / "release-manifest.json").read_bytes()
        release.prepare(self.root)
        self.assertEqual(first, (self.root / "release-manifest.json").read_bytes())
        self.assertEqual(release.verify_apk(self.apk(), self.root)["version"], "0.6.27")

    def test_same_version_changed_source_rejected(self):
        apk = self.apk()
        (self.root / "main.js").write_text("// @engine quickjs\nvar current = 2;\n")
        with self.assertRaisesRegex(ValueError, "source changed"):
            release.verify_apk(apk, self.root)
        release.prepare(self.root)
        with self.assertRaisesRegex(ValueError, "old Xiangqi manifest"):
            release.verify_apk(apk, self.root)

    def test_android_utf8_paths_without_zip_flag(self):
        apk = self.apk()
        raw = bytearray(apk.read_bytes())
        for signature, flag_offset in [(b"PK\x03\x04", 6), (b"PK\x01\x02", 8)]:
            offset = 0
            while True:
                offset = raw.find(signature, offset)
                if offset < 0:
                    break
                flags = struct.unpack_from("<H", raw, offset + flag_offset)[0]
                struct.pack_into("<H", raw, offset + flag_offset, flags & ~0x800)
                offset += 4
        apk.write_bytes(raw)
        self.assertEqual(release.verify_apk(apk, self.root)["version"], "0.6.27")

    def test_missing_module_rejected(self):
        with self.assertRaisesRegex(ValueError, "file list differs"):
            release.verify_apk(self.apk(lambda name, data: None if name == "main.js" else data), self.root)

    def test_modified_package_rejected(self):
        with self.assertRaisesRegex(ValueError, "modified/stale"):
            release.verify_apk(self.apk(lambda name, data: b"old script" if name == "main.js" else data), self.root)

    def test_extra_deleted_module_rejected(self):
        apk = self.apk()
        with zipfile.ZipFile(apk, "a") as out:
            out.writestr(release.APK_PREFIX + "src/old-classifier.js", "old")
        with self.assertRaisesRegex(ValueError, "extra="):
            release.verify_apk(apk, self.root)

    def test_new_untracked_file_changes_fingerprint(self):
        old = release.snapshot(self.root)["sourceSha256"]
        (self.root / "new-module.js").write_text("new")
        self.assertNotEqual(old, release.snapshot(self.root)["sourceSha256"])

    def test_version_module_must_be_generated(self):
        (self.root / "src/app/version.js").write_text("module.exports = {VERSION:'wrong'}")
        with self.assertRaisesRegex(ValueError, "version.js differs"):
            release.verify_source(self.root)


if __name__ == "__main__":
    unittest.main()
