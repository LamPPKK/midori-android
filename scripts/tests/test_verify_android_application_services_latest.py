import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = (
    Path(__file__).resolve().parents[1]
    / "verify_android_application_services_latest.py"
)
SPEC = importlib.util.spec_from_file_location(
    "verify_android_application_services_latest", SCRIPT
)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

VERSION = "155.0"
REVISION = "c" * 40
OLDER_REVISION = "b" * 40


def tags(*, include_newer: bool = False) -> str:
    lines = [
        f"{OLDER_REVISION}\trefs/tags/v154.0",
        f"{'a' * 40}\trefs/tags/v155.0",
        f"{REVISION}\trefs/tags/v155.0^{{}}",
        f"{'d' * 40}\trefs/tags/v156.0-beta.1",
    ]
    if include_newer:
        lines.append(f"{'e' * 40}\trefs/tags/v156.0")
    return "\n".join(lines) + "\n"


def lock(version: str = VERSION, revision: str = REVISION) -> str:
    return "\n".join(
        (
            f"version={version}",
            f"tag=v{version}",
            f"revision={revision}",
            "release_date=2026-08-13",
            "license=MPL-2.0",
            f"maven_repository={MODULE.OFFICIAL_MAVEN_REPOSITORY}",
            f"source={MODULE.OFFICIAL_SOURCE}",
        )
    ) + "\n"


def gradle(version: str = VERSION, artifacts: set[str] | None = None) -> str:
    selected = artifacts or MODULE.EXPECTED_ARTIFACTS
    return "dependencies {\n" + "".join(
        f"    api 'org.mozilla.appservices:{artifact}:{version}'\n"
        for artifact in sorted(selected)
    ) + "}\n"


def verification_metadata(
    version: str = VERSION,
    *,
    checksum: str = "a" * 64,
    artifacts: set[str] | None = None,
) -> str:
    selected = artifacts or MODULE.EXPECTED_ARTIFACTS
    components = []
    for artifact in sorted(selected):
        components.append(
            f'<component group="org.mozilla.appservices" name="{artifact}" '
            f'version="{version}">'
            f'<artifact name="{artifact}-{version}.aar"><sha256 value="{checksum}"/></artifact>'
            f'<artifact name="{artifact}-{version}.pom"><sha256 value="{"b" * 64}"/></artifact>'
            "</component>"
        )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
        f'<components>{"".join(components)}</components></verification-metadata>'
    )


def notice(version: str = VERSION, revision: str = REVISION) -> str:
    return f"""# Mozilla Application Services {version}

This AAR uses Mozilla Application Services {version} at revision
`{revision}`, licensed under MPL-2.0.

- https://github.com/mozilla/application-services/releases/tag/v{version}
"""


def settings() -> str:
    return """dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven { url = uri('https://maven.mozilla.org/maven2') }
            }
            filter { includeGroupByRegex('org\\\\.mozilla\\\\..*') }
        }
    }
}
"""


