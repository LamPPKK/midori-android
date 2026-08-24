import importlib.util
import os
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "verify_adblock_native_package.py"
SPEC = importlib.util.spec_from_file_location("verify_adblock_native_package", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def elf_bytes(elf_class: int, machine: int, *, elf_type: int = MODULE.ET_DYN) -> bytes:
    if elf_class == 2:
        data = bytearray(120)
        data[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<HH", data, 16, elf_type, machine)
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<I", data, 64, 1)
        struct.pack_into("<Q", data, 64 + 48, 16 * 1024)
    else:
        data = bytearray(84)
        data[:7] = b"\x7fELF\x01\x01\x01"
        struct.pack_into("<HH", data, 16, elf_type, machine)
        struct.pack_into("<I", data, 28, 52)
        struct.pack_into("<HH", data, 42, 32, 1)
        struct.pack_into("<I", data, 52, 1)
        struct.pack_into("<I", data, 52 + 28, 16 * 1024)
    return bytes(data)


def symbol_line(
    index: int,
    name: str,
    *,
    symbol_type: str = "FUNC",
    binding: str = "GLOBAL",
    visibility: str = "DEFAULT",
    section_index: str = "12",
) -> str:
    return (
        f"{index:6d}: 0000000000001000    32 {symbol_type:<7} "
        f"{binding:<6} {visibility:<9} {section_index:>3} {name}"
    )


def valid_dynamic_symbols() -> str:
    lines = [
        "Symbol table '.dynsym' contains 5 entries:",
        "   Num:    Value          Size Type    Bind   Vis      Ndx Name",
    ]
    for index, name in enumerate(sorted(MODULE.EXPORTS), start=1):
        binding = "WEAK" if index == 1 else "GLOBAL"
        lines.append(symbol_line(index, name, binding=binding))
    return "\n".join(lines) + "\n"


class NativePackageVerifierTests(unittest.TestCase):
    def write_elf(
        self,
        directory: str,
        *,
        elf_class: int = 2,
        machine: int = 183,
        elf_type: int = MODULE.ET_DYN,
    ) -> Path:
        path = Path(directory) / "libxanh_adblock_core.so"
        path.write_bytes(elf_bytes(elf_class, machine, elf_type=elf_type))
        return path

    def test_accepts_et_dyn_with_exact_defined_default_dynamic_exports(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library = self.write_elf(directory)
            completed = subprocess.CompletedProcess([], 0, valid_dynamic_symbols(), "")
            with mock.patch.object(MODULE.subprocess, "run", return_value=completed) as run:
                MODULE.verify_elf(library, 2, 183, Path("/ndk/bin/llvm-readelf"))
            run.assert_called_once_with(
                [
                    "/ndk/bin/llvm-readelf",
                    "--dyn-syms",
                    "--wide",
                    str(library),
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=30,
            )

    def test_find_readelf_preserves_ndk_multicall_symlink_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            ndk = Path(directory) / "ndk"
            binary = ndk / "toolchains/llvm/prebuilt/test/bin"
            binary.mkdir(parents=True)
            target = binary / "llvm-readobj"
            target.touch(mode=0o755)
            readelf = binary / "llvm-readelf"
            readelf.symlink_to(target.name)
            environment = {
                key: value
                for key, value in os.environ.items()
                if key not in {"ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_SDK_ROOT", "ANDROID_HOME"}
            }
            environment["ANDROID_NDK_HOME"] = str(ndk)
            with mock.patch.dict(os.environ, environment, clear=True):
                self.assertEqual(readelf.absolute(), MODULE.find_readelf("29.0.14206865"))

    def test_accepts_valid_32_bit_shared_object(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library = self.write_elf(directory, elf_class=1, machine=40)
            completed = subprocess.CompletedProcess([], 0, valid_dynamic_symbols(), "")
            with mock.patch.object(MODULE.subprocess, "run", return_value=completed):
                MODULE.verify_elf(library, 1, 40, Path("readelf"))

    def test_rejects_non_shared_object_before_readelf(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library = self.write_elf(directory, elf_type=2)
            with mock.patch.object(MODULE.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "not an ELF shared object"):
                    MODULE.verify_elf(library, 2, 183, Path("readelf"))
            run.assert_not_called()

    def test_rejects_truncated_elf_header_cleanly(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / "libxanh_adblock_core.so"
            library.write_bytes(b"\x7fELF\x02\x01\x01")
            with self.assertRaisesRegex(ValueError, "truncated ELF header"):
                MODULE.verify_elf(library, 2, 183, Path("readelf"))

    def test_rejects_linked_and_oversized_library_before_readelf(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = self.write_elf(directory)
            linked = root / "linked.so"
            linked.symlink_to(target)
            with mock.patch.object(MODULE.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "real native library"):
                    MODULE.verify_elf(linked, 2, 183, Path("readelf"))
            run.assert_not_called()

            with mock.patch.object(MODULE, "MAX_LIBRARY_BYTES", 64):
                with mock.patch.object(MODULE.subprocess, "run") as run:
                    with self.assertRaisesRegex(ValueError, "oversized native library"):
                        MODULE.verify_elf(target, 2, 183, Path("readelf"))
                run.assert_not_called()

    def test_rejects_missing_exact_export_name(self) -> None:
        required = sorted(MODULE.EXPORTS)
        output = valid_dynamic_symbols().replace(required[0], required[0] + "_lookalike")
        with tempfile.TemporaryDirectory() as directory:
            library = self.write_elf(directory)
            completed = subprocess.CompletedProcess([], 0, output, "")
            with mock.patch.object(MODULE.subprocess, "run", return_value=completed):
                with self.assertRaisesRegex(ValueError, required[0]):
                    MODULE.verify_elf(library, 2, 183, Path("readelf"))

    def test_rejects_exports_that_are_not_callable_public_definitions(self) -> None:
        required = sorted(MODULE.EXPORTS)
        invalid_variants = {
            "local": {"binding": "LOCAL"},
            "hidden": {"visibility": "HIDDEN"},
            "undefined": {"section_index": "UND"},
            "absolute": {"section_index": "ABS"},
            "not-a-function": {"symbol_type": "OBJECT"},
        }
        for label, attributes in invalid_variants.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                lines = valid_dynamic_symbols().splitlines()
                lines = [line for line in lines if not line.endswith(required[0])]
                lines.append(symbol_line(20, required[0], **attributes))
                completed = subprocess.CompletedProcess([], 0, "\n".join(lines), "")
                library = self.write_elf(directory)
                with mock.patch.object(MODULE.subprocess, "run", return_value=completed):
                    with self.assertRaisesRegex(ValueError, required[0]):
                        MODULE.verify_elf(library, 2, 183, Path("readelf"))

    def test_completion_marker_must_be_real_regular_and_empty(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / ".complete"
            with self.assertRaisesRegex(ValueError, "missing native package completion marker"):
                MODULE.verify_completion_marker(marker)

            marker.mkdir()
            with self.assertRaisesRegex(ValueError, "real zero-byte file"):
                MODULE.verify_completion_marker(marker)
            marker.rmdir()

            marker.write_text("complete\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "real zero-byte file"):
                MODULE.verify_completion_marker(marker)
            marker.unlink()

            target = Path(directory) / "target"
            target.touch()
            marker.symlink_to(target)
            with self.assertRaisesRegex(ValueError, "real zero-byte file"):
                MODULE.verify_completion_marker(marker)
            marker.unlink()

            marker.touch()
            MODULE.verify_completion_marker(marker)

    def test_native_package_directory_must_exist_and_not_be_a_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            missing = root / "missing"
            with self.assertRaisesRegex(ValueError, "missing native package directory"):
                MODULE.resolve_real_directory(missing)

            package = root / "package"
            package.mkdir()
            self.assertEqual(package.resolve(), MODULE.resolve_real_directory(package))

            linked_package = root / "linked-package"
            linked_package.symlink_to(package, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "must be a real directory"):
                MODULE.resolve_real_directory(linked_package)


if __name__ == "__main__":
    unittest.main()
