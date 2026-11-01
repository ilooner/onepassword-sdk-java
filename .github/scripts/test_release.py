import hashlib
import io
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, call, patch
import urllib.error
from zipfile import ZipFile

import release


class ReleaseVersionTest(unittest.TestCase):
    def test_accepts_stable_and_prerelease_tags(self):
        for tag in ("v0.1.0", "v1.2.3", "v1.0.0-rc.1", "v2.0.0-alpha-beta.0"):
            with self.subTest(tag=tag):
                self.assertEqual(tag[1:], release.version_from_tag(tag))

    def test_rejects_snapshots_paths_and_shell_expressions(self):
        for tag in ("1.2.3", "v1.2", "v01.2.3", "v1.2.3-rc.01", "v1.2.3-SNAPSHOT",
                    "v1.2.3-snapshot", "v1.2.3+build", "v1.2.3/other", "v1.2.3\n",
                    "v1.2.3;echo unexpected", "v$(echo unexpected)", "refs/heads/main"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.version_from_tag(tag)


class ReleasePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name)
        self.git("init", "--quiet", "--initial-branch=main", "--template=")
        self.git("commit", "--quiet", "--allow-empty", "-m", "First test commit")
        self.first_commit = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.run(
            ["git", "-c", "user.name=Test Contributor", "-c", "user.email=contributor@example.invalid",
             "-c", "commit.gpgSign=false", "-c", "tag.gpgSign=false", *args],
            cwd=self.repository, check=True, capture_output=True, text=True,
        ).stdout.strip()

    def test_missing_tag_selects_latest_branch_commit_without_creating_a_tag(self):
        self.git("commit", "--quiet", "--allow-empty", "-m", "Latest test commit")
        latest = self.git("rev-parse", "HEAD")
        self.assertNotEqual(self.first_commit, latest)
        self.assertEqual(("0.1.0", latest, False), release.prepare_release(
            "v0.1.0", allow_missing_tag=True, repository=self.repository))
        self.assertEqual("", self.git("tag", "--list"))

    def test_release_event_without_a_tag_explains_manual_creation(self):
        with self.assertRaisesRegex(ValueError, "publish=true will create the tag"):
            release.prepare_release("v0.1.0", repository=self.repository)

    def test_existing_lightweight_tag_wins_over_selected_branch(self):
        self.git("tag", "v0.1.0")
        self.git("commit", "--quiet", "--allow-empty", "-m", "Later test commit")
        self.assertNotEqual(self.first_commit, self.git("rev-parse", "HEAD"))
        for allow_missing_tag in (True, False):
            self.assertEqual(("0.1.0", self.first_commit, True), release.prepare_release(
                "v0.1.0", allow_missing_tag=allow_missing_tag, repository=self.repository))

    def test_annotated_tag_resolves_to_commit_not_tag_object(self):
        self.git("tag", "-a", "v0.1.0", "-m", "Annotated test release")
        self.assertNotEqual(self.first_commit, self.git("rev-parse", "refs/tags/v0.1.0"))
        self.assertEqual(("0.1.0", self.first_commit, True),
                         release.prepare_release("v0.1.0", repository=self.repository))

    def test_non_commit_tag_never_falls_back_to_selected_branch(self):
        self.git("tag", "v0.1.0", "HEAD^{tree}")
        with self.assertRaisesRegex(ValueError, "does not point to a commit"):
            release.prepare_release("v0.1.0", allow_missing_tag=True, repository=self.repository)

    def test_invalid_tag_is_rejected_before_running_git(self):
        with patch.object(release.subprocess, "run") as git:
            with self.assertRaises(ValueError):
                release.prepare_release("v0.1.0;echo unexpected", repository=self.repository)
            git.assert_not_called()


