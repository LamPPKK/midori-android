#!/usr/bin/env python3
"""Fail-closed verifier for the source-built Android adblock-rust package."""

from __future__ import annotations

import hashlib
import os
import re
import stat
import struct
import subprocess
import sys
from pathlib import Path

ABIS = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86_64": (2, 62),
}
EXPORTS = {
    "xanh_adblock_core_version",
    "xanh_adblock_engine_create_default",
    "xanh_adblock_engine_should_block",
    "xanh_adblock_engine_free",
}
MAX_LIBRARY_BYTES = 128 * 1024 * 1024
LOWER_SHA = re.compile(r"^[0-9a-f]{64}$")
LOWER_GIT_SHA = re.compile(r"^[0-9a-f]{40}$")
ET_DYN = 3


def read_key_values(path: Path) -> dict[str, str]:
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 64 * 1024:
        raise ValueError(f"missing, linked or oversized metadata: {path}")
    result: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        if line.count("=") != 1:
            raise ValueError(f"malformed metadata line in {path}: {line!r}")
        key, value = line.split("=", 1)
        if not key or not value or key in result:
            raise ValueError(f"duplicate or empty metadata field in {path}: {key!r}")
        result[key] = value
    return result


def find_readelf(ndk_version: str) -> Path:
    candidates: list[Path] = []
    for variable in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"):
        if value := os.environ.get(variable):
            candidates.extend(Path(value).glob("toolchains/llvm/prebuilt/*/bin/llvm-readelf"))
    if sdk := os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME"):
        candidates.extend(
            Path(sdk).glob(f"ndk/{ndk_version}/toolchains/llvm/prebuilt/*/bin/llvm-readelf")
        )
    # llvm-readelf is a multicall symlink to llvm-readobj in the NDK. Preserve argv[0]:
    # resolving that symlink changes the tool's output format and invalidates the parser below.
    matches = sorted({candidate.absolute() for candidate in candidates if candidate.is_file()})
    if len(matches) != 1:
        raise ValueError(f"expected exactly one NDK llvm-readelf, found {len(matches)}")
    return matches[0]


def verify_completion_marker(path: Path) -> None:
    try:
        metadata = path.lstat()
    except FileNotFoundError as error:
        raise ValueError(f"missing native package completion marker: {path}") from error
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_size != 0:
        raise ValueError(f"completion marker must be a real zero-byte file: {path}")


def resolve_real_directory(path: Path) -> Path:
    try:
        metadata = path.lstat()
    except FileNotFoundError as error:
        raise ValueError(f"missing native package directory: {path}") from error
    if not stat.S_ISDIR(metadata.st_mode):
        raise ValueError(f"native package must be a real directory: {path}")
    return path.resolve(strict=True)


def dynamic_function_exports(output: str) -> set[str]:
    exports: set[str] = set()
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].endswith(":") or not fields[0][:-1].isdigit():
            continue
        symbol_type, binding, visibility, section_index, name = fields[3:8]
        if (
            symbol_type == "FUNC"
            and binding in {"GLOBAL", "WEAK"}
            and visibility == "DEFAULT"
            and section_index.isdecimal()
        ):
            exports.add(name)
    return exports


def read_bounded_regular_file(path: Path, maximum_bytes: int) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise ValueError(f"cannot open real native library: {path}") from error
    try:
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_size <= 0
            or before.st_size > maximum_bytes
        ):
            raise ValueError(f"missing, linked, empty or oversized native library: {path}")
        chunks: list[bytes] = []
        remaining = maximum_bytes + 1
        while remaining:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        data = b"".join(chunks)
        after = os.fstat(descriptor)
        if (
            len(data) != before.st_size
            or len(data) > maximum_bytes
            or (before.st_dev, before.st_ino, before.st_size)
            != (after.st_dev, after.st_ino, after.st_size)
        ):
            raise ValueError(f"native library changed or exceeded its bound while reading: {path}")
        return data
    finally:
        os.close(descriptor)


