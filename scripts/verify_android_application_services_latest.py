#!/usr/bin/env python3

from __future__ import annotations

import argparse
import datetime
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import NamedTuple


OFFICIAL_REPOSITORY = "https://github.com/mozilla/application-services.git"
OFFICIAL_SOURCE = "https://github.com/mozilla/application-services"
OFFICIAL_MAVEN_REPOSITORY = "https://maven.mozilla.org/maven2"
LOCK_PATH = Path("sync-core/APPLICATION_SERVICES.lock")
GRADLE_PATH = Path("sync-core/build.gradle")
NOTICE_PATH = Path("sync-core/src/main/assets/THIRD_PARTY_NOTICES.md")
VERIFICATION_METADATA_PATH = Path("gradle/verification-metadata.xml")
SETTINGS_PATH = Path("settings.gradle")
MAX_TAG_LIST_BYTES = 8 * 1024 * 1024
MAX_TEXT_FILE_BYTES = 8 * 1024 * 1024
SHA1_PATTERN = re.compile(r"^[0-9a-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
VERSION_PATTERN = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:\.(0|[1-9][0-9]*))?$"
)
TAG_PATTERN = re.compile(
    r"^refs/tags/v"
    r"(0|[1-9][0-9]*)\."
    r"(0|[1-9][0-9]*)"
    r"(?:\.(0|[1-9][0-9]*))?"
    r"(\^\{\})?$"
)
EXPECTED_ARTIFACTS = {"fxaclient", "places", "syncmanager", "logins", "tabs"}
EXPECTED_LOCK_KEYS = {
    "version",
    "tag",
    "revision",
    "release_date",
    "license",
    "maven_repository",
    "source",
}


class VerificationError(RuntimeError):
    pass


class StableRelease(NamedTuple):
    version: tuple[int, int, int]
    text: str
    tag: str
    revision: str


def _record_revision(
    revisions: dict[str, dict[str, str]], tag: str, kind: str, revision: str
) -> None:
    values = revisions.setdefault(tag, {})
    previous = values.get(kind)
    if previous is not None and previous != revision:
        raise VerificationError(f"conflicting revisions for {tag} ({kind})")
    values[kind] = revision


def latest_stable_release(tag_list: str) -> StableRelease:
    if len(tag_list.encode("utf-8")) > MAX_TAG_LIST_BYTES:
        raise VerificationError("upstream tag list exceeds the 8 MiB safety limit")
    revisions: dict[str, dict[str, str]] = {}
    stable: dict[tuple[int, int, int], str] = {}
    for line_number, line in enumerate(tag_list.splitlines(), start=1):
        if not line.strip():
            continue
        fields = line.split()
        if len(fields) != 2:
            raise VerificationError(f"malformed ls-remote output at line {line_number}")
        revision, ref = fields
        if not SHA1_PATTERN.fullmatch(revision):
            raise VerificationError(f"invalid Git object ID at line {line_number}")
        if not ref.startswith("refs/tags/v"):
            raise VerificationError(f"unexpected Git ref at line {line_number}")
        match = TAG_PATTERN.fullmatch(ref)
        if match is None:
            continue
        version = tuple(int(part or 0) for part in match.group(1, 2, 3))
        text = f"{version[0]}.{version[1]}"
        if match.group(3) is not None:
            text += f".{version[2]}"
        tag = f"v{text}"
        previous_tag = stable.get(version)
        if previous_tag is not None and previous_tag != tag:
            raise VerificationError(
                f"duplicate normalized stable version: {previous_tag} and {tag}"
            )
        stable[version] = tag
        _record_revision(
            revisions,
            tag,
            "peeled" if match.group(4) else "direct",
            revision,
        )
    if not stable:
        raise VerificationError(
            "official tag list contains no stable Application Services release"
        )
    version = max(stable)
    tag = stable[version]
    values = revisions[tag]
    revision = values.get("peeled", values.get("direct"))
    if revision is None:
        raise VerificationError(f"no revision found for {tag}")
    return StableRelease(version, tag.removeprefix("v"), tag, revision)