class AndroidApplicationServicesLatestTests(unittest.TestCase):
    def project(self, directory: str) -> Path:
        root = Path(directory)
        (root / "sync-core/src/main/assets").mkdir(parents=True)
        (root / "gradle").mkdir()
        (root / MODULE.LOCK_PATH).write_text(lock(), encoding="utf-8")
        (root / MODULE.GRADLE_PATH).write_text(gradle(), encoding="utf-8")
        (root / MODULE.NOTICE_PATH).write_text(notice(), encoding="utf-8")
        (root / MODULE.SETTINGS_PATH).write_text(settings(), encoding="utf-8")
        (root / MODULE.VERIFICATION_METADATA_PATH).write_text(
            verification_metadata(), encoding="utf-8"
        )
        return root

    def test_selects_latest_stable_and_prefers_peeled_revision(self) -> None:
        release = MODULE.latest_stable_release(tags())
        self.assertEqual((155, 0, 0), release.version)
        self.assertEqual(VERSION, release.text)
        self.assertEqual(REVISION, release.revision)

    def test_rejects_malformed_conflicting_and_oversized_tag_lists(self) -> None:
        for fixture in (
            "not-a-sha refs/tags/v155.0\n",
            f"{REVISION} refs/heads/main\n",
            f"{REVISION} refs/tags/v155.0 extra\n",
            "x" * (MODULE.MAX_TAG_LIST_BYTES + 1),
        ):
            with self.assertRaises(MODULE.VerificationError):
                MODULE.latest_stable_release(fixture)
        with self.assertRaises(MODULE.VerificationError):
            MODULE.latest_stable_release(
                f"{REVISION} refs/tags/v155.0\n{'d' * 40} refs/tags/v155.0\n"
            )

    def test_project_accepts_exact_latest_lock_aars_checksums_and_notice(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            release = MODULE.verify_project(self.project(directory), tags())
            self.assertEqual(VERSION, release.text)

    def test_rejects_stale_release_or_wrong_revision(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.project(directory)
            with self.assertRaises(MODULE.VerificationError):
                MODULE.verify_project(root, tags(include_newer=True))
            (root / MODULE.LOCK_PATH).write_text(
                lock(revision=OLDER_REVISION), encoding="utf-8"
            )
            with self.assertRaises(MODULE.VerificationError):
                MODULE.verify_project(root, tags())

    def test_lock_is_exact_canonical_and_official(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "lock"
            for contents in (
                lock().replace("license=MPL-2.0", "license=unknown"),
                lock().replace(MODULE.OFFICIAL_SOURCE, "https://example.test/fork"),
                lock() + "extra=value\n",
                lock().replace("release_date=2026-08-13", "release_date=not-a-date"),
            ):
                path.write_text(contents, encoding="utf-8")
                with self.assertRaises(MODULE.VerificationError):
                    MODULE.read_lock(path)

    def test_gradle_requires_exact_reviewed_artifact_set_and_lock_version(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.project(directory)
            gradle_path = root / MODULE.GRADLE_PATH
            gradle_path.write_text(
                gradle(artifacts=MODULE.EXPECTED_ARTIFACTS - {"tabs"}),
                encoding="utf-8",
            )
            with self.assertRaises(MODULE.VerificationError):
                MODULE.verify_project(root, tags())
            gradle_path.write_text(gradle("154.0"), encoding="utf-8")
            with self.assertRaises(MODULE.VerificationError):
                MODULE.verify_project(root, tags())
            gradle_path.write_text(
                gradle() + "api 'org.mozilla.appservices:unexpected:155.0'\n",
                encoding="utf-8",
            )
            with self.assertRaises(MODULE.VerificationError):
                MODULE.verify_project(root, tags())

    def test_repository_is_exclusive_fail_closed_and_official(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.project(directory)
            path = root / MODULE.SETTINGS_PATH
            for contents in (
                settings().replace(
                    "https://maven.mozilla.org/maven2", "https://example.test/maven"
                ),
                settings().replace(
                    "repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)", ""
                ),
                settings().replace(
                    "org\\\\.mozilla\\\\..*", "org\\\\.mozilla\\\\.other"
                ),
            ):
                path.write_text(contents, encoding="utf-8")
                with self.assertRaises(MODULE.VerificationError):
                    MODULE.verify_project(root, tags())

    def test_checksums_require_every_direct_aar_and_pom(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.project(directory)
            path = root / MODULE.VERIFICATION_METADATA_PATH
            path.write_text(
                verification_metadata(checksum="invalid"), encoding="utf-8"
            )
            with self.assertRaises(MODULE.VerificationError):
                MODULE.verify_project(root, tags())
            path.write_text(
                verification_metadata(artifacts=MODULE.EXPECTED_ARTIFACTS - {"tabs"}),
                encoding="utf-8",
            )
            with self.assertRaises(MODULE.VerificationError):
                MODULE.verify_project(root, tags())

    def test_notice_must_bind_version_revision_license_and_tag(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.project(directory)
            path = root / MODULE.NOTICE_PATH
            path.write_text(notice().replace(REVISION, OLDER_REVISION), encoding="utf-8")
            with self.assertRaises(MODULE.VerificationError):
                MODULE.verify_project(root, tags())

    def test_bounded_reader_rejects_symlinks_invalid_utf8_and_large_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input"
            path.write_bytes(b"\xff")
            with self.assertRaises(MODULE.VerificationError):
                MODULE._read_bounded(path)
            path.write_bytes(b"x" * 9)
            with self.assertRaises(MODULE.VerificationError):
                MODULE._read_bounded(path, 8)
            link = Path(directory) / "link"
            link.symlink_to(path)
            with self.assertRaises(MODULE.VerificationError):
                MODULE._read_bounded(link)


if __name__ == "__main__":
    unittest.main()
