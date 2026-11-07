"""Themes host: hosts themes for TG Flip apps to download (docs/themes-plan.md, step 4). It only
hosts them: a theme already on a phone keeps working with any server, with or without this module.

Each folder in themes/ is one theme (theme.json, fonts, license). At startup every folder is
checked with the app's own rules (ThemeInstaller.kt) and zipped reproducibly (sorted names, fixed
timestamps), so a theme's sha256 only changes when the theme does; a theme that breaks a rule is
left out with one log line, so it never reaches a phone. Hosting one more theme is one more
folder (adding them from Maintenance mode comes in 1.1.0).

GET /modules/themes_host/list -> {themes: [{id, name, version, size, sha256}]}
GET /modules/themes_host/<id>.zip
"""
import hashlib
import io
import json
import os
import re
import zipfile

MAX_BYTES = 2 * 1024 * 1024  # the app's limits (ThemeInstaller.kt)
MAX_FILES = 32
ALLOWED = re.compile(r"[A-Za-z0-9_\-./ ]+\.(json|ttf|otf|txt|md)")
THEME_ID = re.compile(r"[a-z0-9_-]{1,40}")
BUILT_IN_ID = "pixel"  # the app's built-in theme: an id no hosted theme may take
STAMP = (1980, 1, 1, 0, 0, 0)  # every file's time in the zip, so the bytes only depend on the files


def pack(folder):
    """(theme.json as a dict, the zip's bytes); ValueError says why a theme can't be hosted."""
    names = []
    for root, _, files in os.walk(folder):
        for name in files:
            names.append(os.path.relpath(os.path.join(root, name), folder).replace(os.sep, "/"))
    names.sort()
    if "theme.json" not in names:
        raise ValueError("no theme.json")
    if len(names) > MAX_FILES:
        raise ValueError(f"{len(names)} files (at most {MAX_FILES})")
    for name in names:
        if not ALLOWED.fullmatch(name) or any(part in ("..", ".") for part in name.split("/")):
            raise ValueError(f"not allowed in a theme: {name}")
    total = sum(os.path.getsize(os.path.join(folder, name)) for name in names)
    if total > MAX_BYTES:
        raise ValueError(f"{total // 1024} KB (at most {MAX_BYTES // 1024 // 1024} MB)")
    with open(os.path.join(folder, "theme.json"), encoding="utf-8") as f:
        meta = json.load(f)
    theme_id = meta.get("id", "")
    if not isinstance(theme_id, str) or not THEME_ID.fullmatch(theme_id):
        raise ValueError(f"bad id {theme_id!r} (1-40 lowercase letters, digits, - or _)")
    if theme_id == BUILT_IN_ID:
        raise ValueError("the id of the app's built-in theme")
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name in names:
            info = zipfile.ZipInfo(f"{theme_id}/{name}", STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(os.path.join(folder, name), "rb") as f:
                z.writestr(info, f.read())
    return meta, data.getvalue()


def setup(context):
    from fastapi import HTTPException
    from fastapi.responses import Response

    context.provide("themes_host", 1)
    hosted = {}  # id -> (list entry, zip bytes)
    root = os.path.join(context.folder, "themes")
    for name in sorted(os.listdir(root)) if os.path.isdir(root) else []:
        folder = os.path.join(root, name)
        if not os.path.isdir(folder):
            continue
        try:
            meta, data = pack(folder)
        except (ValueError, OSError) as e:
            context.log(f"Theme {name} is not hosted: {e}")
            continue
        if meta["id"] in hosted:
            context.log(f"Theme {name} is not hosted: the id {meta['id']} is taken")
            continue
        hosted[meta["id"]] = ({"id": meta["id"], "name": str(meta.get("name", meta["id"])),
                               "version": str(meta.get("version", "")), "size": len(data),
                               "sha256": hashlib.sha256(data).hexdigest()}, data)

    @context.router.get("/list")
    async def theme_list():
        return {"themes": [entry for entry, _ in hosted.values()]}

    @context.router.get("/{theme_id}.zip")
    async def theme_zip(theme_id: str):
        if theme_id not in hosted:
            raise HTTPException(404, "No such theme")
        return Response(hosted[theme_id][1], media_type="application/zip")

    # For apps that can't download themes yet (before 1.2.1); no theme names, more come later
    @context.notice("themes_host")
    def hosts_text():
        return "This server hosts themes. Update TG Flip to 1.2.1 or newer to download them." if hosted else None

    @context.on_start
    async def started():
        names = ", ".join(f"{entry['name']} {entry['version']}" for entry, _ in hosted.values())
        context.log(f"Hosting {len(hosted)} theme(s): {names or 'none'}")