def verify_elf(path: Path, elf_class: int, machine: int, readelf: Path) -> str:
    data = read_bounded_regular_file(path, MAX_LIBRARY_BYTES)
    if data[:7] != b"\x7fELF" + bytes((elf_class, 1, 1)):
        raise ValueError(f"unexpected ELF class/endianness/version: {path}")
    minimum_header_size = 64 if elf_class == 2 else 52
    if len(data) < minimum_header_size:
        raise ValueError(f"truncated ELF header: {path}")
    if struct.unpack_from("<H", data, 16)[0] != ET_DYN:
        raise ValueError(f"native library is not an ELF shared object: {path}")
    if struct.unpack_from("<H", data, 18)[0] != machine:
        raise ValueError(f"ELF machine does not match ABI directory: {path}")
    if elf_class == 2:
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phentsize = struct.unpack_from("<H", data, 54)[0]
        phnum = struct.unpack_from("<H", data, 56)[0]
        align_offset = 48
        align_format = "<Q"
    else:
        phoff = struct.unpack_from("<I", data, 28)[0]
        phentsize = struct.unpack_from("<H", data, 42)[0]
        phnum = struct.unpack_from("<H", data, 44)[0]
        align_offset = 28
        align_format = "<I"
    if phentsize <= align_offset or phnum == 0 or phnum > 4096:
        raise ValueError(f"invalid ELF program-header table: {path}")
    load_alignments: list[int] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        if offset + phentsize > len(data):
            raise ValueError(f"truncated ELF program-header table: {path}")
        if struct.unpack_from("<I", data, offset)[0] == 1:
            load_alignments.append(struct.unpack_from(align_format, data, offset + align_offset)[0])
    if not load_alignments or any(alignment < 16 * 1024 for alignment in load_alignments):
        raise ValueError(f"native library is not 16 KiB ELF aligned: {path}")

    completed = subprocess.run(
        [str(readelf), "--dyn-syms", "--wide", str(path)],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    symbols = dynamic_function_exports(completed.stdout)
    missing = sorted(EXPORTS - symbols)
    if missing:
        raise ValueError(f"missing native exports in {path}: {', '.join(missing)}")
    return hashlib.sha256(data).hexdigest()


def verify(package: Path, lock_path: Path) -> None:
    package = resolve_real_directory(package)
    lock = read_key_values(lock_path)
    manifest = read_key_values(package / "ADBLOCK_CORE.manifest")
    required_lock = {
        "format",
        "core_git_revision",
        "core_version",
        "adblock_rust_version",
        "adblock_rust_revision",
        "rust_version",
        "ndk_version",
        "android_api",
    }
    if set(lock) != required_lock:
        raise ValueError("ADBLOCK_CORE.lock has an unexpected field set")
    if lock["format"] != "1" or not LOWER_GIT_SHA.fullmatch(lock["core_git_revision"]):
        raise ValueError("ADBLOCK_CORE.lock has an invalid format or Git revision")
    required_manifest = required_lock | {f"sha256.{abi}" for abi in ABIS}
    if set(manifest) != required_manifest:
        raise ValueError("native manifest has an unexpected field set")
    for key in required_lock:
        if manifest[key] != lock[key]:
            raise ValueError(f"native manifest does not match Android lock for {key}")

    allowed_top = {"ADBLOCK_CORE.manifest", ".complete", *ABIS}
    if {entry.name for entry in package.iterdir()} != allowed_top:
        raise ValueError("native package has missing or unexpected top-level entries")
    verify_completion_marker(package / ".complete")
    readelf = find_readelf(lock["ndk_version"])
    for abi, (elf_class, machine) in ABIS.items():
        directory = package / abi
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError(f"invalid ABI directory: {directory}")
        entries = list(directory.iterdir())
        if [entry.name for entry in entries] != ["libxanh_adblock_core.so"]:
            raise ValueError(f"unexpected files in ABI directory: {directory}")
        library = entries[0]
        digest = verify_elf(library, elf_class, machine, readelf)
        if not LOWER_SHA.fullmatch(manifest[f"sha256.{abi}"]) or digest != manifest[f"sha256.{abi}"]:
            raise ValueError(f"native library digest mismatch: {library}")


def main() -> int:
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} PACKAGE_DIRECTORY ADBLOCK_CORE.lock", file=sys.stderr)
        return 2
    try:
        verify(Path(sys.argv[1]), Path(sys.argv[2]))
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Android adblock native package rejected: {error}", file=sys.stderr)
        return 1
    print("Verified pinned Android adblock native package")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
