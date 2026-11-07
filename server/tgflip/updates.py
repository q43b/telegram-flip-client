"""Finding newer versions of the server and of modules on GitHub, and downloading them
(docs/self-update-plan.md). The repository's releases: server releases are tagged v<version>
(their zip tgflip-server-<version>-win64.zip with its .sha256 next to it), modules
module-<id>-v<version>. Only releases that have their .sha256 are offered (from 0.17.0-beta),
and pre-releases only when asked for.
"""
import json
import re
import urllib.request

from tgflip.settings import app_version_key

RELEASES_URL = "https://api.github.com/repos/q43b/telegram-flip-client/releases?per_page=100"
HEADERS = {"Accept": "application/vnd.github+json", "User-Agent": "tgflip-server"}


class UpdateError(Exception):
    """GitHub can't be reached or answered strangely; the message says what happened."""


class Release:
    def __init__(self, data, version, zip_name):
        self.tag = data["tag_name"]
        self.title = data.get("name") or self.tag
        self.version = version
        self.prerelease = bool(data.get("prerelease"))
        self.notes = (data.get("body") or "").strip()
        assets = {a["name"]: a for a in data.get("assets", [])}
        self.zip_name = zip_name
        self.zip_url = assets[zip_name]["browser_download_url"]
        self.sha256_url = assets[zip_name + ".sha256"]["browser_download_url"]
        self.size = assets[zip_name].get("size", 0)
        # GitHub's own checksum of the zip ("sha256:..."), a second check next to the .sha256
        digest = assets[zip_name].get("digest") or ""
        self.github_sha256 = digest.split(":", 1)[1] if digest.startswith("sha256:") else None


def releases(timeout=20):
    """All releases, newest first."""
    try:
        with urllib.request.urlopen(urllib.request.Request(RELEASES_URL, headers=HEADERS), timeout=timeout) as r:
            return json.load(r)
    except (OSError, ValueError) as e:
        raise UpdateError(f"Can't reach GitHub's release list ({e})")


def newest(prefix, zip_name, current, include_prereleases, found=None):
    """The newest release tagged <prefix><version> that is newer than current and has its
    zip (zip_name(version)) and .sha256; None when there is none."""
    best = None
    current_key = app_version_key(current)
    for data in found if found is not None else releases():
        tag = data.get("tag_name", "")
        if not tag.startswith(prefix) or data.get("draft"):
            continue
        version = tag[len(prefix):]
        key = app_version_key(version)
        if key is None or (current_key is not None and key <= current_key):
            continue
        if data.get("prerelease") and not include_prereleases:
            continue
        names = {a["name"] for a in data.get("assets", [])}
        name = zip_name(version)
        if name not in names or name + ".sha256" not in names:
            continue
        if best is None or key > app_version_key(best.version):
            best = Release(data, version, name)
    return best


def newest_server(current, include_prereleases=False, found=None):
    return newest("v", lambda v: f"tgflip-server-{v}-win64.zip", current, include_prereleases, found)


def newest_module(module_id, current, include_prereleases=False, found=None):
    return newest(f"module-{module_id}-v", lambda v: f"tgflip-module-{module_id}-{v}.zip",
                  current, include_prereleases, found)


MODULE_TAG = re.compile(r"module-([a-z0-9_]+)-v(.+)")  # module ids: lowercase letters, digits and _


def module_ids(found):
    """Every module id that has a release (any version), from the tags."""
    return sorted({m.group(1) for data in found if (m := MODULE_TAG.fullmatch(data.get("tag_name", "")))})


def download(url, path, progress=None, timeout=60):
    """Downloads url into path, chunk by chunk; progress(done bytes, total bytes) after each."""
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": HEADERS["User-Agent"]}),
                                    timeout=timeout) as r, open(path, "wb") as out:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = r.read(256 * 1024)
                if not chunk:
                    break
                out.write(chunk)
                done += len(chunk)
                if progress:
                    progress(done, total)
    except OSError as e:
        raise UpdateError(f"The download stopped ({e})")
