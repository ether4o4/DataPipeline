"""Publish verified test APKs; never execute artifact contents or delete old assets."""
import glob, hashlib, io, json, os, re, subprocess, sys, tempfile, urllib.request, urllib.error, urllib.parse, zipfile
from pathlib import Path
TAG = "polish-test-latest"
BRANCH = "polish/mobile-2026-10-05"
MARK = "<!-- rolling-test "
LIMIT = 512 * 1024 * 1024
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()
def api(path, method="GET", data=None, binary=False):
    url = path if path.startswith("https://") else "https://api.github.com/repos/" + os.environ["GITHUB_REPOSITORY"] + "/" + path
    assert urllib.parse.urlparse(url).hostname in ("api.github.com", "uploads.github.com")
    headers = {"Authorization": "Bearer " + os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    if data is not None:
        headers["Content-Type"] = "application/vnd.android.package-archive" if binary else "application/json"
        if not binary:
            data = json.dumps(data).encode()
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=120) as response:
        return json.load(response)
def download(path):
    url = "https://api.github.com/repos/" + os.environ["GITHUB_REPOSITORY"] + "/" + path
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"], "Accept": "application/vnd.github+json"})
    try:
        response = urllib.request.build_opener(NoRedirect).open(req, timeout=120)
    except urllib.error.HTTPError as error:
        if error.code != 302:
            raise
        location = error.headers["Location"]
        assert urllib.parse.urlparse(location).scheme == "https"
        # Signed storage redirect receives no GitHub credentials.
        response = urllib.request.urlopen(location, timeout=120)
    with response:
        data = response.read(LIMIT + 1)
    assert len(data) <= LIMIT, "Artifact exceeds bounded download size"
    return data
def metadata(release):
    body = (release or {}).get("body") or ""
    match = re.search(re.escape(MARK) + r"(.*?) -->", body)
    return json.loads(match.group(1)) if match else None
def newer(run, old):
    return not old or (run["created_at"], run["id"]) > (old["created_at"], old["run_id"])
def trusted(run, config, current=False):
    repo = os.environ["GITHUB_REPOSITORY"]
    assert run["repository"]["full_name"] == repo and run["head_repository"]["full_name"] == repo, "Foreign repository"
    assert all(pr["head"]["repo"]["id"] == run["repository"]["id"] for pr in run.get("pull_requests", [])), "Fork PR"
    assert run["head_branch"] in ("main", BRANCH), "Unapproved branch"
    path = run["path"].split("@")[0]
    assert path in (config["main_path"], ".github/workflows/polish-verify.yml"), "Unapproved workflow"
    assert path == ".github/workflows/polish-verify.yml" or run["head_branch"] == "main", "Main workflow branch restriction"
    if current:
        assert path == ".github/workflows/polish-verify.yml"
        jobs = api("actions/runs/%s/jobs?per_page=100" % run["id"])["jobs"]
        assert any(j["name"] == "verify" and j["conclusion"] == "success" for j in jobs), "Verification job did not succeed"
    else:
        assert run["status"] == "completed" and run["conclusion"] == "success", "Source build failed"
def choose(apks, config, polish):
    selected = [(name, data) for name, data in apks if re.search(config["selector"], name)]
    unique = {digest(data): (name, data) for name, data in selected}
    assert len(unique) == 1, "Missing or ambiguous intended APK: " + str([n for n, _ in selected])
    return next(iter(unique.values()))
def inspect_apk(data, config):
    assert len(data) <= 256 * 1024 * 1024
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        assert "AndroidManifest.xml" in z.namelist(), "Missing APK manifest"
        abis = sorted({n.split("/")[1] for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")})
    if config.get("abi"):
        assert abis == [config["abi"]], "Unexpected ABI: " + str(abis)
    sdk = os.environ.get("ANDROID_HOME", "/usr/local/lib/android/sdk")
    dirs = sorted(glob.glob(sdk + "/build-tools/*"), key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p)[-3:]))
    assert dirs, "Preinstalled Android inspection tools unavailable"
    tools = dirs[-1]
    with tempfile.TemporaryDirectory() as directory:
        apk = Path(directory) / "test.apk"
        apk.write_bytes(data)
        subprocess.run([tools + "/apksigner", "verify", str(apk)], check=True, capture_output=True, timeout=60)
        badging = subprocess.check_output([tools + "/aapt", "dump", "badging", str(apk)], text=True, timeout=60)
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging)
    assert match, "Unreadable APK identity"
    return {"package": match[1], "version_code": match[2], "version_name": match[3], "abis": abis}
