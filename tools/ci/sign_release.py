"""Sign a release APK with both existing certificates; never log signing secrets."""
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from prepare_release import release_version

EXPECTED_SIGNERS = {
    '23beba4a34379ed306cfe63eaf4fe8eeca9979c9',
    'd1c0f48b370e74d4ea4770ed4c3cd70a3198d31f',
}
REQUIRED_SECRETS = tuple(
    f'{prefix}_{suffix}'
    for prefix in ('UONEST', 'NINEESIM')
    for suffix in ('KEYSTORE_BASE64', 'STORE_PASSWORD', 'KEY_PASSWORD')
)


def verify_report(report):
    signers = re.findall(r'^(?:V2 )?Signer #\d+:? certificate SHA-1 digest: ([0-9a-f]{40})$', report, re.M)
    if (len(signers) != 2 or set(signers) != EXPECTED_SIGNERS
            or 'Number of signers: 2' not in report
            or 'Verified using v2 scheme (APK Signature Scheme v2): true' not in report
            or 'Verified using v3 scheme (APK Signature Scheme v3): false' not in report):
        raise ValueError('APK must have both expected uonest and 9eSIM certificates and a valid v2 signature.')


def run(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        # Tool errors can echo inputs. Do not forward raw signing output to logs.
        raise ValueError(f'{Path(command[0]).name} failed; check signing configuration and APK input.')
    return result.stdout


def main():
    missing = [key for key in REQUIRED_SECRETS if not os.environ.get(key)]
    if missing:
        raise ValueError('Missing Actions Secrets: ' + ', '.join(missing))
    version, code = release_version(os.environ['UONEST_VERSION'])
    sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '37.0.0'
    source = Path('app-unpriv/build/outputs/apk/release/app-unpriv-release-unsigned.apk')
    if not source.is_file():
        raise ValueError('Unsigned release APK is missing.')
    metadata = run([str(sdk / 'aapt2'), 'dump', 'badging', str(source)])
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", metadata, re.M)
    if not package or package.groups() != ('com.uonest.app', str(code), version):
        raise ValueError('APK package/version does not match the release configuration.')
    output_dir = Path('release-output')
    output_dir.mkdir(exist_ok=True)
    output = output_dir / f'uonest-{version}-dual-signed.apk'
    checksum = output.with_suffix('.apk.sha256')
    output.unlink(missing_ok=True)
    checksum.unlink(missing_ok=True)
    try:
        with tempfile.TemporaryDirectory(prefix='uonest-sign-') as temporary:
            temporary = Path(temporary)
            stores = []
            for prefix in ('UONEST', 'NINEESIM'):
                try:
                    data = base64.b64decode(''.join(os.environ[prefix + '_KEYSTORE_BASE64'].split()), validate=True)
                except (ValueError, binascii.Error):
                    raise ValueError(f'{prefix}_KEYSTORE_BASE64 is not valid Base64.') from None
                store = temporary / (prefix + '.keystore')
                store.write_bytes(data)
                store.chmod(0o600)
                stores.append(store)
            aligned = temporary / 'aligned.apk'
            run([str(sdk / 'zipalign'), '-f', '-P', '16', '4', str(source), str(aligned)])
            run([
                str(sdk / 'apksigner'), 'sign',
                '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
                '--v3-signing-enabled', 'false', '--v4-signing-enabled', 'false',
                '--ks', str(stores[0]), '--ks-key-alias', 'uonest',
                '--ks-pass', 'env:UONEST_STORE_PASSWORD', '--key-pass', 'env:UONEST_KEY_PASSWORD',
                '--next-signer', '--ks', str(stores[1]), '--ks-key-alias', 'key0',
                '--ks-pass', 'env:NINEESIM_STORE_PASSWORD', '--key-pass', 'env:NINEESIM_KEY_PASSWORD',
                '--out', str(output), str(aligned),
            ])
            verify_report(run([str(sdk / 'apksigner'), 'verify', '--verbose', '--print-certs', str(output)]))
            run([str(sdk / 'zipalign'), '-c', '-P', '16', '4', str(output)])
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        checksum.write_text(f'{digest}  {output.name}\n')
    except Exception:
        output.unlink(missing_ok=True)
        checksum.unlink(missing_ok=True)
        raise
    print(f'Verified dual-signed APK: {output}')
    print(f'SHA-256: {digest}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        sys.exit(str(error))
