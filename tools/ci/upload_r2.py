"""Mirror the published release to R2, verifying both local and stored bytes."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from prepare_release import release_version


def run(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        # Do not forward raw SDK output or credentials into Actions logs.
        raise ValueError(f'{command[0]} failed; check R2 configuration, permissions and release assets, then retry.')


def upload(version, account, bucket):
    release_version(version)
    if not re.fullmatch(r'[0-9a-f]{32}', account):
        raise ValueError('R2_ACCOUNT_ID must be the 32-character Cloudflare account ID.')
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,61}[a-z0-9]', bucket):
        raise ValueError('R2_BUCKET must be a valid bucket name.')
    missing = [key for key in ('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY') if not os.environ.get(key)]
    if missing:
        raise ValueError('Configure both R2_ACCESS_KEY_ID and R2_SECRET_ACCESS_KEY in Actions Secrets.')
    endpoint = f'https://{account}.r2.cloudflarestorage.com'
    with tempfile.TemporaryDirectory(prefix='uonest-r2-') as temporary:
        directory = Path(temporary)
        apk = directory / f'uonest-{version}.apk'
        checksum = apk.with_suffix('.apk.sha256')
        # Retries can rebuild different bytes. Mirror the authoritative published
        # assets, not the rebuilt artifact left in release-output.
        run(['gh', 'release', 'download', 'v' + version, '--dir', temporary,
             '--pattern', apk.name, '--pattern', checksum.name])
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        if checksum.read_text().strip() != f'{digest}  {apk.name}':
            raise ValueError('Published APK checksum mismatch; nothing was uploaded to R2.')
        for path, content_type in ((apk, 'application/vnd.android.package-archive'),
                                   (checksum, 'text/plain; charset=utf-8')):
            key = f'releases/v{version}/{path.name}'
            destination = f's3://{bucket}/{key}'
            run(['aws', 's3', 'cp', str(path), destination, '--endpoint-url', endpoint,
                 '--region', 'auto', '--only-show-errors', '--content-type', content_type,
                 '--content-disposition', f'attachment; filename="{path.name}"',
                 '--cache-control', 'public, max-age=3600'])
            downloaded = directory / ('stored-' + path.name)
            run(['aws', 's3', 'cp', destination, str(downloaded), '--endpoint-url', endpoint,
                 '--region', 'auto', '--only-show-errors'])
            if hashlib.sha256(downloaded.read_bytes()).digest() != hashlib.sha256(path.read_bytes()).digest():
                raise ValueError('R2 read-back checksum mismatch; retry the publish job.')
            print(f'Verified R2 object: {key}')


if __name__ == '__main__':
    try:
        upload(os.environ['RELEASE_VERSION'], os.environ['R2_ACCOUNT_ID'], os.environ['R2_BUCKET'])
    except (ValueError, KeyError, OSError) as error:
        sys.exit(str(error))
