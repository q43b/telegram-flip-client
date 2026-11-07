"""The TG Flip server. Its code is in the tgflip package (docs/self-update-plan.md); this file
puts it together, so `uvicorn server:app` and `python run_server.py` work as always, and
offers its names to the module loader and maintenance mode.

The order matters: the log capture first (from then on everything printed is kept for the live
log), then the rest, then the installed modules.
"""
import os
import sys

from tgflip.log_capture import *  # noqa: F401,F403
from tgflip.settings import *  # noqa: F401,F403
from tgflip.account import *  # noqa: F401,F403
from tgflip.account import _current_account  # noqa: F401
from tgflip.telegram import *  # noqa: F401,F403
from tgflip.messages import *  # noqa: F401,F403
from tgflip.cache import *  # noqa: F401,F403
from tgflip.notices import *  # noqa: F401,F403
from tgflip.live import *  # noqa: F401,F403
from tgflip.routes.chats import *  # noqa: F401,F403
from tgflip.routes.media import *  # noqa: F401,F403
from tgflip.routes.stickers import *  # noqa: F401,F403
from tgflip.routes.reactions import *  # noqa: F401,F403
from tgflip.routes.server import *  # noqa: F401,F403
from tgflip.routes.live_connections import *  # noqa: F401,F403
from tgflip.app import *  # noqa: F401,F403

import module_loader


def load_modules():
    """The installed modules (module_loader.py, docs/modules-plan.md). At import, except in
    maintenance mode (run_server.py sets TGFLIP_LOAD_MODULES_LATER), where the menu may still
    replace their files: then just before the server starts."""
    module_loader.load_all(sys.modules[__name__])


if not os.environ.get("TGFLIP_LOAD_MODULES_LATER"):
    load_modules()