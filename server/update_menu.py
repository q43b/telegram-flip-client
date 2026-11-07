"""Maintenance mode's "u) Update the server" (docs/self-update-plan.md, part 3): check GitHub for
a newer server (pre-releases only when asked for), or take a downloaded zip; check it against
its .sha256 (and GitHub's own checksum); unpack it into update/; then swap it in for the program
(tgflip-server.exe and _internal), keeping the old one in previous/ for "Roll back".

Only from Maintenance mode.bat, which runs this menu from a copy of the program (maintenance/),
so the program itself isn't in use. The swap renames whole folders (close to instant), the old
version first into previous.new/, which only replaces previous/ once all went well; a swap that
stopped halfway (a power cut) is repaired on the next run. Your data (.env, accounts, sessions,
modules, caches) is never touched.
"""
import hashlib
import os
import posixpath
import shutil
import zipfile

from tgflip import updates
from tgflip.settings import VERSION, app_version_key

EXE = "tgflip-server.exe"
CONTENTS = "_internal"
PROGRAM = (EXE, CONTENTS)
STAGING = "update"
PREVIOUS = "previous"
HOLDING = "previous.new"  # the old version while a swap is under way
VERSION_FILE = "version.txt"  # in previous/: which version is kept there
BAT = "Maintenance mode.bat"
REPLACED_TOO = ("README.txt", ".env.example")  # plain files, replaced right away
installed_now = None  # the version this menu put in place (shown until the server restarts)


def ask(prompt):
    return input(f"{prompt}: ").strip()


def yes(prompt):
    return ask(f"{prompt} (y/N)").lower() in ("y", "yes")


def current_version():
    return installed_now or VERSION


def can_update():
    """True from Maintenance mode.bat's copy of the packaged program; otherwise says why not."""
    if not os.environ.get("TGFLIP_MAINTENANCE_COPY"):
        print("  Updating works from \"Maintenance mode.bat\" (it runs this menu from a copy of the\n"
              "  program, so the program itself can be replaced). Close this and open that instead.")
        return False
    return True


def move_program(source, target):
    for name in PROGRAM:
        if os.path.exists(os.path.join(source, name)) and not os.path.exists(os.path.join(target, name)):
            os.replace(os.path.join(source, name), os.path.join(target, name))


def repair():
    """A swap that stopped halfway: the version from before it goes back in place. And the
    leftovers of an unfinished download or swap go."""
    for kept in (HOLDING, PREVIOUS):
        if not os.path.exists(EXE) and os.path.exists(os.path.join(kept, EXE)):
            move_program(kept, ".")
            print("An update didn't finish last time: the version from before it is back in place.")
    if os.path.isdir(HOLDING) and not any(os.path.exists(os.path.join(HOLDING, n)) for n in PROGRAM):
        shutil.rmtree(HOLDING, ignore_errors=True)
    shutil.rmtree(STAGING, ignore_errors=True)


def kept_version():
    try:
        with open(os.path.join(PREVIOUS, VERSION_FILE), encoding="utf-8") as f:
            return f.read().strip() or None
    except OSError:
        return None


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_sha256(zip_path, sha_path, also=None):
    """True if the zip matches its .sha256 (and GitHub's checksum, when given)."""
    actual = sha256_of(zip_path)
    try:
        expected = open(sha_path, encoding="ascii", errors="replace").read().split()[0].lower()
    except (OSError, IndexError):
        print("  The .sha256 file can't be read. Not installed.")
        return False
    if actual != expected or (also and actual != also.lower()):
        print("  The zip doesn't match its .sha256: damaged or changed. Not installed.")
        return False
    print("  sha256 checked: the zip is complete and unchanged.")
    return True


def unpack(zip_path):
    """The zip's tgflip-server folder unpacked into update/; returns that folder, or None (says why)."""
    shutil.rmtree(STAGING, ignore_errors=True)
    try:
        with zipfile.ZipFile(zip_path) as z:
            names = z.namelist()
            for name in names:
                clean = posixpath.normpath(name)
                if name.startswith(("/", "\\")) or ":" in name or ".." in clean.split("/") or \
                        not (clean == "tgflip-server" or clean.startswith("tgflip-server/")):
                    print(f"  The zip has a file outside its folder: {name}. Not installed.")
                    return None
            if f"tgflip-server/{EXE}" not in names or not any(n.startswith(f"tgflip-server/{CONTENTS}/") for n in names):
                print("  That zip isn't a TG Flip server (no tgflip-server.exe and _internal). Not installed.")
                return None
            print("  Unpacking...")
            z.extractall(STAGING)
    except zipfile.BadZipFile:
        print("  That is not a zip file.")
        return None
    return os.path.join(STAGING, "tgflip-server")