def _read_bounded(path: Path, limit: int = MAX_TEXT_FILE_BYTES) -> str:
    try:
        if path.is_symlink() or not path.is_file():
            raise VerificationError(f"{path} must be a regular file")
        with path.open("rb") as stream:
            contents = stream.read(limit + 1)
    except OSError as error:
        raise VerificationError(f"cannot read {path}: {error}") from error
    if len(contents) > limit:
        raise VerificationError(f"{path} exceeds the {limit}-byte safety limit")
    try:
        return contents.decode("utf-8")
    except UnicodeDecodeError as error:
        raise VerificationError(f"{path} is not valid UTF-8") from error


def read_lock(path: Path) -> dict[str, str]:
    contents = _read_bounded(path, 4096)
    values: dict[str, str] = {}
    for line_number, line in enumerate(contents.splitlines(), start=1):
        fields = line.split("=", 1)
        if len(fields) != 2 or not all(fields):
            raise VerificationError(f"malformed Application Services lock line {line_number}")
        key, value = fields
        if key in values:
            raise VerificationError(f"duplicate Application Services lock key: {key}")
        values[key] = value
    if set(values) != EXPECTED_LOCK_KEYS or len(contents.splitlines()) != len(
        EXPECTED_LOCK_KEYS
    ):
        raise VerificationError("Application Services lock has unexpected or missing keys")
    if VERSION_PATTERN.fullmatch(values["version"]) is None:
        raise VerificationError("Application Services lock version is not exact semver")
    if values["tag"] != f'v{values["version"]}':
        raise VerificationError("Application Services lock tag does not match its version")
    if not SHA1_PATTERN.fullmatch(values["revision"]):
        raise VerificationError("Application Services revision must be lowercase 40-hex")
    try:
        release_date = datetime.date.fromisoformat(values["release_date"])
    except ValueError as error:
        raise VerificationError("Application Services release date is invalid") from error
    if release_date.isoformat() != values["release_date"]:
        raise VerificationError("Application Services release date is not canonical")
    if values["license"] != "MPL-2.0":
        raise VerificationError("Application Services lock must retain the MPL-2.0 license")
    if values["source"] != OFFICIAL_SOURCE:
        raise VerificationError("Application Services lock uses an unofficial source")
    if values["maven_repository"] != OFFICIAL_MAVEN_REPOSITORY:
        raise VerificationError("Application Services lock uses an unofficial Maven repository")
    return values


def _active_gradle_text(contents: str) -> str:
    without_blocks = re.sub(r"/\*.*?\*/", "", contents, flags=re.DOTALL)
    return "\n".join(
        line for line in without_blocks.splitlines() if not line.lstrip().startswith("//")
    )


def _verify_gradle(path: Path, version: str) -> None:
    contents = _active_gradle_text(_read_bounded(path))
    pattern = re.compile(
        r"\borg\.mozilla\.appservices:([A-Za-z0-9_.-]+):([^\s\"'`)]+)"
    )
    matches = [(match.group(1), match.group(2)) for match in pattern.finditer(contents)]
    artifacts = [artifact for artifact, _ in matches]
    if set(artifacts) != EXPECTED_ARTIFACTS or len(artifacts) != len(EXPECTED_ARTIFACTS):
        raise VerificationError(
            "sync-core Gradle dependencies must contain exactly the reviewed Mozilla AAR set"
        )
    stale = [
        f"{artifact}={candidate}"
        for artifact, candidate in matches
        if candidate != version
    ]
    if stale:
        raise VerificationError(
            "Application Services Gradle pins do not match the lock: " + ", ".join(stale)
        )


