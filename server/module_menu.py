"""Maintenance mode's "x) Modules" (docs/modules-plan.md): list the installed modules, install
one from a zip, switch one on or off, remove one. Runs before the server loads any module, so
module files can be replaced (module_loader is only used for its checks and modules.json).

Only the PC's owner installs modules, here: a module is code with full access to every
account's Telegram session.
"""
import hashlib
import os
import posixpath
import shutil
import zipfile

import module_loader as ml


def ask(prompt):
    return input(f"{prompt}: ").strip()


def yes(prompt):
    return ask(f"{prompt} (y/N)").lower() in ("y", "yes")


def installed(server):
    """[(id, manifest or None, status)] for every module folder: status as the server would
    have it at startup ("on", "off", or "can't load: <reason>")."""
    found = []
    if not os.path.isdir(ml.MODULES_FOLDER):
        return found
    off = ml.switched_off_ids()
    for name in sorted(os.listdir(ml.MODULES_FOLDER)):
        folder = os.path.join(ml.MODULES_FOLDER, name)
        if not os.path.isdir(folder) or name.endswith((".new", ".old")):
            continue
        try:
            manifest = ml.read_manifest(folder, server.VERSION, server.app_version_key)
            found.append((name, manifest, "off" if name in off else "on"))
        except ml.ModuleError as e:
            found.append((name, None, f"can't load: {e}"))
    return found


def show(server):
    modules = installed(server)
    print("\nModules:")
    if not modules:
        print("  (none installed)")
    for number, (name, manifest, status) in enumerate(modules, 1):
        if manifest:
            provides = ", ".join(manifest.get("provides", {})) or "nothing"
            uses = manifest.get("uses", [])
            print(f"  {number}. {manifest.get('name', name)} {manifest.get('version', '?')}: {status}"
                  f" (provides {provides}{'; uses ' + ', '.join(uses) if uses else ''})")
        else:
            print(f"  {number}. {name}: {status}")
    return modules


def pick(modules, what):
    if not modules:
        print("  No modules installed.")
        return None
    answer = ask(f"Number of the module to {what} (Enter to cancel)")
    if answer.isdigit() and 1 <= int(answer) <= len(modules):
        return modules[int(answer) - 1]
    if answer:
        print("  No module with that number.")
    return None


def sha256_ok(zip_path):
    """True to go on: the .sha256 next to the zip matches (or, missing, the owner says so)."""
    check_file = zip_path + ".sha256"
    digest = hashlib.sha256(open(zip_path, "rb").read()).hexdigest()
    if not os.path.exists(check_file):
        print("  There is no .sha256 file next to the zip, so it can't be checked.")
        return yes("Install it anyway? Only if you trust where it came from")
    expected = open(check_file, encoding="ascii", errors="replace").read().split()
    if not expected or expected[0].lower() != digest:
        print("  The zip doesn't match its .sha256: damaged or changed. Not installed.")
        return False
    print("  sha256 checked: the zip is complete and unchanged.")
    return True


def zip_module(z):
    """The module id a zip holds (one top folder) and its module.json text. Refuses zips with
    anything outside that folder, absolute paths or .. (they could write anywhere)."""
    names = z.namelist()
    tops = {name.split("/", 1)[0] for name in names}
    if len(tops) != 1:
        raise ml.ModuleError("the zip must hold exactly one folder, the module's")
    module_id = tops.pop()
    for name in names:
        clean = posixpath.normpath(name)
        if name.startswith(("/", "\\")) or ":" in name or ".." in clean.split("/") or \
                not clean.startswith(module_id + "/") and clean != module_id:
            raise ml.ModuleError(f"the zip has a file outside its folder: {name}")
    try:
        text = z.read(f"{module_id}/module.json").decode("utf-8")
    except KeyError:
        raise ml.ModuleError("no module.json in the zip")
    return module_id, text


