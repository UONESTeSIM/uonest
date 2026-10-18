import hashlib
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import upload_r2


class R2Tests(unittest.TestCase):
    def setUp(self):
        self.payload = b'published signed APK'
        self.stored = {}
        self.bad_release = False
        self.corrupt_download = False
        self.upload_failure = False
        env = patch.dict(os.environ, {'AWS_ACCESS_KEY_ID': 'test', 'AWS_SECRET_ACCESS_KEY': 'test'})
        env.start()
        self.addCleanup(env.stop)
        runner = patch.object(upload_r2, 'run', side_effect=self.command)
        self.runner = runner.start()
        self.addCleanup(runner.stop)

    def command(self, args):
        if args[0] == 'gh':
            directory = Path(args[args.index('--dir') + 1])
            apk = directory / 'uonest-1.0.1.apk'
            apk.write_bytes(self.payload)
            digest = 'bad' if self.bad_release else hashlib.sha256(self.payload).hexdigest()
            apk.with_suffix('.apk.sha256').write_text(f'{digest}  {apk.name}\n')
        elif args[1] == 's3api':
            key = 's3://uonest-downloads/latest.json'
            return json.dumps({'Contents': [{'Key': 'latest.json'}] if key in self.stored else []})
        elif args[3].startswith('s3://'):
            Path(args[4]).write_bytes(b'corrupted' if self.corrupt_download else self.stored[args[3]])
        else:
            if self.upload_failure:
                raise ValueError('upload failed')
            self.stored[args[4]] = Path(args[3]).read_bytes()

    def upload(self):
        upload_r2.upload('1.0.1', 'a' * 32, 'uonest-downloads')

    def test_mirrors_published_bytes_and_checksum_to_versioned_paths(self):
        self.upload()
        base = 's3://uonest-downloads/releases/v1.0.1/uonest-1.0.1.apk'
        self.assertEqual(self.stored[base], self.payload)
        self.assertEqual(self.stored[base + '.sha256'],
                         (hashlib.sha256(self.payload).hexdigest() + '  uonest-1.0.1.apk\n').encode())
        self.assertEqual(self.runner.call_args_list[0].args[0][:4], ['gh', 'release', 'download', 'v1.0.1'])
        manifest = json.loads(self.stored['s3://uonest-downloads/latest.json'])
        self.assertEqual(manifest, {'version': '1.0.1', 'path': 'releases/v1.0.1/uonest-1.0.1.apk',
                                    'size': len(self.payload), 'sha256': hashlib.sha256(self.payload).hexdigest()})
        self.assertIn('--cache-control', self.runner.call_args_list[-2].args[0])
        self.assertIn('no-store', self.runner.call_args_list[-2].args[0])

    def test_old_retry_cannot_replace_newer_latest(self):
        key = 's3://uonest-downloads/latest.json'
        newer = json.dumps({'version': '1.0.10', 'sha256': 'newer'}).encode()
        self.stored[key] = newer
        self.upload()
        self.assertEqual(self.stored[key], newer)

    def test_failed_upload_preserves_previous_latest(self):
        key = 's3://uonest-downloads/latest.json'
        previous = b'{"version":"1.0.0"}'
        self.stored[key] = previous
        self.upload_failure = True
        with self.assertRaises(ValueError):
            self.upload()
        self.assertEqual(self.stored[key], previous)

    def test_same_version_cannot_change_published_digest(self):
        key = 's3://uonest-downloads/latest.json'
        previous = json.dumps({'version': '1.0.1', 'sha256': 'different'}).encode()
        self.stored[key] = previous
        with self.assertRaisesRegex(ValueError, 'different checksum'):
            self.upload()
        self.assertEqual(self.stored[key], previous)

    def test_corrupt_latest_does_not_silently_reset_version(self):
        key = 's3://uonest-downloads/latest.json'
        self.stored[key] = b'not JSON'
        with self.assertRaises(ValueError):
            self.upload()
        self.assertEqual(self.stored[key], b'not JSON')

    def test_invalid_release_never_uploads(self):
        self.bad_release = True
        with self.assertRaisesRegex(ValueError, 'Published APK checksum mismatch'):
            self.upload()
        self.assertFalse(self.stored)
        self.assertEqual(self.runner.call_count, 1)

    def test_corrupted_remote_bytes_fail_verification(self):
        self.corrupt_download = True
        with self.assertRaisesRegex(ValueError, 'read-back checksum mismatch'):
            self.upload()
        self.assertEqual(len(self.stored), 1)

    def test_failed_upload_stops_before_publishing_checksum(self):
        self.upload_failure = True
        with self.assertRaisesRegex(ValueError, 'upload failed'):
            self.upload()
        self.assertEqual(self.runner.call_count, 2)

    def test_missing_credentials_stop_before_network(self):
        with patch.dict(os.environ, {'AWS_SECRET_ACCESS_KEY': ''}):
            with self.assertRaisesRegex(ValueError, 'Actions Secrets'):
                self.upload()
        self.runner.assert_not_called()

    def test_invalid_version_stops_before_network(self):
        with self.assertRaises(ValueError):
            upload_r2.upload('../latest', 'a' * 32, 'uonest-downloads')
        self.runner.assert_not_called()


if __name__ == '__main__':
    unittest.main()
