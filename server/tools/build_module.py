"""Builds a module's release files from its source in modules/<id>/ (docs/modules-plan.md):
dist/tgflip-module-<id>-<version>.zip (one folder, <id>/, inside) and the .sha256 next to it,
which maintenance mode checks before installing.

    python server/tools/build_module.py helloworld
"""
import hashlib
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SKIP_FOLDERS = {"__pycache__"}
SKIP_FILES = (".pyc", ".pyo")


def build(module_id):
    source = os.path.join(ROOT, "modules", module_id)
    with open(os.path.join(source, "module.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    if manifest.get("id") != module_id:
        sys.exit(f"module.json's id is {manifest.get('id')!r}, not {module_id!r}")
    os.makedirs(os.path.join(ROOT, "dist"), exist_ok=True)
    zip_path = os.path.join(ROOT, "dist", f"tgflip-module-{module_id}-{manifest['version']}.zip")
    count = 0
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for parent, folders, files in os.walk(source):
            folders[:] = sorted(d for d in folders if d not in SKIP_FOLDERS)
            for name in sorted(files):
                if name.endswith(SKIP_FILES):
                    continue
                path = os.path.join(parent, name)
                inside = os.path.join(module_id, os.path.relpath(path, source)).replace(os.sep, "/")
                z.write(path, inside)
                count += 1
    digest = hashlib.sha256(open(zip_path, "rb").read()).hexdigest()
    with open(zip_path + ".sha256", "w", encoding="ascii") as f:
        f.write(f"{digest}  {os.path.basename(zip_path)}\n")  # the same format as sha256sum
    print(f"Built {zip_path} ({count} files, {os.path.getsize(zip_path) / 1024:.1f} KB)")
    print(f"sha256 {digest}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: python server/tools/build_module.py <module id>")
    build(sys.argv[1])
