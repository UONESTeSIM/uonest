"""Validate a release version and export deterministic Android version metadata."""
import os
import re
import sys
import subprocess
from pathlib import Path


def release_version(value):
    match = re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value)
    if not match:
        raise ValueError("Use X.Y.Z with no leading zeros, for example 1.0.1.")
    major, minor, patch = map(int, match.groups())
    code = major * 1_000_000 + minor * 1_000 + patch
    if major > 2099 or minor > 999 or patch > 999 or code < 1:
        raise ValueError("Version components out of range: major <= 2099, minor/patch <= 999; 0.0.0 is invalid.")
    return value, code


def automatic_version(tags, commit_tags):
    versions = {}
    for tag in tags:
        if not tag.startswith('v'):
            continue
        try:
            version, code = release_version(tag[1:])
        except ValueError:
            continue
        versions[tag] = (version, code)
    # Re-running a commit already assigned a tag must not create another release.
    existing = [versions[tag] for tag in commit_tags if tag in versions]
    if existing:
        return max(existing, key=lambda item: item[1])[0]
    code = max([1_000_000, *(item[1] for item in versions.values())]) + 1
    major, rest = divmod(code, 1_000_000)
    minor, patch = divmod(rest, 1_000)
    return release_version(f'{major}.{minor}.{patch}')[0]


def main():
    if os.environ.get("GITHUB_EVENT_NAME") == "push":
        ref = os.environ.get("GITHUB_REF", "")
        if ref == 'refs/heads/main':
            sha = os.environ.get('GITHUB_SHA', '')
            if not re.fullmatch(r'[0-9a-f]{40}', sha):
                raise ValueError('Missing or invalid source commit SHA.')
            tags = subprocess.check_output(['git', 'tag', '--list', 'v*'], text=True).splitlines()
            commit_tags = subprocess.check_output(['git', 'tag', '--points-at', sha], text=True).splitlines()
            raw = automatic_version(tags, commit_tags)
        elif ref.startswith("refs/tags/v"):
            raw = ref.removeprefix("refs/tags/v")
        else:
            raise ValueError("Only main pushes and version tags can publish releases.")
    else:
        raw = os.environ.get("RELEASE_VERSION", "")
    version, code = release_version(raw)
    with Path(os.environ["GITHUB_ENV"]).open("a") as out:
        out.write(f"UONEST_VERSION={version}\nUONEST_VERSION_CODE={code}\n")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as out:
        out.write(f"version={version}\n")
    print(f"uonest {version}, versionCode {code}")


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        sys.exit(str(error))
