import importlib.util, os, unittest
from unittest.mock import patch
spec = importlib.util.spec_from_file_location("rolling", ".github/scripts/rolling_apk.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
class SafetyTests(unittest.TestCase):
    def setUp(self):
        os.environ["GITHUB_REPOSITORY"] = "owner/repo"
        self.run = dict(id=2, created_at="2026-10-06T00:00:00Z", head_branch="main", head_sha="abc", path="build.yml", status="completed", conclusion="success", repository=dict(full_name="owner/repo", id=1), head_repository=dict(full_name="owner/repo"), pull_requests=[])
        self.config = dict(main_path="build.yml", selector=r"\.apk$", asset_prefix="test")
    def test_failed_build_rejected(self):
        self.run["conclusion"] = "failure"
        with self.assertRaises(AssertionError): m.trusted(self.run, self.config)
    def test_foreign_head_rejected(self):
        self.run["head_repository"]["full_name"] = "fork/repo"
        with self.assertRaises(AssertionError): m.trusted(self.run, self.config)
    def test_fork_pr_rejected(self):
        self.run["pull_requests"] = [dict(head=dict(repo=dict(id=2)))]
        with self.assertRaises(AssertionError): m.trusted(self.run, self.config)
    def test_unapproved_ref_and_workflow_rejected(self):
        self.run["head_branch"] = "other"
        with self.assertRaises(AssertionError): m.trusted(self.run, self.config)
        self.run["head_branch"] = "main"
        self.run["path"] = "untrusted.yml"
        with self.assertRaises(AssertionError): m.trusted(self.run, self.config)
    def test_stale_and_duplicate_do_not_promote(self):
        old = dict(created_at=self.run["created_at"], run_id=3)
        self.assertFalse(m.newer(self.run, old))
        old["run_id"] = 2
        self.assertFalse(m.newer(self.run, old))
        old["run_id"] = 1
        self.assertTrue(m.newer(self.run, old))
    def test_ambiguous_apks_rejected_duplicates_allowed(self):
        with self.assertRaises(AssertionError): m.choose([("a.apk", b"a"), ("b.apk", b"b")], self.config, True)
        self.assertEqual(m.choose([("a.apk", b"a"), ("b.apk", b"a")], self.config, True)[1], b"a")
    def test_upload_failure_keeps_previous_release(self):
        release = dict(id=1, prerelease=True, immutable=False, body=m.MARK + '{"package":"p","abis":[]} -->', upload_url="https://uploads.github.com/repo/assets{?name}")
        identity = dict(package="p", abis=[])
        calls = []
        def fake(path, method="GET", *args, **kwargs):
            calls.append((path, method))
            if method == "GET": return []
            raise RuntimeError("Upload failed")
        with patch.object(m, "api", side_effect=fake):
            with self.assertRaises(RuntimeError): m.publish(self.config, self.run, b"apk", identity, release)
        self.assertFalse(any(method in ("PATCH", "DELETE") for _, method in calls))
    def test_bad_upload_digest_keeps_previous_release(self):
        release = dict(id=1, prerelease=True, immutable=False, body=m.MARK + '{"package":"p","abis":[]} -->', upload_url="https://uploads.github.com/repo/assets{?name}")
        calls = []
        def fake(path, method="GET", *args, **kwargs):
            calls.append(method)
            return [] if method == "GET" else dict(state="uploaded", size=3, digest="wrong")
        with patch.object(m, "api", side_effect=fake):
            with self.assertRaises(AssertionError): m.publish(self.config, self.run, b"apk", dict(package="p", abis=[]), release)
        self.assertNotIn("PATCH", calls)
        self.assertNotIn("DELETE", calls)
if __name__ == "__main__": unittest.main()
