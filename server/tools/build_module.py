"""Builds a module's release files from its source in modules/<id>/ (docs/modules-plan.md):
dist/tgflip-module-<id>-<version>.zip (one folder, <id>/, inside) and the .sha256 next to it,
which maintenance mode checks before installing.

A module's requirements.txt (if it has one) lists the libraries it brings: they are installed
into the zip's <id>/lib/ at build time (ready-built wheels only, no dependencies pulled in), so
they never enter git. With --install the built module is also unpacked into the development
server's server/modules/<id>/, replacing the copy there.

    python server/tools/build_module.py helloworld
    python server/tools/build_module.py animated --install
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SKIP_FOLDERS = {"__pycache__"}
SKIP_FILES = (".pyc", ".pyo")


def add_folder(z, source, inside_root, skip_requirements=False):
    count = 0
    for parent, folders, files in os.walk(source):
        folders[:] = sorted(d for d in folders if d not in SKIP_FOLDERS)
        for name in sorted(files):
            if name.endswith(SKIP_FILES):
                continue
            path = os.path.join(parent, name)
            relative = os.path.relpath(path, source)
            if skip_requirements and relative == "requirements.txt":
                continue  # build-time only: the libraries themselves are in lib/
            z.write(path, os.path.join(inside_root, relative).replace(os.sep, "/"))
            count += 1
    return count


def build(module_id):
    source = os.path.join(ROOT, "modules", module_id)
    with open(os.path.join(source, "module.json"), encoding="utf-8") as f:
        manifest = json.load(f)
    if manifest.get("id") != module_id:
        sys.exit(f"module.json's id is {manifest.get('id')!r}, not {module_id!r}")
    os.makedirs(os.path.join(ROOT, "dist"), exist_ok=True)
    zip_path = os.path.join(ROOT, "dist", f"tgflip-module-{module_id}-{manifest['version']}.zip")
    requirements = os.path.join(source, "requirements.txt")
    with tempfile.TemporaryDirectory() as staging, \
            zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        count = add_folder(z, source, module_id, skip_requirements=True)
        if os.path.exists(requirements):
            lib = os.path.join(staging, "lib")
            print(f"Installing {module_id}'s libraries into its lib/ ...")
            subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "--target", lib,
                            "--only-binary=:all:", "--no-deps", "-r", requirements], check=True)
            count += add_folder(z, lib, f"{module_id}/lib")
    digest = hashlib.sha256(open(zip_path, "rb").read()).hexdigest()
    with open(zip_path + ".sha256", "w", encoding="ascii") as f:
        f.write(f"{digest}  {os.path.basename(zip_path)}\n")  # the same format as sha256sum
    print(f"Built {zip_path} ({count} files, {os.path.getsize(zip_path) / 1024 / 1024:.2f} MB)")
    print(f"sha256 {digest}")
    return zip_path


def install_for_development(module_id, zip_path):
    """Unpacks the built zip into server/modules/<id>/, as maintenance mode would."""
    target = os.path.join(ROOT, "server", "modules", module_id)
    shutil.rmtree(target, ignore_errors=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(os.path.join(ROOT, "server", "modules"))
    print(f"Installed into {target} (development server)")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--install"]
    if len(args) != 1:
        sys.exit("Usage: python server/tools/build_module.py <module id> [--install]")
    built = build(args[0])
    if "--install" in sys.argv[1:]:
        install_for_development(args[0], built)