class GitHubPublicationTest(unittest.TestCase):
    COMMIT = "a" * 40
    OTHER = "b" * 40
    TAG = "v0.1.1"

    def setUp(self):
        self.github = Mock(spec=release.GitHubAPI)
        self.reference = {"object": {"type": "commit", "sha": self.COMMIT}}
        self.draft = {"id": 1, "tag_name": self.TAG, "draft": True}

    def test_missing_tag_is_created_at_exact_tested_commit(self):
        self.github.request.side_effect = [None, self.reference]
        release.ensure_remote_tag(self.github, self.TAG, self.COMMIT)
        self.assertEqual([
            call("GET", f"git/ref/tags/{self.TAG}", missing_ok=True),
            call("POST", "git/refs", {"ref": f"refs/tags/{self.TAG}", "sha": self.COMMIT}),
        ], self.github.request.call_args_list)

    def test_existing_tag_is_reused_without_writes(self):
        self.github.request.return_value = self.reference
        release.ensure_remote_tag(self.github, self.TAG, self.COMMIT)
        self.github.request.assert_called_once_with("GET", f"git/ref/tags/{self.TAG}", missing_ok=True)

    def test_existing_annotated_tag_is_peeled_and_preserved(self):
        self.github.request.side_effect = [{"object": {"type": "tag", "sha": self.OTHER}}, self.reference]
        release.ensure_remote_tag(self.github, self.TAG, self.COMMIT)
        self.github.request.assert_called_with("GET", f"git/tags/{self.OTHER}")
        self.assertTrue(all(c.args[0] == "GET" for c in self.github.request.call_args_list))

    def test_tag_on_another_commit_is_never_moved(self):
        self.github.request.return_value = {"object": {"type": "commit", "sha": self.OTHER}}
        with self.assertRaisesRegex(ValueError, "will not be moved"):
            release.ensure_remote_tag(self.github, self.TAG, self.COMMIT)
        self.assertEqual(1, self.github.request.call_count)

    def test_concurrent_tag_creation_at_same_commit_is_safe(self):
        self.github.request.side_effect = [None, release.GitHubError(422, "Already exists"), self.reference]
        release.ensure_remote_tag(self.github, self.TAG, self.COMMIT)
        self.assertEqual(3, self.github.request.call_count)

    def test_concurrent_tag_creation_at_other_commit_fails(self):
        self.github.request.side_effect = [None, release.GitHubError(422, "Already exists"),
                                           {"object": {"type": "commit", "sha": self.OTHER}}]
        with self.assertRaisesRegex(ValueError, "will not be moved"):
            release.ensure_remote_tag(self.github, self.TAG, self.COMMIT)

    def test_permission_errors_do_not_trigger_more_writes(self):
        self.github.request.side_effect = [None, release.GitHubError(403, "Forbidden")]
        with self.assertRaises(release.GitHubError):
            release.ensure_remote_tag(self.github, self.TAG, self.COMMIT)
        self.assertEqual(2, self.github.request.call_count)

    def test_publication_creates_a_draft_for_the_verified_tag(self):
        self.github.request.side_effect = [None, self.reference, [], self.draft]
        self.assertEqual(self.draft, release.ensure_github_release(self.github, self.TAG, self.COMMIT))
        self.github.request.assert_called_with("POST", "releases", {
            "tag_name": self.TAG, "target_commitish": self.COMMIT, "name": self.TAG,
            "body": "Java SDK 0.1.1", "draft": True, "prerelease": False,
        })

    def test_prerelease_versions_are_marked_as_prereleases(self):
        self.github.request.side_effect = [self.reference, [], self.draft]
        release.ensure_github_release(self.github, "v0.1.1-rc.1", self.COMMIT)
        self.assertTrue(self.github.request.call_args.args[2]["prerelease"])

    def test_existing_draft_or_published_release_is_preserved(self):
        for draft in (True, False):
            existing = {**self.draft, "draft": draft, "body": "Existing release notes"}
            self.github.request.reset_mock(side_effect=True)
            self.github.request.side_effect = [self.reference, [existing]]
            self.assertIs(existing, release.ensure_github_release(self.github, self.TAG, self.COMMIT))
            self.assertTrue(all(c.args[0] == "GET" for c in self.github.request.call_args_list))

    def test_release_created_concurrently_is_reused(self):
        self.github.request.side_effect = [self.reference, [], release.GitHubError(422, "Already exists"), [self.draft]]
        self.assertEqual(self.draft, release.ensure_github_release(self.github, self.TAG, self.COMMIT))

    def test_release_lookup_includes_later_pages(self):
        self.github.request.side_effect = [[{"tag_name": "other"}] * 100, [self.draft]]
        self.assertEqual(self.draft, release.find_github_release(self.github, self.TAG))
        self.github.request.assert_called_with("GET", "releases?per_page=100&page=2")

    def test_invalid_commit_is_rejected_before_network_access(self):
        with self.assertRaisesRegex(ValueError, "full commit SHA"):
            release.ensure_remote_tag(self.github, self.TAG, "main")
        self.github.request.assert_not_called()


class GitHubAPITest(unittest.TestCase):
    def test_request_sends_structured_json_and_uses_token_without_logging_it(self):
        github = release.GitHubAPI("example/sdk", "test-token")
        with patch.object(release.urllib.request, "urlopen", return_value=io.BytesIO(b'{"id":1}')) as open_url:
            self.assertEqual({"id": 1}, github.request("POST", "git/refs", {"ref": "refs/tags/v1.0.0", "sha": "a" * 40}))
            request = open_url.call_args.args[0]
            self.assertEqual("https://api.github.com/repos/example/sdk/git/refs", request.full_url)
            self.assertEqual("Bearer test-token", request.get_header("Authorization"))
            self.assertEqual("POST", request.method)
            self.assertEqual("a" * 40, json.loads(request.data)["sha"])

    def test_only_a_get_404_can_mean_missing(self):
        github = release.GitHubAPI("example/sdk", "test-token")
        for method, status in (("GET", 404), ("GET", 403), ("POST", 404)):
            error = urllib.error.HTTPError("https://api.github.com/example", status, "Request failed", {}, None)
            with self.subTest(method=method, status=status), patch.object(release.urllib.request, "urlopen", side_effect=error):
                if method == "GET" and status == 404:
                    self.assertIsNone(github.request(method, "git/ref/tags/v1.0.0", missing_ok=True))
                else:
                    with self.assertRaises(release.GitHubError) as caught:
                        github.request(method, "git/ref/tags/v1.0.0", missing_ok=True)
                    self.assertNotIn("test-token", str(caught.exception))

    def test_github_errors_are_visible_in_workflow_annotations(self):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), \
                patch.object(release.sys, "argv", ["release.py", "version", "invalid"]), \
                patch.object(release.sys, "stderr", new_callable=io.StringIO) as stderr, \
                self.assertRaises(SystemExit) as caught:
            release.main()
        self.assertEqual(1, caught.exception.code)
        self.assertIn("::error::Release failed:", stderr.getvalue())


class ReleaseBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / "bundle"
        self.directory.mkdir()
        self.version = "1.2.3"
        self.prefix = "onepassword-sdk-java-1.2.3"
        (self.root / "LICENSE").write_text("Project contributor license fixture\n")
        (self.root / "THIRD_PARTY_NOTICES.md").write_text("Upstream license fixture\n")
        (self.root / "gradle").mkdir()
        binary = b"pinned upstream binary fixture"
        self.native_name = "libop_uniffi_core.so"
        manifest = {"platforms": {"linux-x86_64": {
            "filename": self.native_name, "sha256": hashlib.sha256(binary).hexdigest()}}}
        (self.root / "gradle/native-libraries.json").write_text(json.dumps(manifest))
        patcher = patch.object(release, "ROOT", self.root)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.write_jar(".jar", {
            "com/onepassword/sdk/Client.class": b"class fixture",
            "com/onepassword/sdk/internal/BuildInfo.class": b"version class fixture",
            "META-INF/MANIFEST.MF": b"Implementation-Version: 1.2.3\r\n"})
        self.write_jar("-sources.jar", {
            "com/onepassword/sdk/Client.java": b"source fixture",
            "com/onepassword/sdk/internal/BuildInfo.java": b'public static final String VERSION = "1.2.3";'})
        self.write_jar("-javadoc.jar", {"index.html": b"documentation fixture"})
        self.write_jar("-native-linux-x86_64.jar", {f"native/linux-x86_64/{self.native_name}": binary})
        (self.directory / f"{self.prefix}.pom").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>com.ilooner</groupId>'
            '<artifactId>onepassword-sdk-java</artifactId><version>1.2.3</version></project>')
        (self.directory / f"{self.prefix}.module").write_text(json.dumps({"component": {
            "group": "com.ilooner", "module": "onepassword-sdk-java", "version": "1.2.3"}}))
        self.checksums()

    def write_jar(self, suffix, entries):
        with ZipFile(self.directory / f"{self.prefix}{suffix}", "w") as archive:
            for notice in ("LICENSE", "THIRD_PARTY_NOTICES.md"):
                archive.writestr(f"META-INF/{notice}", (self.root / notice).read_bytes())
            for name, data in entries.items():
                archive.writestr(name, data)

    def checksums(self):
        lines = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}"
                 for path in sorted(self.directory.iterdir()) if path.name != "SHA256SUMS"]
        (self.directory / "SHA256SUMS").write_text("\n".join(lines) + "\n")

    def test_accepts_complete_bundle(self):
        release.verify(self.version, self.directory)

    def test_rejects_missing_native_classifier(self):
        (self.directory / f"{self.prefix}-native-linux-x86_64.jar").unlink()
        with self.assertRaisesRegex(ValueError, "Incorrect release files"):
            release.verify(self.version, self.directory)

    def test_rejects_corrupted_artifact(self):
        with (self.directory / f"{self.prefix}.jar").open("ab") as stream:
            stream.write(b"corrupted")
        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            release.verify(self.version, self.directory)

    def test_rejects_wrong_native_binary_even_with_a_valid_outer_checksum(self):
        self.write_jar("-native-linux-x86_64.jar", {f"native/linux-x86_64/{self.native_name}": b"wrong binary"})
        self.checksums()
        with self.assertRaisesRegex(ValueError, "Native binary does not match"):
            release.verify(self.version, self.directory)

    def test_rejects_mismatched_maven_version(self):
        path = self.directory / f"{self.prefix}.pom"
        path.write_text(path.read_text().replace("1.2.3", "1.2.4"))
        self.checksums()
        with self.assertRaisesRegex(ValueError, "Incorrect Maven publication coordinates"):
            release.verify(self.version, self.directory)

    def test_rejects_duplicate_checksum_entries(self):
        path = self.directory / "SHA256SUMS"
        text = path.read_text()
        path.write_text(text + text.splitlines()[0] + "\n")
        with self.assertRaisesRegex(ValueError, "duplicate release checksum"):
            release.verify(self.version, self.directory)

    def test_rejects_outdated_license_notices(self):
        (self.root / "LICENSE").write_text("Updated project license\n")
        with self.assertRaisesRegex(ValueError, "Incorrect license notice"):
            release.verify(self.version, self.directory)


if __name__ == "__main__":
    unittest.main()
