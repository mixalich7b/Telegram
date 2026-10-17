"""Exercise preparation against a real tiny Git repository, without network access."""
import concurrent.futures
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "prepare-tgcalls.py"


class PrepareTgcallsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "upstream"
        self.source.mkdir()
        self.git("init", "-q")
        (self.source / "tgcalls").mkdir()
        (self.source / "tgcalls/route.txt").write_text("direct\n")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                 "-c", "commit.gpgsign=false", "commit", "-qm", "fixture")
        self.revision = self.git("rev-parse", "HEAD").strip()
        self.patches = self.root / "patches"
        self.patches.mkdir()
        (self.patches / "upstream-revision").write_text(self.revision + "\n")
        self.series = self.patches / "series"
        self.series.write_text("route.patch\n")
        self.patch("tunnel")
        self.output = self.root / "generated"

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.source), *args], text=True)

    def patch(self, route):
        (self.patches / "route.patch").write_text(
            "diff --git a/tgcalls/route.txt b/tgcalls/route.txt\n"
            "--- a/tgcalls/route.txt\n+++ b/tgcalls/route.txt\n"
            "@@ -1 +1 @@\n-direct\n+" + route + "\n")

    def prepare(self, success=True, output=None):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--source", str(self.source),
             "--output", str(output or self.output), "--series", str(self.series)],
            text=True, capture_output=True)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def test_clean_preparation_is_idempotent_and_ignores_dirty_checkout(self):
        (self.source / "tgcalls/route.txt").write_text("local edit\n")
        (self.source / "tgcalls/untracked.txt").write_text("local only\n")
        before = self.git("status", "--porcelain")
        self.prepare()
        route = self.output / "tgcalls/route.txt"
        self.assertEqual(route.read_text(), "tunnel\n")
        self.assertFalse((self.output / "tgcalls/untracked.txt").exists())
        timestamp = route.stat().st_mtime_ns
        self.prepare()
        self.assertEqual(route.stat().st_mtime_ns, timestamp)
        self.assertEqual(self.git("status", "--porcelain"), before)

    def test_patch_change_rebuilds_output(self):
        self.prepare()
        self.patch("blocked")
        self.prepare()
        self.assertEqual((self.output / "tgcalls/route.txt").read_text(), "blocked\n")

    def test_output_inside_parent_repository_still_applies_patch(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.prepare()
        self.assertEqual((self.output / "tgcalls/route.txt").read_text(), "tunnel\n")

    def test_missing_output_file_is_restored(self):
        self.prepare()
        (self.output / "tgcalls/route.txt").unlink()
        self.prepare()
        self.assertEqual((self.output / "tgcalls/route.txt").read_text(), "tunnel\n")

    def test_modified_output_is_restored(self):
        self.prepare()
        (self.output / "tgcalls/route.txt").write_text("direct\n")
        self.prepare()
        self.assertEqual((self.output / "tgcalls/route.txt").read_text(), "tunnel\n")

    def test_wrong_revision_fails_even_with_cached_output(self):
        self.prepare()
        (self.patches / "upstream-revision").write_text("0" * 40 + "\n")
        self.assertIn("revision", self.prepare(False).stderr)

    def test_unapplicable_patch_fails_even_with_cached_output(self):
        self.prepare()
        p = self.patches / "route.patch"
        p.write_text(p.read_text().replace("-direct", "-missing"))
        self.assertIn("patch", self.prepare(False).stderr)

    def test_concurrent_preparations_produce_one_valid_tree(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
            list(executor.map(lambda _: self.prepare(), range(4)))
        self.assertEqual((self.output / "tgcalls/route.txt").read_text(), "tunnel\n")

    def test_output_cannot_replace_source_or_its_parent(self):
        for output in [self.source, self.root, self.source / "build"]:
            self.prepare(False, output)
        self.assertEqual((self.source / "tgcalls/route.txt").read_text(), "direct\n")


if __name__ == "__main__":
    unittest.main()
