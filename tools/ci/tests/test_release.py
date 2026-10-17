import os
from pathlib import Path
import sys
import tempfile
import subprocess
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_release
import sign_release


class ReleaseTests(unittest.TestCase):
    def test_versions_increase_across_rollovers(self):
        versions = ['0.0.1', '0.999.999', '1.0.0', '1.0.1', '1.1.0', '2.0.0', '2099.999.999']
        codes = [prepare_release.release_version(v)[1] for v in versions]
        self.assertEqual(codes, sorted(set(codes)))
        self.assertLess(codes[-1], 2_100_000_000)

    def test_rejects_invalid_and_environment_injection_versions(self):
        for version in ['1.0', '01.0.1', 'v1.0.1', '1.0.1-beta', '1.1000.0', '2100.0.0', '0.0.0', '1.0.1\nX=1', '$(id)']:
            with self.subTest(version=version), self.assertRaises(ValueError):
                prepare_release.release_version(version)

    def test_tag_takes_precedence_over_manual_input(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / 'env'
            output = Path(directory) / 'output'
            with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/tags/v1.2.3',
                                         'RELEASE_VERSION': '9.0.0', 'GITHUB_ENV': str(env), 'GITHUB_OUTPUT': str(output)}):
                prepare_release.main()
            self.assertIn('UONEST_VERSION_CODE=1002003\n', env.read_text())
            self.assertEqual(output.read_text(), 'version=1.2.3\n')

    def test_other_branch_push_cannot_publish(self):
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/feature'}):
            with self.assertRaises(ValueError):
                prepare_release.main()

    def test_auto_starts_at_1_0_1_and_ignores_non_release_tags(self):
        self.assertEqual(prepare_release.automatic_version(['unpriv-v1.7.2', 'v1.2.3-beta'], []), '1.0.1')

    def test_auto_increments_highest_numeric_tag(self):
        self.assertEqual(prepare_release.automatic_version(['v1.0.9', 'v1.0.10', 'v1.0.2'], []), '1.0.11')

    def test_auto_rolls_over_patch_and_minor(self):
        self.assertEqual(prepare_release.automatic_version(['v1.0.999'], []), '1.1.0')
        self.assertEqual(prepare_release.automatic_version(['v1.999.999'], []), '2.0.0')

    def test_retry_reuses_version_for_same_commit_even_after_later_releases(self):
        self.assertEqual(prepare_release.automatic_version(['v1.0.1', 'v1.0.2'], ['v1.0.1']), '1.0.1')

    def test_main_push_exports_auto_version(self):
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / 'env'
            output = Path(directory) / 'output'
            with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/main',
                                         'GITHUB_SHA': 'a' * 40, 'GITHUB_ENV': str(env), 'GITHUB_OUTPUT': str(output)}), patch.object(
                    prepare_release.subprocess, 'check_output', side_effect=['v1.0.3\n', '']):
                prepare_release.main()
            self.assertEqual(output.read_text(), 'version=1.0.4\n')
            self.assertIn('UONEST_VERSION_CODE=1000004\n', env.read_text())

    def test_main_version_selection_with_real_git_tags_and_new_commits(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], text=True).strip()
            git('init', '-q')
            def commit():
                git('-c', 'user.name=Release Test', '-c', 'user.email=test@example.invalid',
                    'commit', '-q', '--allow-empty', '-m', 'test')
                return git('rev-parse', 'HEAD')
            def prepare(sha):
                output = repo / 'output'
                output.write_text('')
                env = dict(os.environ, GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/main',
                           GITHUB_SHA=sha, GITHUB_ENV=str(repo / 'env'), GITHUB_OUTPUT=str(output))
                subprocess.run([sys.executable, str(Path(prepare_release.__file__).resolve())],
                               cwd=repo, env=env, check=True, capture_output=True)
                return output.read_text()
            first = commit()
            self.assertEqual(prepare(first), 'version=1.0.1\n')
            git('tag', 'v1.0.1', first)
            second = commit()
            self.assertEqual(prepare(second), 'version=1.0.2\n')
            git('-c', 'user.name=Release Test', '-c', 'user.email=test@example.invalid',
                'tag', '-a', 'v1.0.2', '-m', 'release', second)
            self.assertEqual(prepare(first), 'version=1.0.1\n')

    def report(self):
        return ('Number of signers: 2\n'
                'Verified using v2 scheme (APK Signature Scheme v2): true\n'
                'Verified using v3 scheme (APK Signature Scheme v3): false\n'
                'Signer #1 certificate SHA-1 digest: 23beba4a34379ed306cfe63eaf4fe8eeca9979c9\n'
                'Signer #2 certificate SHA-1 digest: d1c0f48b370e74d4ea4770ed4c3cd70a3198d31f\n')

    def test_accepts_expected_dual_signers(self):
        sign_release.verify_report(self.report())

    def test_accepts_build_tools_37_certificate_format(self):
        report = self.report().replace('Signer #1 certificate', 'V2 Signer #1: certificate').replace('Signer #2 certificate', 'V2 Signer #2: certificate')
        sign_release.verify_report(report)

    def test_rejects_wrong_missing_or_duplicate_certificate(self):
        for report in [self.report().replace('23beba4a34379ed306cfe63eaf4fe8eeca9979c9', '0' * 40),
                       self.report().replace('Number of signers: 2', 'Number of signers: 1'),
                       self.report().replace('d1c0f48b370e74d4ea4770ed4c3cd70a3198d31f', '23beba4a34379ed306cfe63eaf4fe8eeca9979c9')]:
            with self.assertRaises(ValueError):
                sign_release.verify_report(report)

    def test_rejects_failed_v2_verification(self):
        with self.assertRaises(ValueError):
            sign_release.verify_report(self.report().replace('v2): true', 'v2): false'))

    def test_missing_secrets_stop_before_any_signing_process(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(sign_release, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'Missing Actions Secrets'):
                sign_release.main()
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