def confirm_uses(manifest, old):
    """What a module says it uses (module.json "uses"), confirmed by the owner when it is
    installed, and again, with "no" as the default, when an update adds something to what the
    installed version used. True to go on. Not security (a module runs inside the server and can
    do anything): the owner should know what a module does with their messages."""
    before = old.get("uses", []) if old else []
    added = [use for use in manifest.get("uses", []) if use not in before]
    if not added:
        return True
    name = manifest.get("name", manifest["id"])
    if old:
        print(f"\n  WARNING: this update of {name} asks for more than the version installed:")
    else:
        print(f"\n  WARNING: {name} asks to:")
    for use in added:
        print(f"    - {ml.KNOWN_USES.get(use) or 'do something this server does not know (' + use + ')'}")
    print("  A module runs inside the server and could do anything, so allow this only if you trust\n"
          "  where the module came from.")
    return yes("Allow this")


def install(server):
    path = ask("Path of the module's zip (you can drag the file into this window)").strip('"').strip("'")
    if not path:
        return
    if not os.path.isfile(path):
        print("  No such file.")
        return
    install_zip(server, path)


def install_zip(server, path, replace_without_asking=False):
    """Installs the module in the zip at path (its .sha256 next to it), after the checks."""
    if not sha256_ok(path):  # first: a damaged or changed zip isn't even opened
        return
    try:
        with zipfile.ZipFile(path) as z:
            module_id, text = zip_module(z)
            manifest = ml.check_manifest(text, module_id, server.VERSION, server.app_version_key)
            target = os.path.join(ml.MODULES_FOLDER, module_id)
            old = next((m for i, m, _ in installed(server) if i == module_id), None)
            if not confirm_uses(manifest, old):
                print("  Not installed.")
                return
            if os.path.exists(target) and not replace_without_asking:
                old_version = old.get("version", "?") if old else "a version that can't load"
                if not yes(f"{manifest.get('name', module_id)} {old_version} is installed. "
                           f"Replace it with {manifest['version']}"):
                    return
            # Unpacked next to it first, then swapped in: never half a module
            new = target + ".new"
            shutil.rmtree(new, ignore_errors=True)
            os.makedirs(new)
            for name in z.namelist():
                inside = name[len(module_id) + 1:]
                if not inside or name.endswith("/"):
                    continue
                destination = os.path.join(new, *inside.split("/"))
                os.makedirs(os.path.dirname(destination), exist_ok=True)
                with z.open(name) as source, open(destination, "wb") as out:
                    shutil.copyfileobj(source, out)
    except zipfile.BadZipFile:
        print("  That is not a zip file.")
        return
    except ml.ModuleError as e:
        print(f"  Can't install it: {e}.")
        return
    if os.path.exists(target):
        shutil.rmtree(target)
    os.replace(new, target)
    state = ml.read_state()
    state[module_id] = {"enabled": True}
    ml.write_state(state)
    print(f"Installed {manifest.get('name', module_id)} {manifest['version']}; it is on and "
          "starts with the server.")


def switch(server):
    chosen = pick(show(server), "switch on or off")
    if chosen is None:
        return
    module_id, manifest, status = chosen
    state = ml.read_state()
    on = module_id in ml.switched_off_ids()  # it was off: switch on
    state[module_id] = {"enabled": on}
    ml.write_state(state)
    print(f"{module_id} is now {'on' if on else 'off'} (from the next start of the server).")


def remove(server):
    chosen = pick(show(server), "remove")
    if chosen is None:
        return
    module_id = chosen[0]
    if not yes(f"Remove the module {module_id}"):
        return
    shutil.rmtree(os.path.join(ml.MODULES_FOLDER, module_id))
    shutil.rmtree(os.path.join(ml.MODULE_CACHE, module_id), ignore_errors=True)
    data = os.path.join(ml.MODULE_DATA, module_id)
    if os.path.isdir(data) and yes("Also delete the files it kept (its module_data folder)"):
        shutil.rmtree(data)
    state = ml.read_state()
    state.pop(module_id, None)
    ml.write_state(state)
    print(f"Removed {module_id}.")


