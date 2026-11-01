import unittest
from flow_upstream import normalize_owned_fields, version

class UpstreamPolicyTest(unittest.TestCase):
    def test_version_changes_do_not_conflict_with_fork_identity(self):
        ours = b'versionCode = 2006\nversionName = "1.1.0-beta.9.flow.8"\nnewSetting = false'
        upstream = b'versionCode = 1073\nversionName = "1.1.0-beta.10"\nnewSetting = true'
        result = normalize_owned_fields('app/build.gradle.kts', upstream, ours)
        self.assertIn(b'versionCode = 2006', result)
        self.assertIn(b'newSetting = true', result)
    def test_branding_preserves_other_translations(self):
        ours = b'<string name="app_name">Nuvio Flow</string>'
        upstream = b'<string name="app_name">Nuvio</string><string name="new">new</string>'
        result = normalize_owned_fields('app/src/main/res/values-bg/strings.xml', upstream, ours)
        self.assertIn(b'Nuvio Flow', result)
        self.assertIn(b'name="new"', result)
    def test_beta_ten_sorts_after_nine(self):
        self.assertGreater(version('1.1.0-beta.10'), version('1.1.0-beta.9'))
        self.assertGreater(version('1.1.0'), version('1.1.0-beta.10'))

if __name__ == '__main__': unittest.main()
