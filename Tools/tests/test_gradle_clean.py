"""Exercise the real cleanup script with Gradle and isolated build fixtures."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]


class GradleCleanTest(unittest.TestCase):
    def test_clean_contract(self):
        with tempfile.TemporaryDirectory(prefix="telegram-clean-") as temporary:
            root = Path(temporary)
            (root / "settings.gradle").write_text("include ':app'\n")
            (root / "app").mkdir()
            script = REPOSITORY / "gradle/clean.gradle"
            self.assertTrue(script.is_file(), "Gradle cleanup script is missing")
            shutil.copyfile(script, root / "clean.gradle")
            sources = root / "buildSrc/src/main/java/fixture"
            sources.mkdir(parents=True)
            (root / "buildSrc/build.gradle").write_text("plugins { id 'java' }\n")
            (sources / "BuildWriter.java").write_text('''
package fixture;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
public class BuildWriter {
    public static void write(File output) throws IOException {
        new LateWriter().write(output);
    }
}
class LateWriter {
    void write(File output) throws IOException {
        Files.createDirectories(output.toPath().getParent());
        Files.write(output.toPath(), "built".getBytes(StandardCharsets.UTF_8));
    }
}
''')
            (root / "build.gradle").write_text('''
apply from: 'clean.gradle'
allprojects {
    tasks.named('assemble') {
        doLast {
            def output = layout.buildDirectory.file('assembled.txt').get().asFile
            fixture.BuildWriter.write(output)
        }
    }
}
''')

            def gradle(*tasks):
                result = subprocess.run(
                    [str(REPOSITORY / "gradlew"), "--no-daemon", "--offline",
                     "--console=plain", "-p", str(root), *tasks],
                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                self.assertEqual(result.returncode, 0, result.stdout)

            def seed(project):
                for name in ("build", ".cxx", ".externalNativeBuild"):
                    folder = project / name / "gomod" / "nested"
                    folder.mkdir(parents=True)
                    (folder / "source.go").write_text("package fixture\n")
                    (folder / "source.go").chmod(0o444)
                    folder.chmod(0o555)
                    folder.parent.chmod(0o555)

            outside = root / "source-input"
            outside.mkdir()
            (outside / "keep.txt").write_text("source")
            outside.chmod(0o555)
            try:
                seed(root)
                seed(root / "app")
                os.symlink(outside, root / ".cxx" / "external")
                os.symlink(root / "missing", root / ".cxx" / "dangling")

                gradle(":app:clean")
                self.assertTrue((root / "build").exists())
                for name in ("build", ".cxx", ".externalNativeBuild"):
                    self.assertFalse((root / "app" / name).exists())

                seed(root / "app")
                gradle(":clean")
                for project in (root, root / "app"):
                    for name in ("build", ".cxx", ".externalNativeBuild"):
                        self.assertFalse((project / name).exists())
                self.assertFalse((root / "buildSrc/build").exists())
                self.assertEqual((outside / "keep.txt").read_text(), "source")
                self.assertEqual(outside.stat().st_mode & 0o777, 0o555)

                gradle("clean", "assemble")
                for project in (root, root / "app"):
                    self.assertEqual((project / "build/assembled.txt").read_text(), "built")
                self.assertFalse((root / "buildSrc/build").exists())
            finally:
                outside.chmod(0o755)
                # Also allow fixture disposal if a cleanup assertion fails.
                for parent, directories, _ in os.walk(root, followlinks=False):
                    for directory in directories:
                        path = Path(parent) / directory
                        if not path.is_symlink():
                            path.chmod(0o755)


if __name__ == "__main__":
    unittest.main()