def check_updates(server):
    """Newer releases of the installed modules (module-<id>-v<version>), downloaded and installed
    through install_zip, one by one, each after asking."""
    from tgflip import updates
    modules = [(i, m) for i, m, _ in installed(server) if m]
    if not modules:
        print("  No modules installed.")
        return
    include = yes("Include pre-releases?")
    print("  Asking GitHub...")
    try:
        found = updates.releases()
    except updates.UpdateError as e:
        print(f"  {e}.")
        return
    newer = [(i, m, updates.newest_module(i, m.get("version", "0.0.0"), include, found)) for i, m in modules]
    newer = [(i, m, r) for i, m, r in newer if r]
    if not newer:
        print("  The installed modules are the newest" + ("" if include else " stable") + " versions.")
        return
    for module_id, manifest, release in newer:
        print(f"\n  {manifest.get('name', module_id)}: {manifest.get('version')} -> {release.version}. What's new:")
        for line in release.notes.splitlines()[:8]:
            print("    " + line)
        if not yes(f"Update {manifest.get('name', module_id)} to {release.version}"):
            continue
        download_and_install(server, release, replace=True)


def get_new(server):
    """Modules released on GitHub that aren't installed here: pick one, read its notes, install
    it (stable releases only, unless pre-releases are asked for)."""
    from tgflip import updates
    have = {i for i, _, _ in installed(server)}
    include = yes("Include pre-releases?")
    print("  Asking GitHub...")
    try:
        found = updates.releases()
    except updates.UpdateError as e:
        print(f"  {e}.")
        return
    offered = [r for r in (updates.newest_module(i, "0.0.0", include, found)
                           for i in updates.module_ids(found) if i not in have) if r]
    if not offered:
        print("  Every module on GitHub is installed already" + ("." if include else " (stable releases)."))
        return
    for n, release in enumerate(offered, 1):
        print(f"  {n}) {release.title} ({release.size / 1024 / 1024:.1f} MB)")
    choice = ask("Which one (number, empty = none)")
    if not choice.isdigit() or not 1 <= int(choice) <= len(offered):
        return
    release = offered[int(choice) - 1]
    print(f"\n  {release.title}:")
    for line in release.notes.splitlines()[:8]:
        print("    " + line)
    if yes(f"Install {release.title}"):
        download_and_install(server, release, replace=False)


def download_and_install(server, release, replace):
    """A module release from GitHub: its .sha256 first, then the zip, then install_zip (which
    checks the zip against it, then the manifest). Downloads go into module-download/ and are
    removed afterwards."""
    from tgflip import updates
    downloads = "module-download"
    shutil.rmtree(downloads, ignore_errors=True)
    os.makedirs(downloads)
    path = os.path.join(downloads, release.zip_name)
    try:
        updates.download(release.sha256_url, path + ".sha256")
        print(f"  Downloading {release.size / 1024 / 1024:.1f} MB...")
        updates.download(release.zip_url, path)
        install_zip(server, path, replace_without_asking=replace)
    except updates.UpdateError as e:
        print(f"  {e}. Not installed.")
    finally:
        shutil.rmtree(downloads, ignore_errors=True)


def menu(server):
    while True:
        show(server)
        print("\n  i) Install a module from a zip")
        print("  o) Switch a module on or off")
        print("  d) Remove a module")
        print("  c) Check for module updates")
        print("  g) Get a new module from GitHub")
        print("  b) Back")
        choice = ask("Choose").lower()
        if choice == "i":
            install(server)
        elif choice == "o":
            switch(server)
        elif choice == "c":
            check_updates(server)
        elif choice == "g":
            get_new(server)
        elif choice == "d":
            remove(server)
        elif choice in ("b", ""):
            return