def swap_in(new_folder, new_version, keep_as):
    """The program in new_folder replaces the current one (keep_as: its version), which is kept
    in previous/. True when done; on any failure the current one stays or is put back."""
    global installed_now
    shutil.rmtree(HOLDING, ignore_errors=True)
    os.makedirs(HOLDING)
    try:
        for name in PROGRAM:
            os.replace(name, os.path.join(HOLDING, name))
    except OSError:
        move_program(HOLDING, ".")
        shutil.rmtree(HOLDING, ignore_errors=True)
        print("  The server program is in use: close the running server first, then try again.")
        return False
    try:
        for name in PROGRAM:
            os.replace(os.path.join(new_folder, name), name)
    except OSError as e:
        for name in PROGRAM:
            if os.path.exists(name) and os.path.exists(os.path.join(HOLDING, name)):
                shutil.rmtree(name) if os.path.isdir(name) else os.remove(name)
        move_program(HOLDING, ".")
        shutil.rmtree(HOLDING, ignore_errors=True)
        print(f"  Couldn't put the new version in place ({e}); the old one is back.")
        return False
    with open(os.path.join(HOLDING, VERSION_FILE), "w", encoding="utf-8") as f:
        f.write(keep_as)
    shutil.rmtree(PREVIOUS, ignore_errors=True)
    os.replace(HOLDING, PREVIOUS)
    for name in REPLACED_TOO:
        if os.path.exists(os.path.join(new_folder, name)):
            shutil.copy2(os.path.join(new_folder, name), name)
    # The bat that started this menu is still being read by Windows: the new one waits next to
    # it, and the server puts it in place when it next starts (run_server.swap_in_new_bat)
    new_bat = os.path.join(new_folder, BAT)
    if os.path.exists(new_bat):
        with open(new_bat, "rb") as f:
            new = f.read()
        old = open(BAT, "rb").read() if os.path.exists(BAT) else None
        if new != old:
            shutil.copy2(new_bat, BAT + ".new")
    shutil.rmtree(STAGING, ignore_errors=True)
    installed_now = new_version
    print(f"\nDone: {keep_as} -> {new_version}. Choose s (in the main menu) to start it. "
          f"Roll back is in this menu.")
    return True


def install_zip(zip_path, sha_path, version, github_sha256=None):
    if sha_path is not None and not check_sha256(zip_path, sha_path, github_sha256):
        return False
    folder = unpack(zip_path)
    if folder is None:
        return False
    return swap_in(folder, version, current_version())


def from_github():
    include = yes("Include pre-releases (betas)?")
    print("  Asking GitHub...")
    try:
        release = updates.newest_server(current_version(), include)
    except updates.UpdateError as e:
        print(f"  {e}. You can also install a zip you downloaded (d).")
        return
    if release is None:
        print(f"  {current_version()} is the newest" + ("" if include else " stable") + " version.")
        return
    print(f"\n  {release.version}" + (" (pre-release)" if release.prerelease else "") +
          f", {release.size / 1024 / 1024:.0f} MB. What's new:")
    for line in release.notes.splitlines()[:12]:
        print("    " + line)
    if not yes(f"\nDownload and install {release.version}"):
        return
    downloads = "update-download"  # not update/: unpacking empties that
    shutil.rmtree(downloads, ignore_errors=True)
    os.makedirs(downloads)
    zip_path = os.path.join(downloads, release.zip_name)
    try:
        updates.download(release.sha256_url, zip_path + ".sha256")
        shown = [-1]

        def progress(done, total):
            step = done * 10 // total if total else 0
            if step != shown[0]:
                shown[0] = step
                print(f"  Downloading: {done / 1024 / 1024:.0f} of {total / 1024 / 1024:.0f} MB")
        updates.download(release.zip_url, zip_path, progress)
        install_zip(zip_path, zip_path + ".sha256", release.version, release.github_sha256)
    except updates.UpdateError as e:
        print(f"  {e}. Nothing was changed.")
    finally:
        shutil.rmtree(downloads, ignore_errors=True)


def from_zip():
    path = ask("Path of the server's zip (you can drag the file into this window)").strip('"').strip("'")
    if not path:
        return
    if not os.path.isfile(path):
        print("  No such file.")
        return
    name = os.path.basename(path)
    version = name[len("tgflip-server-"):-len("-win64.zip")] \
        if name.startswith("tgflip-server-") and name.endswith("-win64.zip") else "?"
    newer = app_version_key(version) is not None and app_version_key(version) > app_version_key(current_version())
    if not newer and not yes(f"  That is {version}, not newer than {current_version()}. Install it anyway"):
        return
    sha_path = path + ".sha256"
    if not os.path.exists(sha_path):
        print("  There is no .sha256 file next to the zip, so it can't be checked.")
        if not yes("Install it anyway? Only if you trust where it came from"):
            return
        sha_path = None
    install_zip(path, sha_path, version)


def roll_back():
    kept = kept_version() or "the earlier version"
    if not os.path.exists(os.path.join(PREVIOUS, EXE)):
        print("  There is no earlier version to go back to.")
        return
    if not yes(f"Go back to {kept} ({current_version()} is kept, to go forward again)"):
        return
    # The kept version comes in through the same swap; the current one is kept instead
    back = os.path.join(STAGING, "tgflip-server")
    shutil.rmtree(STAGING, ignore_errors=True)
    os.makedirs(back)
    move_program(PREVIOUS, back)
    if not swap_in(back, kept, current_version()):
        move_program(back, PREVIOUS)  # not swapped: it stays the kept version


def menu():
    if not can_update():
        return
    repair()
    while True:
        print(f"\nThis server: {current_version()}" + (" (start it with s)" if installed_now else ""))
        print("  g) Check GitHub for a newer version")
        print("  d) Install a zip I downloaded")
        if os.path.exists(os.path.join(PREVIOUS, EXE)):
            print(f"  r) Roll back to {kept_version() or 'the earlier version'}")
        print("  b) Back")
        choice = ask("Choose").lower()
        if choice == "g":
            from_github()
        elif choice == "d":
            from_zip()
        elif choice == "r":
            roll_back()
        elif choice in ("b", ""):
            return
