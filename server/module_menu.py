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
            print(f"  {number}. {manifest.get('name', name)} {manifest.get('version', '?')}: {status}"
                  f" (provides {provides})")
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


def install(server):
    path = ask("Path of the module's zip (you can drag the file into this window)").strip('"').strip("'")
    if not path:
        return
    if not os.path.isfile(path):
        print("  No such file.")
        return
    if not sha256_ok(path):  # first: a damaged or changed zip isn't even opened
        return
    try:
        with zipfile.ZipFile(path) as z:
            module_id, text = zip_module(z)
            manifest = ml.check_manifest(text, module_id, server.VERSION, server.app_version_key)
            target = os.path.join(ml.MODULES_FOLDER, module_id)
            if os.path.exists(target):
                old = next((m for i, m, _ in installed(server) if i == module_id), None)
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


def menu(server):
    while True:
        show(server)
        print("\n  i) Install a module from a zip")
        print("  o) Switch a module on or off")
        print("  d) Remove a module")
        print("  b) Back")
        choice = ask("Choose").lower()
        if choice == "i":
            install(server)
        elif choice == "o":
            switch(server)
        elif choice == "d":
            remove(server)
        elif choice in ("b", ""):
            return
