#!/usr/bin/env python3
"""Patch the bundled JDK's embedded identity to com.rigel.weather-host.

WebKit keys ALL persistent website data (sign-ins, cookies, storage) on the
process bundle identifier. For the java process the JDK-folder Info.plist is
rejected (its CFBundleExecutable is libjli.dylib, which never matches the
running `java`), so CoreFoundation falls back to the __TEXT,__info_plist
section embedded inside the java launcher binary itself — which ships with
the JDK vendor's identifier (com.azul.zulu.java / net.java.openjdk.java).
Every JDK or machine difference therefore "forgets" every account.

The original Rigel identity was com.rigel.weather-host
(~/Library/WebKit/com.rigel.weather-host). This script rewrites the embedded
plist to that identifier, byte-for-byte within the existing section, and
re-signs the binary ad-hoc. Run it whenever the bundled jdk/ is replaced.
"""

import subprocess
import struct
import sys
from pathlib import Path

IDENTITY = "com.rigel.weather-host"
VENDORS = ("com.azul.zulu.java", "net.java.openjdk.java", "com.oracle.java.jdk",
           "com.azul.zulu.jdk", "net.java.openjdk.jdk")


def info_plist_section(path: Path):
    data = path.read_bytes()
    ncmds = struct.unpack_from("<I", data, 16)[0]
    p = 32  # first load command (arm64 64-bit header is 32 bytes)
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack_from("<II", data, p)
        if cmd == 0x19:  # LC_SEGMENT_64
            nsects = struct.unpack_from("<I", data, p + 64)[0]
            sp = p + 72
            for i in range(nsects):
                sect = data[sp:sp + 80]
                name = sect[0:16].rstrip(b"\0").decode()
                if "info_pl" in name:
                    _addr, size, offset = struct.unpack_from("<QQI", sect, 32)
                    return offset, size
                sp += 80
        if cmdsize == 0:
            break
        p += cmdsize
    return None


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch-java-identity.py <path-to-java-binary>")
        return 1
    java = Path(sys.argv[1]).resolve()
    if not java.is_file():
        print(f"no such binary: {java}")
        return 1

    found = info_plist_section(java)
    if not found:
        print(f"{java}: no embedded __info_plist section (nothing to patch)")
        return 1
    offset, size = found

    data = bytearray(java.read_bytes())
    region = bytes(data[offset:offset + size])
    text = region.decode("utf-8", "replace")

    if IDENTITY in text:
        print(f"{java}: already patched ({IDENTITY})")
        return 0

    current = next((v for v in VENDORS if v in text), None)
    if not current:
        print(f"{java}: unexpected embedded identity — refusing to patch")
        print(text)
        return 1

    patched = text.replace(current, IDENTITY)
    if len(patched.rstrip()) > size:
        # Not enough trailing padding to absorb the longer identifier —
        # shorten the verbose microphone description instead (its exact
        # wording is irrelevant to the JVM).
        patched = patched.replace(
            "The application is requesting access to the microphone.",
            "Microphone access.")
    if len(patched.rstrip()) > size:
        print(f"{java}: replacement does not fit the {size}-byte section")
        return 1
    patched = patched.rstrip()

    if len(patched) < size:
        patched = patched + " " * (size - len(patched))

    data[offset:offset + size] = patched.encode("utf-8")
    java.write_bytes(bytes(data))
    print(f"{java}: identity {current} -> {IDENTITY} "
          f"({size} bytes at offset {offset})")

    subprocess.run(["/usr/bin/codesign", "--force", "--sign", "-", str(java)],
                   check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["/usr/bin/codesign", "--verify", "--strict", str(java)],
                   check=True, stdout=subprocess.DEVNULL)
    print(f"{java}: re-signed ad-hoc")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
