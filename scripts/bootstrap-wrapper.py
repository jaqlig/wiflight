#!/usr/bin/env python3
"""Fetch the official, checksum-pinned Gradle wrapper JAR; no third-party Python packages."""
import hashlib
from pathlib import Path
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "gradle/wrapper/gradle-wrapper.jar"
URL = "https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
# Official reference: https://gradle.org/release-checksums/#8.13
SHA256 = "81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"


def main():
    if DESTINATION.exists():
        content = DESTINATION.read_bytes()
    else:
        print("Downloading the official Gradle 8.13 wrapper…", flush=True)
        with urllib.request.urlopen(URL, timeout=20) as response:
            content = response.read(1024 * 1024)
    if hashlib.sha256(content).hexdigest() != SHA256:
        raise RuntimeError("Gradle wrapper checksum mismatch; refusing to use it.")
    if not DESTINATION.exists():
        temporary = DESTINATION.with_suffix(".jar.tmp")
        temporary.write_bytes(content)
        temporary.replace(DESTINATION)
    print("Gradle wrapper verified.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Wrapper setup failed: {error}", file=sys.stderr)
        sys.exit(1)
