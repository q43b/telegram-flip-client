"""Which version of the app is behind a request (since 1.4.0), for the filters of modules
(module_loader.Traffic.client_version).

Apps from 0.7.1 on send x-app-version when they open a live connection (/ws, /logs), but not on
ordinary requests (apps from 1.4.0 do, which is exact). So the version seen on a live connection is
remembered per (account, address), and a plain request from that address is taken to come from
that app. Wrong only when two phones with different versions share an address and an account:
then the newest version is used, once noted in the log (an old phone is then treated as new, which
only means it gets no extra help; the other way round would add text a new app already shows).
"""
import time

from tgflip.settings import app_version_key

REMEMBER = 7 * 24 * 3600  # seconds a version counts after it was last seen
RECENT = 24 * 3600  # versions seen within this long count as "behind this address now"

seen = {}  # (account name, address) -> {version: when last seen}
warned = set()  # (account name, address, versions) already reported


def note(account_name, host, version):
    """A live connection opened with this version header (None, or empty = none: nothing noted)."""
    if not version:
        return
    versions = seen.setdefault((account_name, host), {})
    now = time.time()
    versions[version] = now
    recent = sorted(v for v, when in versions.items() if now - when < RECENT)
    if len(recent) > 1 and (account_name, host, tuple(recent)) not in warned:
        warned.add((account_name, host, tuple(recent)))
        print(f"Several app versions behind one address of [{account_name}]: {', '.join(recent)}; "
              "modules that adapt to the app's version go by the newest")


def version_of(account_name, host):
    """The newest version noted at this address for this account lately, or None."""
    versions = seen.get((account_name, host))
    if not versions:
        return None
    now = time.time()
    known = [v for v, when in versions.items() if now - when < REMEMBER and app_version_key(v) is not None]
    return max(known, key=app_version_key, default=None)
