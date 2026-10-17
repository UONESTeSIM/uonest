import hashlib
from contextlib import ExitStack
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch, call

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_release


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / 'uonest-1.0.1-dual-signed.apk'
        self.apk.write_bytes(b'test verified artifact')
        self.checksum = self.apk.with_suffix('.apk.sha256')
        self.checksum.write_text(hashlib.sha256(self.apk.read_bytes()).hexdigest() + '  ' + self.apk.name + '\n')
        self.sha = 'a' * 40
        stack = ExitStack()
        self.addCleanup(stack.close)
        self.api = stack.enter_context(patch.object(publish_release, 'api'))
        self.gh = stack.enter_context(patch.object(publish_release, 'gh'))

    def release(self, draft=False):
        return {'tag_name': 'v1.0.1', 'draft': draft, 'target_commitish': self.sha,
                'assets': [{'name': self.apk.name}, {'name': self.checksum.name}]}

    def run_publish(self):
        publish_release.publish('1.0.1', self.sha, self.apk, self.checksum)

    def test_new_release_tags_exact_commit_before_upload_and_publish(self):
        self.api.side_effect = [None, [], {}]
        self.run_publish()
        self.assertIn(call('POST', '/git/refs', {'ref': 'refs/tags/v1.0.1', 'sha': self.sha}), self.api.call_args_list)
        self.assertEqual([c.args[0] for c in self.gh.call_args_list], ['create', 'upload', 'edit'])
        self.assertIn(self.sha, self.gh.call_args_list[0].args)
        self.assertIn('--draft', self.gh.call_args_list[0].args)
        self.assertIn('--latest=true', self.gh.call_args_list[-1].args)

    def test_completed_retry_does_not_overwrite_published_assets(self):
        self.api.side_effect = [{'object': {'type': 'commit', 'sha': self.sha}}, [self.release()]]
        self.run_publish()
        self.gh.assert_not_called()
        self.assertFalse(any(c.args[0] == 'POST' for c in self.api.call_args_list))

    def test_draft_retry_reuploads_then_publishes_without_recreating_tag(self):
        self.api.side_effect = [{'object': {'type': 'commit', 'sha': self.sha}}, [self.release(draft=True)]]
        self.run_publish()
        self.assertEqual([c.args[0] for c in self.gh.call_args_list], ['upload', 'edit'])
        self.assertFalse(any(c.args[0] == 'POST' for c in self.api.call_args_list))

    def test_version_collision_stops_before_mutation(self):
        self.api.return_value = {'object': {'type': 'commit', 'sha': 'b' * 40}}
        with self.assertRaisesRegex(ValueError, 'another commit'):
            self.run_publish()
        self.gh.assert_not_called()
        self.assertEqual(self.api.call_count, 1)

    def test_bad_checksum_stops_before_network(self):
        self.apk.write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            self.run_publish()
        self.api.assert_not_called()
        self.gh.assert_not_called()

    def test_old_draft_retry_does_not_replace_newer_latest_release(self):
        newer = dict(self.release(), tag_name='v1.0.2')
        self.api.side_effect = [{'object': {'type': 'commit', 'sha': self.sha}}, [self.release(draft=True), newer]]
        self.run_publish()
        self.assertIn('--latest=false', self.gh.call_args_list[-1].args)

    def test_failed_upload_does_not_publish_draft(self):
        self.api.side_effect = [None, [], {}]
        self.gh.side_effect = [None, ValueError('upload interrupted')]
        with self.assertRaises(ValueError):
            self.run_publish()
        self.assertEqual([c.args[0] for c in self.gh.call_args_list], ['create', 'upload'])

    def test_annotated_tag_is_resolved_before_comparing_commit(self):
        self.api.side_effect = [{'object': {'type': 'tag', 'sha': 'b' * 40}},
                               {'object': {'type': 'commit', 'sha': self.sha}}, [self.release()]]
        self.run_publish()
        self.gh.assert_not_called()


if __name__ == '__main__':
    unittest.main()
