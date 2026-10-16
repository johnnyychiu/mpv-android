#!/usr/bin/env python3
"""Prepare verified upstream native binaries for app-only development (Python 3.9+)."""

import argparse
import hashlib
import re
import shutil
import stat
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path


RELEASE_TAG = "2026-09-17"
RELEASE_COMMIT = "fdf74f6830c47dbaa8a22ac79726e8303f1db5af"
APK_URL = (
    "https://github.com/mpv-android/mpv-android/releases/download/"
    f"{RELEASE_TAG}/app-default-universal-release.apk"
)
APK_SHA256 = "c3b505e45b919b767b9867e7f0d16fd823d77bc7c9a6e6b181b80ea60c018a70"
ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
NATIVE_PATHS = ("app/src/main/jni", "app/src/main/java/is/xyz/mpv/MPVLib.kt")
LIBRARY_PATH = re.compile(r"lib/(armeabi-v7a|arm64-v8a|x86|x86_64)/(lib[A-Za-z0-9_.+-]+\.so)")


def git(repo, *args):
    result = subprocess.run(
        ["git", "-C", str(repo), *args], capture_output=True, check=False
    )
    if result.returncode:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(detail or "Git source verification failed.")
    return result.stdout


def verify_sources(repo):
    try:
        commit = git(repo, "rev-parse", "--verify", f"refs/tags/{RELEASE_TAG}^{{commit}}")
    except RuntimeError as error:
        raise RuntimeError(
            f"Missing upstream tag {RELEASE_TAG}. Fetch it first:\n"
            f"git fetch https://github.com/mpv-android/mpv-android.git tag {RELEASE_TAG}"
        ) from error
    if commit.decode().strip() != RELEASE_COMMIT:
        raise RuntimeError(f"Tag {RELEASE_TAG} does not match the pinned upstream commit.")

    expected = git(repo, "ls-tree", "-r", "--name-only", "-z", RELEASE_TAG, "--", *NATIVE_PATHS)
    expected_paths = set(expected.decode("utf-8").rstrip("\0").split("\0"))
    current_paths = {
        path.relative_to(repo).as_posix()
        for path in (repo / NATIVE_PATHS[0]).rglob("*")
        if path.is_file()
    } | {NATIVE_PATHS[1]}
    if expected_paths != current_paths:
        raise RuntimeError("Native source files differ from the pinned release. Build natives from source instead.")
    for name in sorted(expected_paths):
        path = repo / name
        if not path.is_file() or path.is_symlink():
            raise RuntimeError(f"Missing or symlinked native source: {name}")
        # Git commonly checks text out as CRLF on Windows.
        current = path.read_bytes().replace(b"\r\n", b"\n")
        reference = git(repo, "show", f"{RELEASE_TAG}:{name}").replace(b"\r\n", b"\n")
        if current != reference:
            raise RuntimeError(
                f"{name} differs from {RELEASE_TAG}. Build natives from source instead."
            )
    print(f"Native interface matches upstream {RELEASE_TAG} ({RELEASE_COMMIT}).")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked_destination(repo, relative):
    destination = repo / relative
    if not destination.resolve().is_relative_to(repo) or destination.is_symlink():
        raise RuntimeError(f"Destination escapes the repository or is a symlink: {relative}")
    return destination


def download_apk(repo):
    apk = checked_destination(repo, Path("build/native-cache") / f"mpv-{RELEASE_TAG}.apk")
    if apk.exists():
        return apk
    apk.parent.mkdir(parents=True, exist_ok=True)
    partial = checked_destination(repo, apk.relative_to(repo).with_suffix(".apk.part"))
    request = urllib.request.Request(APK_URL, headers={"User-Agent": "mpv-android-youtube-native-prep"})
    print(f"Downloading pinned native libraries from {APK_URL}")
    with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)
    if sha256(partial) != APK_SHA256:
        raise RuntimeError("Downloaded APK failed its pinned SHA-256 check; no native files were extracted.")
    partial.replace(apk)
    return apk


def extract_libraries(repo, apk, verify_only=False):
    if sha256(apk) != APK_SHA256:
        raise RuntimeError("APK failed its pinned SHA-256 check; no native files were extracted.")
    with zipfile.ZipFile(apk) as archive:
        entries = {}
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.startswith("lib/"):
                continue
            match = LIBRARY_PATH.fullmatch(entry.filename)
            if not match or stat.S_ISLNK(entry.external_attr >> 16):
                raise RuntimeError(f"Unexpected native archive entry: {entry.filename}")
            abi, filename = match.groups()
            if (abi, filename) in entries:
                raise RuntimeError(f"Duplicate native archive entry: {entry.filename}")
            destination = checked_destination(repo, Path("app/src/main/libs") / abi / filename)
            entries[abi, filename] = (entry, destination)
        for abi in ABIS:
            if not all((abi, name) in entries for name in ("libmpv.so", "libplayer.so")):
                raise RuntimeError(f"Pinned APK lacks required mpv libraries for {abi}.")
        if verify_only:
            print(f"Verified {len(entries)} native entries and APK SHA-256: {APK_SHA256}")
            return
        # Every path and required ABI has been checked before any native files are written.
        for entry, destination in entries.values():
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as source, destination.open("wb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
    print(f"Prepared {len(entries)} verified .so files in app/src/main/libs ({', '.join(ABIS)}).")
    print(f"APK SHA-256: {APK_SHA256}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="Use a local copy of the exact pinned universal release APK")
    parser.add_argument("--verify-only", action="store_true", help="Check sources and APK without extracting native files")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    verify_sources(repo)
    apk = args.apk.resolve() if args.apk else download_apk(repo)
    extract_libraries(repo, apk, verify_only=args.verify_only)


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"Native preparation failed: {error}", file=sys.stderr)
        sys.exit(1)
