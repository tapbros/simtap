"""Deterministic release API transport tests; no network or app build."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, unquote, urlsplit

SPEC = importlib.util.spec_from_file_location(
    "republish_release", Path(__file__).resolve().parents[1] / "tools/republish_release.py",
)
assert SPEC is not None and SPEC.loader is not None
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

REPO = "example/app"
TAG = "v1.2.3"
SHA = "a" * 40
ROOT = f"repos/{REPO}"


class MemoryGh(release.Gh):
    """Model remote state behind the real JSON, pagination and upload adapters."""

    def __init__(self):
        self.releases = {
            1: {
                "id": 1, "tag_name": TAG, "target_commitish": "app-branch",
                "name": "Version 1.2.3", "body": "Notes\n\nwith trailing newline\n",
                "draft": False, "prerelease": True, "author": {"login": "maintainer"},
                "upload_url": "https://uploads.github.com/repos/example/app/releases/1/assets{?name,label}",
            },
        }
        self.assets = {1: []}
        self.contents = {}
        self.refs = {TAG: {"type": "commit", "sha": SHA}}
        self.tag_objects = {}
        self.calls = []
        self.next_asset = 10
        self.fail_download = False
        self.fail_upload_at: int | None = None
        self.upload_count = 0
        self.corrupt_verification = False
        self.fail_publish = False
        self.lose_publish_response = False
        self.lose_delete_response = False
        self.add_asset(1, "app.apk", b"\x00binary\xff", "Android package",
                       "application/vnd.android.package-archive")
        self.add_asset(1, "checksums.txt", b"checksum\n", None, "text/plain")

    def add_asset(self, rid, name, content, label=None,
                  content_type="application/octet-stream"):
        aid = self.next_asset
        self.next_asset += 1
        asset = {
            "id": aid, "name": name, "size": len(content), "label": label,
            "content_type": content_type, "state": "uploaded",
            "digest": "sha256:" + hashlib.sha256(content).hexdigest(),
        }
        self.assets[rid].append(asset)
        self.contents[aid] = content
        return asset

    def call(self, method, path, data=None, *, binary=None, accept=None,
             content_type="application/octet-stream"):
        self.calls.append((method, path, copy.deepcopy(data)))
        url = urlsplit(path)
        endpoint = url.path.removeprefix("/").removeprefix(ROOT)
        query = parse_qs(url.query, keep_blank_values=True)
        if endpoint.startswith("/git/matching-refs/tags/"):
            prefix = unquote(endpoint.split("/tags/", 1)[1])
            result = [
                {"ref": f"refs/tags/{tag}", "object": obj}
                for tag, obj in self.refs.items() if tag.startswith(prefix)
            ]
        elif endpoint.startswith("/git/tags/"):
            result = {"object": self.tag_objects[endpoint.rsplit("/", 1)[1]]}
        elif endpoint.startswith("/commits/"):
            result = {"sha": SHA}
        elif endpoint.startswith("/releases/assets/"):
            aid = int(endpoint.rsplit("/", 1)[1])
            if method == "DELETE":
                for assets in self.assets.values():
                    assets[:] = [a for a in assets if a["id"] != aid]
                del self.contents[aid]
                return b""
            if self.fail_download:
                raise OSError("download unavailable")
            if self.corrupt_verification and aid not in {
                a["id"] for a in self.assets.get(1, [])
            }:
                return b"x" * len(self.contents[aid])
            assert accept == "application/octet-stream"
            return self.contents[aid]
        elif endpoint == "/releases":
            if method == "GET":
                result = list(self.releases.values())
            else:
                assert data is not None
                assert method == "POST" and data["draft"] is True
                rid = 2
                assert rid not in self.releases
                self.releases[rid] = {
                    **data, "id": rid, "author": {"login": release.BOT},
                    "upload_url": f"https://uploads.github.com/{ROOT}/releases/{rid}/assets{{?name,label}}",
                }
                self.assets[rid] = []
                result = self.releases[rid]
        else:
            parts = endpoint.split("/")
            rid = int(parts[2])
            if endpoint.endswith("/assets"):
                if method == "GET":
                    result = self.assets[rid]
                else:
                    assert method == "POST"
                    self.upload_count += 1
                    if self.upload_count == self.fail_upload_at:
                        raise OSError("upload unavailable")
                    result = self.add_asset(
                        rid, query["name"][0], binary, query.get("label", [None])[0],
                        content_type,
                    )
            elif method == "GET":
                result = self.releases[rid]
            elif method == "DELETE":
                assert rid == 1
                # The destructive boundary must already have a complete durable copy.
                self.assert_recovery_complete()
                del self.releases[rid]
                del self.assets[rid]
                if self.lose_delete_response:
                    raise OSError("delete response lost")
                return b""
            else:
                assert method == "PATCH" and rid == 2
                assert data is not None
                if self.fail_publish:
                    raise OSError("publish unavailable")
                self.releases[rid].update(data)
                if self.lose_publish_response:
                    raise OSError("publish response lost")
                result = self.releases[rid]
        if isinstance(result, list):
            page = int(query.get("page", ["1"])[0])
            result = result[(page - 1) * 100:page * 100]
        return json.dumps(result).encode()

    def assert_recovery_complete(self):
        recovery = self.releases[2]
        assert recovery["draft"] and recovery["author"]["login"] == release.BOT
        meta = json.loads(recovery["body"][len(release.MARKER):])
        actual = {
            a["name"]: hashlib.sha256(self.contents[a["id"]]).hexdigest()
            for a in self.assets[2]
        }
        assert actual == {a["name"]: a["sha256"] for a in meta["assets"]}
        assert recovery["target_commitish"] == SHA
        assert release.recovery_tag(TAG) not in self.refs


class RepublishTests(unittest.TestCase):
    def setUp(self):
        self.gh = MemoryGh()

    def run_republish(self, latest=False):
        release.republish(self.gh, REPO, TAG, latest)

    def stage_failed_publish(self):
        self.gh.fail_publish = True
        with self.assertRaises(OSError):
            self.run_republish()
        self.gh.fail_publish = False

    def test_download_failure_keeps_original(self):
        self.gh.fail_download = True
        with self.assertRaises(OSError):
            self.run_republish()
        self.assertIn(1, self.gh.releases)
        self.assertNotIn(2, self.gh.releases)

    def test_verification_failure_keeps_original(self):
        self.gh.corrupt_verification = True
        with self.assertRaises(release.RecoveryError):
            self.run_republish()
        self.assertIn(1, self.gh.releases)
        self.assertTrue(self.gh.releases[2]["draft"])

    def test_upload_failure_keeps_original_and_rerun_fills_draft(self):
        self.gh.fail_upload_at = 2
        with self.assertRaises(OSError):
            self.run_republish()
        self.assertIn(1, self.gh.releases)
        self.assertEqual(len(self.gh.assets[2]), 1)
        self.gh.fail_upload_at = None
        self.run_republish()
        self.assertNotIn(1, self.gh.releases)
        self.assertFalse(self.gh.releases[2]["draft"])
        self.assertEqual(len(self.gh.assets[2]), 2)

    def test_publish_failure_leaves_complete_recovery(self):
        self.stage_failed_publish()
        self.assertNotIn(1, self.gh.releases)
        self.gh.assert_recovery_complete()
        meta = release.recovery_metadata(self.gh.releases[2], REPO, TAG)
        self.assertEqual(meta["target"], SHA)
        self.assertEqual(meta["original_target"], "app-branch")
        self.assertFalse(meta["latest"])

    def test_rerun_recovers_without_original_or_local_files(self):
        self.stage_failed_publish()
        self.gh.contents = {
            a["id"]: self.gh.contents[a["id"]] for a in self.gh.assets[2]
        }
        self.run_republish()
        self.assertFalse(self.gh.releases[2]["draft"])
        self.assertEqual(self.gh.releases[2]["tag_name"], TAG)
        self.assertEqual(self.gh.upload_count, 2)

    def test_success_preserves_binary_assets_and_metadata(self):
        original = copy.deepcopy(self.gh.releases[1])
        expected = {
            a["name"]: (self.gh.contents[a["id"]], a["label"], a["content_type"])
            for a in self.gh.assets[1]
        }
        self.run_republish()
        published = self.gh.releases[2]
        for key in ("name", "body", "prerelease", "tag_name"):
            self.assertEqual(published[key], original[key])
        self.assertEqual(published["author"]["login"], release.BOT)
        self.assertEqual(published["target_commitish"], SHA)
        self.assertEqual(published["make_latest"], "false")
        self.assertEqual({
            a["name"]: (self.gh.contents[a["id"]], a["label"], a["content_type"])
            for a in self.gh.assets[2]
        }, expected)
        self.assertEqual(self.gh.refs, {TAG: {"type": "commit", "sha": SHA}})

    def test_published_bot_is_idempotent(self):
        self.gh.releases[1]["author"]["login"] = release.BOT
        before = copy.deepcopy(self.gh.releases)
        self.run_republish()
        self.assertEqual(self.gh.releases, before)
        self.assertTrue(all(method == "GET" for method, _, _ in self.gh.calls))

    def test_original_bot_draft_is_not_a_noop(self):
        self.gh.releases[1].update(author={"login": release.BOT}, draft=True)
        self.run_republish()
        self.assertNotIn(1, self.gh.releases)
        self.assertFalse(self.gh.releases[2]["draft"])

    def test_bad_recovery_owner_is_refused(self):
        self.stage_failed_publish()
        self.gh.releases[2]["author"]["login"] = "other"
        self.gh.calls.clear()
        with self.assertRaises(release.RecoveryError):
            self.run_republish()
        self.assertTrue(self.gh.releases[2]["draft"])
        self.assertTrue(all(method == "GET" for method, _, _ in self.gh.calls))

    def test_conflicting_recovery_manifest_is_refused_before_delete(self):
        self.gh.fail_upload_at = 1
        with self.assertRaises(OSError):
            self.run_republish()
        meta = json.loads(self.gh.releases[2]["body"][len(release.MARKER):])
        meta["source_id"] = 99
        self.gh.releases[2]["body"] = release.MARKER + json.dumps(meta)
        with self.assertRaises(release.RecoveryError):
            self.run_republish()
        self.assertIn(1, self.gh.releases)

    def test_conflicting_existing_asset_is_refused_before_delete(self):
        self.gh.fail_upload_at = 2
        with self.assertRaises(OSError):
            self.run_republish()
        asset = self.gh.assets[2][0]
        self.gh.contents[asset["id"]] = b"x" * asset["size"]
        self.gh.fail_upload_at = None
        with self.assertRaises(release.RecoveryError):
            self.run_republish()
        self.assertIn(1, self.gh.releases)

    def test_incomplete_recovery_without_source_is_not_published(self):
        self.gh.fail_upload_at = 2
        with self.assertRaises(OSError):
            self.run_republish()
        del self.gh.releases[1]
        with self.assertRaises(release.RecoveryError):
            self.run_republish()
        self.assertTrue(self.gh.releases[2]["draft"])

    def test_rerun_replaces_empty_starter_asset(self):
        self.gh.fail_upload_at = 1
        with self.assertRaises(OSError):
            self.run_republish()
        starter = self.gh.add_asset(2, "app.apk", b"")
        starter["state"] = "starter"
        self.gh.fail_upload_at = None
        self.run_republish()
        self.assertFalse(self.gh.releases[2]["draft"])
        self.assertNotIn(starter["id"], self.gh.contents)

    def test_annotated_tag_pins_app_commit_not_workflow_head(self):
        self.gh.refs[TAG] = {"type": "tag", "sha": "b" * 40}
        self.gh.tag_objects["b" * 40] = {"type": "commit", "sha": SHA}
        with patch.dict("os.environ", {"GITHUB_SHA": "c" * 40}):
            self.run_republish()
        self.assertEqual(self.gh.releases[2]["target_commitish"], SHA)

    def test_missing_tag_resolves_source_target(self):
        self.gh.refs.clear()
        self.gh.releases[1]["draft"] = True
        self.run_republish()
        self.assertEqual(self.gh.releases[2]["target_commitish"], SHA)
        commit_reads = [p for m, p, _ in self.gh.calls if "/commits/" in p]
        self.assertEqual(commit_reads, [f"{ROOT}/commits/app-branch",
                                       f"{ROOT}/commits/{SHA}",
                                       f"{ROOT}/commits/app-branch"])

    def test_changed_latest_on_recovery_is_refused(self):
        self.stage_failed_publish()
        with self.assertRaises(release.RecoveryError):
            self.run_republish(latest=True)
        self.assertTrue(self.gh.releases[2]["draft"])

    def test_release_latest_true_and_prerelease_false_are_preserved(self):
        self.gh.releases[1]["prerelease"] = False
        self.run_republish(latest=True)
        self.assertFalse(self.gh.releases[2]["prerelease"])
        self.assertEqual(self.gh.releases[2]["make_latest"], "true")

    def test_prerelease_cannot_be_marked_latest(self):
        self.run_republish(latest=True)
        self.assertTrue(self.gh.releases[2]["prerelease"])
        self.assertEqual(self.gh.releases[2]["make_latest"], "false")

    def test_lost_delete_response_is_recoverable(self):
        self.gh.lose_delete_response = True
        with self.assertRaises(OSError):
            self.run_republish()
        self.gh.assert_recovery_complete()
        self.run_republish()
        self.assertFalse(self.gh.releases[2]["draft"])

    def test_lost_publish_response_is_idempotent_on_rerun(self):
        self.gh.lose_publish_response = True
        with self.assertRaises(OSError):
            self.run_republish()
        self.gh.calls.clear()
        self.run_republish()
        self.assertFalse(self.gh.releases[2]["draft"])
        self.assertTrue(all(m == "GET" for m, _, _ in self.gh.calls))

    def test_pagination_includes_all_assets(self):
        for index in range(100):
            self.gh.add_asset(1, f"file-{index}", b"data")
        self.run_republish()
        self.assertEqual(len(self.gh.assets[2]), 102)

    def test_transport_uses_gh_binary_stdin_and_encoded_names(self):
        transport = release.Gh()
        response = subprocess.CompletedProcess([], 0, stdout=b"")
        with patch.object(release.subprocess, "run", return_value=response) as run:
            transport.upload(
                {"upload_url": f"https://uploads.github.com/{ROOT}/releases/2/assets{{?name,label}}"},
                {"name": "a #?.apk", "label": None, "content_type": "custom/type"},
                b"\x00\xff",
            )
        args = run.call_args.args[0]
        self.assertEqual(args[:4], ["gh", "api", "--method", "POST"])
        self.assertIn("name=a+%23%3F.apk", args[4])
        self.assertIn("Content-Type: custom/type", args)
        self.assertIn("Content-Length: 2", args)
        self.assertEqual(run.call_args.kwargs["input"], b"\x00\xff")
        self.assertTrue(run.call_args.kwargs["check"])


if __name__ == "__main__":
    unittest.main()