def publish(config, run, data, identity, release):
    old = metadata(release)
    if old:
        assert old["package"] == identity["package"], "Application package changed"
        assert old["abis"] == identity["abis"], "Native ABI set changed"
    if release:
        assert release["prerelease"] and not release.get("immutable"), "Channel is not a mutable test prerelease"
        assert old or release["draft"], "Unrecognized existing release"
    name = config["asset_prefix"] + "-" + str(run["id"]) + "-" + digest(data)[7:19] + ".apk"
    if not release:
        # Tag anchors main. The body records actual APK source SHA; no workflow-tag write privilege needed.
        release = api("releases", "POST", {"tag_name": TAG, "target_commitish": "main", "name": "Latest polish test APK", "draft": True, "prerelease": True, "make_latest": "false"})
    assets = api("releases/%s/assets?per_page=100" % release["id"])
    asset = next((a for a in assets if a["name"] == name), None)
    if not asset:
        upload = release["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote(name)
        asset = api(upload, "POST", data, binary=True)
    assert asset["state"] == "uploaded" and asset["size"] == len(data) and asset.get("digest") == digest(data), "Uploaded APK did not verify"
    info = dict(identity, run_id=run["id"], created_at=run["created_at"], source_sha=run["head_sha"], source_branch=run["head_branch"], workflow=run["path"], apk_sha256=digest(data)[7:], bytes=len(data), asset=name)
    body = ("**TEST APK — phone runtime testing is incomplete.** Signing may differ from installed/store builds.\n\n"
            "[Download newest verified test APK](" + ("https://github.com/" + os.environ["GITHUB_REPOSITORY"] + "/releases/download/" + TAG + "/" + name) + ")\n\n"
            "Package: \x60" + identity["package"] + "\x60; version: \x60" + identity["version_name"] + "\x60 (code " + identity["version_code"] + "); native ABIs: " + (", ".join(identity["abis"]) or "no native libraries") + ".\n\n"
            "[Successful source build](" + run["html_url"] + "); branch \x60" + run["head_branch"] + "\x60; source commit \x60" + run["head_sha"] + "\x60.\n\n"
            "SHA-256: \x60" + info["apk_sha256"] + "\x60\n\n"
            "This stable page follows approved successful builds, not every commit. Older versioned downloads remain available below. The tag anchors main; APK source identity is recorded above.\n\n"
            + MARK + json.dumps(info, sort_keys=True) + " -->")
    # Only promote after upload/identity/digest checks; old body and old APK remain on any earlier failure.
    api("releases/%s" % release["id"], "PATCH", {"name": "Latest polish test APK", "body": body, "draft": False, "prerelease": True, "make_latest": "false"})
    print(json.dumps(info, sort_keys=True))
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write(body.split(MARK)[0])
def main():
    config = json.loads(Path(".github/rolling-test-apk.json").read_text())
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    mode = os.environ.get("ROLLING_MODE", "seed")
    if mode == "seed":
        assert event["pull_request"]["head"]["repo"]["full_name"] == os.environ["GITHUB_REPOSITORY"]
        assert event["pull_request"]["head"]["ref"] == BRANCH
        run_id = config["seed_run"]
    elif mode == "current":
        run_id = int(os.environ["GITHUB_RUN_ID"])
    else:
        run_id = event["workflow_run"]["id"]
    run = api("actions/runs/%s" % run_id)
    trusted(run, config, current=mode == "current")
    if mode == "seed":
        assert run["head_sha"] == config["seed_sha"]
    try:
        release = api("releases/tags/" + TAG)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        release = next((r for r in api("releases?per_page=100") if r["tag_name"] == TAG), None)
    if not newer(run, metadata(release)):
        old = metadata(release)
        if old and old["run_id"] == run["id"] and "/releases/download/untagged-" in release["body"]:
            assets = api("releases/%s/assets?per_page=100" % release["id"])
            assert any(a["name"] == old["asset"] and a.get("digest") == "sha256:" + old["apk_sha256"] for a in assets)
            body = re.sub(r"/releases/download/untagged-[^/]+/", "/releases/download/" + TAG + "/", release["body"])
            api("releases/%s" % release["id"], "PATCH", {"body": body, "prerelease": True, "make_latest": "false"})
        print("Same or older source build: working download preserved.")
        return
    old = metadata(release)
    if old and old["source_sha"] != run["head_sha"]:
        comparison = api("compare/" + old["source_sha"] + "..." + run["head_sha"])
        assert comparison["status"] in ("ahead", "identical"), "Source commit would regress or diverge"
    polish = run["path"].split("@")[0] == ".github/workflows/polish-verify.yml"
    artifacts = api("actions/runs/%s/artifacts?per_page=100" % run_id)["artifacts"]
    artifacts = [a for a in artifacts if a["name"] == "polish-validation"] if polish else [a for a in artifacts if re.fullmatch(config["main_artifact"], a["name"])]
    assert artifacts and not any(a["expired"] for a in artifacts), "Intended artifacts missing or expired"
    apks = []
    for artifact in artifacts:
        assert artifact["workflow_run"]["head_sha"] == run["head_sha"]
        archive = download("actions/artifacts/%s/zip" % artifact["id"])
        assert digest(archive) == artifact["digest"], "Artifact archive digest mismatch"
        with zipfile.ZipFile(io.BytesIO(archive)) as z:
            for item in z.infolist():
                if item.filename.endswith(".apk") and re.search(config["selector"], item.filename):
                    assert item.file_size <= 256 * 1024 * 1024
                    apks.append((item.filename, z.read(item)))
    name, data = choose(apks, config, polish)
    if mode == "seed" and config.get("seed_apk_sha"):
        assert digest(data)[7:] == config["seed_apk_sha"], "Seed APK differs from approved test build"
    identity = inspect_apk(data, config)
    publish(config, run, data, identity, release)
if __name__ == "__main__":
    main()
