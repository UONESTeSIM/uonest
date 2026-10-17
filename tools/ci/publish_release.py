"""Publish verified artifacts at the exact source commit; resume drafts safely."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request

from prepare_release import release_version


def api(method, path, payload=None, missing_ok=False):
    url = os.environ.get('GITHUB_API_URL', 'https://api.github.com') + '/repos/' + os.environ['GH_REPO'] + path
    request = urllib.request.Request(
        url, method=method,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'Accept': 'application/vnd.github+json',
                 'Content-Type': 'application/json'},
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if missing_ok and error.code == 404:
            return None
        raise ValueError(f'GitHub API {method} {path} failed (HTTP {error.code}).') from None


def tag_commit(tag):
    ref = api('GET', '/git/ref/tags/' + tag, missing_ok=True)
    if ref is None:
        return None
    obj = ref['object']
    for _ in range(10):
        if obj['type'] == 'commit':
            return obj['sha']
        if obj['type'] != 'tag':
            break
        obj = api('GET', '/git/tags/' + obj['sha'])['object']
    raise ValueError('Release tag does not resolve to a commit.')


def list_releases():
    # Include drafts when resuming an interrupted run; do not rely on /latest.
    page = 1
    result = []
    while True:
        releases = api('GET', f'/releases?per_page=100&page={page}')
        result.extend(releases)
        if len(releases) < 100:
            return result
        page += 1


def gh(*args):
    result = subprocess.run(['gh', 'release', *args], capture_output=True, text=True)
    if result.returncode:
        raise ValueError(f'GitHub release {args[0]} failed; a draft can be resumed by re-running the workflow.')


def publish(version, source_sha, apk, checksum):
    release_version(version)
    if not re.fullmatch(r'[0-9a-f]{40}', source_sha):
        raise ValueError('Missing or invalid source commit SHA.')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if checksum.read_text().strip() != f'{digest}  {apk.name}':
        raise ValueError('APK checksum mismatch; nothing was published.')
    tag = 'v' + version
    commit = tag_commit(tag)
    if commit is not None and commit != source_sha:
        raise ValueError('Version tag belongs to another commit; refusing to overwrite it.')
    releases = list_releases()
    release = next((item for item in releases if item['tag_name'] == tag), None)
    if release is not None and not release['draft']:
        if commit != source_sha:
            raise ValueError('Published release has no matching source tag.')
        assets = {asset['name'] for asset in release['assets']}
        if not {apk.name, checksum.name}.issubset(assets):
            raise ValueError('Existing published release is missing expected artifacts; inspect it manually.')
        print(f'{tag} is already published for this commit; leaving it unchanged.')
        return
    if release is not None and release['target_commitish'] != source_sha:
        raise ValueError('Existing draft is not owned by this exact commit; inspect it manually.')
    if commit is None:
        # Creating the tag first reserves the version for safe retries. Never move a tag.
        api('POST', '/git/refs', {'ref': 'refs/tags/' + tag, 'sha': source_sha})
    if release is None:
        gh('create', tag, '--verify-tag', '--draft', '--target', source_sha,
           '--title', 'uonest ' + version, '--generate-notes')
    gh('upload', tag, str(apk), str(checksum), '--clobber')
    # Do not make an old retry the Latest release after newer versions have shipped.
    newer_published = False
    for item in releases:
        if item['draft'] or item.get('prerelease') or not item['tag_name'].startswith('v'):
            continue
        try:
            newer_published |= release_version(item['tag_name'][1:])[1] > release_version(version)[1]
        except ValueError:
            continue
    gh('edit', tag, '--draft=false', '--latest=' + ('false' if newer_published else 'true'))
    print(f'Published {tag} for {source_sha}.')


def main():
    version = os.environ['RELEASE_VERSION']
    release_version(version)
    # For annotated tags GITHUB_SHA can be a tag object; resolve the checked-out commit.
    source_sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    apk = Path('release-output') / f'uonest-{version}.apk'
    publish(version, source_sha, apk, apk.with_suffix('.apk.sha256'))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
