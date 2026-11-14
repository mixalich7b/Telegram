#!/usr/bin/env python3
"""Reject incomplete tunnel APKs and leaked Go build outputs before publication."""
import argparse
from pathlib import Path
import sys
import zipfile


REQUIRED_LIBRARIES = (
    "libtg-wg.so", "libtg-awg.so", "libtg-tunnel-go.so",
    "libtg-tunnel-log.so", "libtwidget.so")
FORBIDDEN = (
    "gomod/", "gocache/", "tg_wg/go/build", "tg_awg/go/build",
    "tg_tunnel/go/build", "vdso_", "libtg-wg-go.so", "libtg-awg-go.so")


def verify_apk(path, required_abis):
    with zipfile.ZipFile(path) as apk:
        entries = set(apk.namelist())
    for entry in sorted(entries):
        if any(token in entry for token in FORBIDDEN):
            raise ValueError("APK contains forbidden Go build/cache entry: " + entry)
    packaged_abis = {
        parts[1] for entry in entries
        if len(parts := entry.split("/")) == 3
        and parts[0] == "lib" and parts[2].endswith(".so")}
    for abi in sorted(set(required_abis) | packaged_abis):
        for library in REQUIRED_LIBRARIES:
            entry = f"lib/{abi}/{library}"
            if entry not in entries:
                raise ValueError("APK is missing required native library " + entry)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--abi", action="append", required=True,
                        help="Required ABI; repeat for multiple ABIs")
    args = parser.parse_args()
    try:
        verify_apk(args.apk, args.abi)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print("APK verification failed: " + str(error), file=sys.stderr)
        return 1
    print("Native APK packaging verified: " + str(args.apk))
    return 0


if __name__ == "__main__":
    sys.exit(main())