def _verify_repository(path: Path) -> None:
    contents = _active_gradle_text(_read_bounded(path))
    required = (
        "repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)",
        "maven { url = uri('https://maven.mozilla.org/maven2') }",
        "filter { includeGroupByRegex('org\\\\.mozilla\\\\..*') }",
    )
    if any(contents.count(value) != 1 for value in required):
        raise VerificationError(
            "settings.gradle must bind Mozilla artifacts exclusively to the official repository"
        )


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _verify_gradle_checksums(path: Path, version: str) -> None:
    contents = _read_bounded(path)
    upper = contents.upper()
    if "<!DOCTYPE" in upper or "<!ENTITY" in upper:
        raise VerificationError("Gradle verification metadata cannot contain DTD/entities")
    try:
        root = ET.fromstring(contents)
    except ET.ParseError as error:
        raise VerificationError(f"Gradle verification metadata is malformed: {error}") from error
    if _local_name(root.tag) != "verification-metadata":
        raise VerificationError("Gradle verification metadata has an unexpected root")
    for artifact in sorted(EXPECTED_ARTIFACTS):
        components = [
            node
            for node in root.iter()
            if _local_name(node.tag) == "component"
            and node.attrib.get("group") == "org.mozilla.appservices"
            and node.attrib.get("name") == artifact
            and node.attrib.get("version") == version
        ]
        if len(components) != 1:
            raise VerificationError(
                f"Gradle verification metadata must contain one {artifact}:{version}"
            )
        for extension in ("aar", "pom"):
            name = f"{artifact}-{version}.{extension}"
            artifacts = [
                child
                for child in components[0]
                if _local_name(child.tag) == "artifact"
                and child.attrib.get("name") == name
            ]
            if len(artifacts) != 1:
                raise VerificationError(
                    f"Gradle verification metadata must contain exactly one {name}"
                )
            checksums = [
                child.attrib.get("value", "")
                for child in artifacts[0]
                if _local_name(child.tag) == "sha256"
            ]
            if len(checksums) != 1 or SHA256_PATTERN.fullmatch(checksums[0]) is None:
                raise VerificationError(f"invalid Gradle SHA-256 for {name}")


def _verify_notice(path: Path, version: str, revision: str) -> None:
    contents = _read_bounded(path)
    required = (
        f"# Mozilla Application Services {version}",
        f"Application Services {version} at revision",
        f"`{revision}`",
        f"releases/tag/v{version}",
        "MPL-2.0",
    )
    if any(value not in contents for value in required):
        raise VerificationError("Android Application Services notice is stale")


def verify_project(root: Path, tag_list: str) -> StableRelease:
    root = root.resolve()
    latest = latest_stable_release(tag_list)
    lock = read_lock(root / LOCK_PATH)
    if lock["version"] != latest.text or lock["tag"] != latest.tag:
        raise VerificationError(
            f"Application Services pin {lock['tag']} is stale; latest stable is {latest.tag}"
        )
    if lock["revision"] != latest.revision:
        raise VerificationError(
            f"Application Services revision {lock['revision']} does not match "
            f"{latest.tag} revision {latest.revision}"
        )
    _verify_gradle(root / GRADLE_PATH, latest.text)
    _verify_repository(root / SETTINGS_PATH)
    _verify_gradle_checksums(root / VERIFICATION_METADATA_PATH, latest.text)
    _verify_notice(root / NOTICE_PATH, latest.text, latest.revision)
    return latest


def fetch_official_tags() -> str:
    try:
        completed = subprocess.run(
            ["git", "ls-remote", "--tags", OFFICIAL_REPOSITORY, "refs/tags/v*"],
            check=False,
            capture_output=True,
            text=True,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise VerificationError(
            f"cannot query official Application Services tags: {error}"
        ) from error
    if completed.returncode:
        detail = completed.stderr.strip() or f"git exited with {completed.returncode}"
        raise VerificationError(f"cannot query official Application Services tags: {detail}")
    return completed.stdout


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Fail when Android AARs do not match Mozilla's latest stable "
            "Application Services tag and exact revision."
        )
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="xanh-android repository root",
    )
    parser.add_argument(
        "--tags-file",
        type=Path,
        help="read git ls-remote output from a fixture instead of the network",
    )
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    try:
        tag_list = (
            _read_bounded(arguments.tags_file, MAX_TAG_LIST_BYTES)
            if arguments.tags_file
            else fetch_official_tags()
        )
        release = verify_project(arguments.root, tag_list)
    except VerificationError as error:
        print(
            f"Android Application Services latest-stable verification failed: {error}",
            file=sys.stderr,
        )
        return 1
    print(
        f"Verified Android AARs at latest stable Application Services {release.text} "
        f"revision {release.revision}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
