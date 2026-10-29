#!/usr/bin/env python3
"""Validate release tags and the complete publication bundle before uploading."""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = "onepassword-sdk-java"
GROUP = "com.ilooner"
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?")


def validate_version(version):
    match = VERSION.fullmatch(version)
    if not match or version.upper().endswith("-SNAPSHOT"):
        raise ValueError("Use a release version such as 1.2.3 or 1.2.3-rc.1; snapshots are not releases")
    if match[4]:
        for identifier in match[4].split("."):
            if identifier.isdigit() and len(identifier) > 1 and identifier.startswith("0"):
                raise ValueError("Numeric prerelease identifiers must not have leading zeroes")
    return version


def version_from_tag(tag):
    if not tag.startswith("v"):
        raise ValueError("Release tags must start with v, for example v1.2.3")
    return validate_version(tag[1:])


def prepare_release(tag, allow_missing_tag=False, repository=None):
    """Resolve a tag to its commit, permitting HEAD only for a manual dry run."""
    version = version_from_tag(tag)
    repository = ROOT if repository is None else repository
    reference = f"refs/tags/{tag}"
    exists = subprocess.run(
        ["git", "show-ref", "--verify", "--quiet", reference],
        cwd=repository, capture_output=True, text=True,
    )
    if exists.returncode not in (0, 1):
        raise ValueError("Unable to inspect release tags in the checkout")
    tag_exists = exists.returncode == 0
    if not tag_exists and not allow_missing_tag:
        raise ValueError(
            f"Tag {tag} does not exist on GitHub. Create it at the intended commit and push it "
            f"(git tag {tag} <commit>; git push origin {tag}), then publish its GitHub Release. "
            "For a build without a tag, run the workflow manually with publish=false."
        )
    commit_reference = f"{reference}^{{commit}}" if tag_exists else "HEAD^{commit}"
    commit = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", commit_reference],
        cwd=repository, capture_output=True, text=True,
    )
    if commit.returncode != 0:
        raise ValueError(f"{reference if tag_exists else 'HEAD'} does not point to a commit")
    return version, commit.stdout.strip(), tag_exists


def verify(version, directory):
    validate_version(version)
    manifest = json.loads((ROOT / "gradle/native-libraries.json").read_text())
    prefix = f"{ARTIFACT}-{version}"
    expected = {
        f"{prefix}.jar", f"{prefix}-sources.jar", f"{prefix}-javadoc.jar",
        f"{prefix}.pom", f"{prefix}.module",
    }
    expected.update(f"{prefix}-native-{platform}.jar" for platform in manifest["platforms"])
    actual = {path.name for path in directory.iterdir()}
    if actual != expected | {"SHA256SUMS"}:
        raise ValueError(f"Incorrect release files; missing: {expected - actual}; unexpected: {actual - expected - {'SHA256SUMS'}}")

    checksums = {}
    for line in (directory / "SHA256SUMS").read_text().splitlines():
        digest, name = line.split("  ", 1)
        if name in checksums or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("Malformed or duplicate release checksum")
        checksums[name] = digest
    if checksums.keys() != expected:
        raise ValueError("Checksum manifest does not cover exactly the release files")
    for name, digest in checksums.items():
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"Checksum mismatch: {name}")
        if name.endswith(".jar"):
            with ZipFile(directory / name) as archive:
                for notice in ("LICENSE", "THIRD_PARTY_NOTICES.md"):
                    if archive.read(f"META-INF/{notice}") != (ROOT / notice).read_bytes():
                        raise ValueError(f"Incorrect license notice in {name}")

    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(directory / f"{prefix}.pom").getroot()
    coordinates = tuple(pom.find(f"m:{key}", ns).text for key in ("groupId", "artifactId", "version"))
    if coordinates != (GROUP, ARTIFACT, version):
        raise ValueError("Incorrect Maven publication coordinates")
    module = json.loads((directory / f"{prefix}.module").read_text())
    if any(module["component"][key] != value for key, value in
           (("group", GROUP), ("module", ARTIFACT), ("version", version))):
        raise ValueError("Incorrect Gradle module coordinates")

    with ZipFile(directory / f"{prefix}.jar") as archive:
        archive.getinfo("com/onepassword/sdk/Client.class")
        archive.getinfo("com/onepassword/sdk/internal/BuildInfo.class")
        attributes = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        if f"Implementation-Version: {version}\r\n" not in attributes:
            raise ValueError("Binary JAR version does not match the release")
    with ZipFile(directory / f"{prefix}-sources.jar") as archive:
        archive.getinfo("com/onepassword/sdk/Client.java")
        if f'VERSION = "{version}"' not in archive.read("com/onepassword/sdk/internal/BuildInfo.java").decode():
            raise ValueError("Source JAR version does not match the release")
    with ZipFile(directory / f"{prefix}-javadoc.jar") as archive:
        archive.getinfo("index.html")
    for platform, entry in manifest["platforms"].items():
        with ZipFile(directory / f"{prefix}-native-{platform}.jar") as archive:
            data = archive.read(f"native/{platform}/{entry['filename']}")
            if hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise ValueError(f"Native binary does not match its pinned upstream digest: {platform}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    tag_parser = subparsers.add_parser("version")
    tag_parser.add_argument("tag")
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("tag")
    prepare_parser.add_argument("--allow-missing-tag", action="store_true")
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("version")
    verify_parser.add_argument("directory", type=Path, nargs="?", default=ROOT / "build/release")
    args = parser.parse_args()
    try:
        if args.command == "version":
            print(version_from_tag(args.tag))
        elif args.command == "prepare":
            version, commit, tag_exists = prepare_release(args.tag, args.allow_missing_tag)
            if not tag_exists:
                print(f"::notice::Tag {args.tag} does not exist; dry run will build selected commit {commit}. "
                      "No tag or release will be created.", file=sys.stderr)
            print(f"version={version}")
            print(f"commit={commit}")
        else:
            verify(args.version, args.directory)
            print("Release artifacts, coordinates, versions, and native checksums verified")
    except (ValueError, KeyError, OSError, ET.ParseError) as error:
        parser.exit(1, f"Release validation failed: {error}\n")


if __name__ == "__main__":
    main()
