#!/usr/bin/env python3
"""Stage and verify a durable recovery draft before replacing a release."""

import hashlib
import json
import os
import subprocess
from urllib.parse import quote, urlencode

BOT = "github-actions[bot]"
MARKER = "republish-recovery-v1\n"


class RecoveryError(RuntimeError):
    """Remote content does not satisfy the recovery contract."""


class Gh:
    """Use gh for authentication and transport, including binary asset bodies."""

    def call(self, method, path, data=None, *, binary=None, accept=None,
             content_type="application/octet-stream"):
        args = ["gh", "api", "--method", method, path]
        body = None
        if data is not None:
            args += ["--input", "-", "-H", "Content-Type: application/json"]
            body = json.dumps(data).encode()
        if binary is not None:
            args += ["--input", "-", "-H", f"Content-Type: {content_type}"]
            body = binary
        if accept:
            args += ["-H", f"Accept: {accept}"]
        if body is not None:
            args += ["-H", f"Content-Length: {len(body)}"]
        return subprocess.run(args, input=body, stdout=subprocess.PIPE, check=True).stdout

    def api(self, method, path, data=None):
        body = self.call(method, path, data)
        return json.loads(body or b"null")

    def listing(self, path):
        items = []
        page = 1
        while True:
            batch = self.api("GET", f"{path}?per_page=100&page={page}")
            items.extend(batch)
            if len(batch) < 100:
                return items
            page += 1

    def download(self, root, asset):
        return self.call(
            "GET", f"{root}/releases/assets/{asset['id']}",
            accept="application/octet-stream",
        )

    def upload(self, release, asset, content):
        url = release["upload_url"].split("{", 1)[0]
        params = {"name": asset["name"]}
        if asset["label"] is not None:
            params["label"] = asset["label"]
        self.call("POST", f"{url}?{urlencode(params)}", binary=content,
                  content_type=asset["content_type"])


def require(condition, message):
    if not condition:
        raise RecoveryError(message)


def recovery_tag(tag):
    return "republish-recovery-" + hashlib.sha256(tag.encode()).hexdigest()


def target_sha(gh, root, tag, fallback):
    """Peel annotated tags; otherwise resolve only the source target, never HEAD."""
    refs = gh.api("GET", f"{root}/git/matching-refs/tags/{quote(tag, safe='')}")
    refs = [ref for ref in refs if ref["ref"] == f"refs/tags/{tag}"]
    require(len(refs) <= 1, "Ambiguous tag")
    if refs:
        obj = refs[0]["object"]
        while obj["type"] == "tag":
            obj = gh.api("GET", f"{root}/git/tags/{obj['sha']}")["object"]
        require(obj["type"] == "commit", "Tag does not point to a commit")
        return obj["sha"]
    require(bool(fallback), "Missing source target")
    return gh.api("GET", f"{root}/commits/{quote(fallback, safe='')}")["sha"]


def assets_by_name(gh, root, release):
    assets = gh.listing(f"{root}/releases/{release['id']}/assets")
    indexed = {asset["name"]: asset for asset in assets}
    require(len(indexed) == len(assets), "Duplicate asset names")
    return indexed


def checked_bytes(gh, root, asset):
    content = gh.download(root, asset)
    digest = hashlib.sha256(content).hexdigest()
    require(len(content) == asset["size"], "Asset size mismatch")
    if asset.get("digest"):
        require(asset["digest"] == f"sha256:{digest}", "Source digest mismatch")
    require(asset["state"] == "uploaded", "Asset upload is incomplete")
    return content, digest


def snapshot(gh, root, repo, tag, source, latest):
    content = {}
    manifest = []
    for name, asset in sorted(assets_by_name(gh, root, source).items()):
        content[name], digest = checked_bytes(gh, root, asset)
        manifest.append({
            "name": name, "size": asset["size"], "sha256": digest,
            "label": asset.get("label"), "content_type": asset["content_type"],
        })
    return {
        "version": 1, "repo": repo, "tag": tag, "source_id": source["id"],
        "name": source["name"], "body": source["body"],
        "prerelease": source["prerelease"], "latest": latest,
        "original_target": source["target_commitish"],
        "target": target_sha(gh, root, tag, source["target_commitish"]),
        "assets": manifest,
    }, content


def recovery_metadata(release, repo, tag):
    require(release["draft"] and release["author"]["login"] == BOT,
            "Recovery must be a bot-authored draft")
    require((release["body"] or "").startswith(MARKER), "Unrecognized recovery body")
    try:
        meta = json.loads(release["body"][len(MARKER):])
        require(set(meta) == {
            "version", "repo", "tag", "source_id", "name", "body", "prerelease",
            "latest", "original_target", "target", "assets",
        }, "Invalid recovery fields")
        require(meta["version"] == 1 and meta["repo"] == repo and meta["tag"] == tag,
                "Recovery belongs to another source")
        require(type(meta["source_id"]) is int and type(meta["latest"]) is bool
                and type(meta["prerelease"]) is bool, "Invalid recovery types")
        require(all(value is None or isinstance(value, str)
                    for value in (meta["name"], meta["body"])), "Invalid release text")
        require(isinstance(meta["original_target"], str)
                and isinstance(meta["target"], str)
                and len(meta["target"]) == 40
                and all(c in "0123456789abcdef" for c in meta["target"]),
                "Recovery target is not a pinned SHA")
        names = set()
        for asset in meta["assets"]:
            require(set(asset) == {"name", "size", "sha256", "label", "content_type"},
                    "Invalid manifest fields")
            require(isinstance(asset["name"], str) and asset["name"] not in names
                    and type(asset["size"]) is int and asset["size"] >= 0
                    and isinstance(asset["sha256"], str) and len(asset["sha256"]) == 64
                    and all(c in "0123456789abcdef" for c in asset["sha256"])
                    and isinstance(asset["content_type"], str)
                    and (asset["label"] is None or isinstance(asset["label"], str)),
                    "Invalid asset manifest")
            names.add(asset["name"])
    except (ValueError, KeyError, TypeError) as error:
        raise RecoveryError("Malformed recovery metadata") from error
    require(release["name"] == meta["name"]
            and release["prerelease"] == meta["prerelease"]
            and release["target_commitish"] == meta["target"],
            "Recovery fields conflict with its manifest")
    return meta


