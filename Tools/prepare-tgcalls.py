#!/usr/bin/env python3
"""Prepare the pinned tgcalls sources plus local patches outside the JNI tree."""
import argparse
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile


STAMP = ".tunnel-sources.json"


def git(source, *args):
    return subprocess.check_output(["git", "-C", str(source), *args], stderr=subprocess.PIPE)


def inventory(root):
    """Detect edited/missing generated sources instead of trusting a stamp alone."""
    result = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError("Unexpected symlink in prepared sources: " + str(path))
        if path.is_file() and path.name != STAMP:
            result[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def prepare(source, output, series):
    source, output, series = source.resolve(), output.resolve(), series.resolve()
    if source == output or source in output.parents or output in source.parents:
        raise ValueError("Output must be separate from the upstream source tree")
    if Path(git(source, "rev-parse", "--show-toplevel").decode().strip()).resolve() != source:
        raise ValueError("Source must be an initialized tgcalls repository root")
    revision = (series.parent / "upstream-revision").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Invalid upstream revision")
    if git(source, "rev-parse", "HEAD").decode().strip() != revision:
        raise ValueError("tgcalls revision differs from patches/tgcalls/upstream-revision; update submodules")
    patches = []
    for name in series.read_text().splitlines():
        name = name.strip()
        if not name or name.startswith("#"):
            continue
        path = (series.parent / name).resolve()
        if path.parent != series.parent:
            raise ValueError("Patch must be next to its series file")
        patches.append((name, path.read_bytes()))
    if not patches:
        raise ValueError("Empty tunnel patch series")
    fingerprint = hashlib.sha256()
    for data in [revision.encode(), Path(__file__).read_bytes(), series.read_bytes()]:
        fingerprint.update(data)
    for name, data in patches:
        fingerprint.update(name.encode())
        fingerprint.update(data)
    key = fingerprint.hexdigest()
    output.parent.mkdir(parents=True, exist_ok=True)
    # Gradle and CMake/IDE invocations, including different ABIs, share this lock.
    with (output.parent / (output.name + ".lock")).open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        stamp = output / STAMP
        if stamp.is_file():
            try:
                previous = json.loads(stamp.read_text())
                if previous["fingerprint"] == key and previous["files"] == inventory(output):
                    return
            except (ValueError, KeyError, OSError):
                pass
        with tempfile.TemporaryDirectory(prefix=output.name + "-", dir=output.parent) as temp:
            prepared = Path(temp) / "sources"
            prepared.mkdir()
            # Archive objects, never the possibly dirty developer checkout.
            archive = git(source, "archive", "--format=tar", revision, "tgcalls")
            with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
                for member in tar.getmembers():
                    path = Path(member.name)
                    if path.is_absolute() or ".." in path.parts or not (member.isfile() or member.isdir()):
                        raise ValueError("Unsupported archive entry: " + member.name)
                    target = prepared / path
                    if member.isdir():
                        target.mkdir(parents=True, exist_ok=True)
                    else:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(tar.extractfile(member).read())
                        target.chmod(member.mode & 0o777)
            for name, data in patches:
                for options in [("--check",), ()]:
                    result = subprocess.run(
                        ["git", "apply", *options, "-"], cwd=prepared,
                        # Do not discover the Android parent repo and silently
                        # skip patch paths outside its current subdirectory.
                        env={**os.environ, "GIT_CEILING_DIRECTORIES": str(prepared.parent)},
                        input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    if result.returncode:
                        raise ValueError("Cannot apply patch " + name + ": " + result.stderr.decode())
            (prepared / STAMP).write_text(json.dumps({
                "revision": revision, "fingerprint": key, "files": inventory(prepared)
            }, sort_keys=True) + "\n")
            # The lock prevents consumers from observing an incomplete preparation.
            if output.exists():
                os.rename(output, Path(temp) / "previous")
            os.rename(prepared, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--series", type=Path, required=True)
    args = parser.parse_args()
    try:
        prepare(args.source, args.output, args.series)
    except (OSError, ValueError, subprocess.CalledProcessError, tarfile.TarError) as error:
        print("tgcalls preparation failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
