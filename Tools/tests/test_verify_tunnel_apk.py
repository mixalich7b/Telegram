import importlib.util
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "verify_tunnel_apk", Path(__file__).resolve().parents[1] / "verify-tunnel-apk.py")
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)

LIBRARIES = (
    "libtg-wg.so", "libtg-awg.so", "libtg-tunnel-go.so",
    "libtg-tunnel-log.so", "libtwidget.so")


class VerifyTunnelApkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "app.apk"

    def entries(self, abi="arm64-v8a"):
        return [f"lib/{abi}/{name}" for name in LIBRARIES]

    def verify(self, entries):
        with zipfile.ZipFile(self.apk, "w") as apk:
            for entry in entries:
                apk.writestr(entry, b"fixture")
        verifier.verify_apk(self.apk, ["arm64-v8a"])

    def test_arm64_only_release_passes(self):
        self.verify(self.entries())

    def test_complete_multi_abi_apk_passes(self):
        self.verify(self.entries() + self.entries("x86_64"))

    def test_each_required_library_is_checked(self):
        for library in LIBRARIES:
            with self.subTest(library=library), self.assertRaisesRegex(ValueError, library):
                self.verify([entry for entry in self.entries() if not entry.endswith(library)])

    def test_required_abi_must_be_present(self):
        with self.assertRaisesRegex(ValueError, "arm64-v8a"):
            self.verify(self.entries("x86_64"))

    def test_extra_packaged_abi_must_also_be_complete(self):
        with self.assertRaisesRegex(ValueError, "x86_64/libtg-awg.so"):
            self.verify(self.entries() + ["lib/x86_64/libtg-wg.so"])

    def test_cache_and_separate_go_runtimes_are_rejected(self):
        for entry in ["assets/gomod/dependency", "assets/gocache/object",
                      "assets/tg_tunnel/go/build/cache", "assets/vdso_arm64.so",
                      "lib/arm64-v8a/libtg-wg-go.so", "lib/arm64-v8a/libtg-awg-go.so"]:
            with self.subTest(entry=entry), self.assertRaisesRegex(ValueError, "forbidden"):
                self.verify(self.entries() + [entry])

    def test_invalid_apk_is_rejected(self):
        self.apk.write_bytes(b"not a ZIP archive")
        with self.assertRaises(zipfile.BadZipFile):
            verifier.verify_apk(self.apk, ["arm64-v8a"])

    def test_cli_fails_before_publication_for_invalid_apk(self):
        self.apk.write_bytes(b"not a ZIP archive")
        result = subprocess.run(
            [sys.executable, "-B", str(SPEC.origin), "--abi", "arm64-v8a", str(self.apk)],
            capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("APK verification failed", result.stderr)


if __name__ == "__main__":
    unittest.main()