def verify_assets(gh, root, release, manifest):
    assets = assets_by_name(gh, root, release)
    require(set(assets) == {asset["name"] for asset in manifest}, "Asset count/name mismatch")
    for expected in manifest:
        asset = assets[expected["name"]]
        _, digest = checked_bytes(gh, root, asset)
        require(digest == expected["sha256"] and asset["size"] == expected["size"]
                and asset.get("label") == expected["label"]
                and asset["content_type"] == expected["content_type"],
                "Recovery asset conflicts with its manifest")


def republish(gh, repo, tag, latest):
    root = f"repos/{repo}"
    temporary_tag = recovery_tag(tag)
    releases = gh.listing(f"{root}/releases")
    originals = [r for r in releases if r["tag_name"] == tag]
    recoveries = [r for r in releases if r["tag_name"] == temporary_tag]
    require(len(originals) <= 1 and len(recoveries) <= 1, "Ambiguous releases")
    source = originals[0] if originals else None
    recovery = recoveries[0] if recoveries else None
    if source and not source["draft"] and source["author"]["login"] == BOT:
        require(recovery is None, "Published release conflicts with recovery")
        return
    require(source is not None or recovery is not None, "No source or recovery release")
    refs = gh.api("GET", f"{root}/git/matching-refs/tags/{temporary_tag}")
    require(not any(ref["ref"] == f"refs/tags/{temporary_tag}" for ref in refs),
            "Temporary recovery tag already exists")
    meta = None
    if recovery:
        meta = recovery_metadata(recovery, repo, tag)
        require(meta["latest"] == latest, "Latest option conflicts with recovery")
    if source:
        fresh, content = snapshot(gh, root, repo, tag, source, latest)
        if recovery:
            assert meta is not None
            require(meta == fresh, "Source changed since recovery was created")
        else:
            meta = fresh
            # A draft does not create its temporary git tag. Never create a ref.
            recovery = gh.api("POST", f"{root}/releases", {
                "tag_name": temporary_tag, "target_commitish": meta["target"],
                "name": meta["name"], "body": MARKER + json.dumps(meta, sort_keys=True),
                "draft": True, "prerelease": meta["prerelease"],
                "make_latest": "false",
            })
            recovery_metadata(recovery, repo, tag)
        assert meta is not None
        existing = assets_by_name(gh, root, recovery)
        require(set(existing) <= set(content), "Unexpected recovery assets")
        for asset in meta["assets"]:
            previous = existing.get(asset["name"])
            if previous and previous["state"] == "starter":
                # GitHub documents empty starter assets after failed uploads.
                require(previous["size"] == 0, "Nonempty starter asset")
                gh.api("DELETE", f"{root}/releases/assets/{previous['id']}")
                previous = None
            if previous is None:
                gh.upload(recovery, asset, content[asset["name"]])
    assert meta is not None and recovery is not None
    verify_assets(gh, root, recovery, meta["assets"])
    # Re-read the durable fields before crossing the destructive boundary.
    stored = gh.api("GET", f"{root}/releases/{recovery['id']}")
    require(recovery_metadata(stored, repo, tag) == meta, "Recovery changed")
    require(target_sha(gh, root, tag, meta["target"]) == meta["target"], "Tag moved")
    if source:
        current = gh.api("GET", f"{root}/releases/{source['id']}")
        current_meta, _ = snapshot(gh, root, repo, tag, current, latest)
        require(current_meta == meta, "Source changed before deletion")
        gh.api("DELETE", f"{root}/releases/{source['id']}")
    # One PATCH replaces the recovery fields and publishes on the original tag.
    gh.api("PATCH", f"{root}/releases/{recovery['id']}", {
        "tag_name": tag, "target_commitish": meta["target"], "name": meta["name"],
        "body": meta["body"], "draft": False, "prerelease": meta["prerelease"],
        "make_latest": "true" if latest and not meta["prerelease"] else "false",
    })
    published = gh.api("GET", f"{root}/releases/{recovery['id']}")
    require(not published["draft"] and published["author"]["login"] == BOT
            and published["tag_name"] == tag and published["name"] == meta["name"]
            and published["body"] == meta["body"]
            and published["prerelease"] == meta["prerelease"]
            and published["target_commitish"] == meta["target"], "Publish verification failed")
    verify_assets(gh, root, published, meta["assets"])


if __name__ == "__main__":
    require(os.environ["LATEST"] in ("true", "false"), "LATEST must be true or false")
    republish(Gh(), os.environ["GH_REPO"], os.environ["TAG"], os.environ["LATEST"] == "true")
