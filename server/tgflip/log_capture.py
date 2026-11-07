"""The server's console log, kept for the app's Live logs (WebSocket /logs): every line
printed goes through LogTee on its way to the console.
"""
import re
import sys
import logging
import asyncio
import time
from collections import deque


# The server's own console output, kept for the app's "under the hood" screen (WebSocket
# /logs): the last LOG_KEEP lines, with the time. Everything printed goes through LogTee on
# its way to the console. Sending lines to the app writes nothing to the log itself, so
# watching it can't make it grow.
LOG_KEEP = 500


log_lines = deque(maxlen=LOG_KEEP)


log_listeners = set()  # one asyncio.Queue per open /logs connection


log_loop = None        # the server's event loop, set at startup


ANSI_COLORS = re.compile(r"\x1b\[[0-9;]*m")  # uvicorn colors its words in a terminal


def record_log(text):
    line = f"{time.strftime('%H:%M:%S')} {ANSI_COLORS.sub('', text).rstrip()}"
    log_lines.append(line)
    if log_loop is not None and log_listeners:
        log_loop.call_soon_threadsafe(notify_log_listeners, line)  # safe from any thread


def notify_log_listeners(line):
    for queue in list(log_listeners):
        try:
            queue.put_nowait(line)
        except asyncio.QueueFull:
            pass  # that viewer is far behind; it misses a line rather than holding up the server


class LogTee:
    """Passes console output on unchanged and records each finished line."""

    def __init__(self, stream):
        self.stream = stream
        self.partial = ""

    def write(self, text):
        self.stream.write(text)
        self.partial += text
        while "\n" in self.partial:
            line, self.partial = self.partial.split("\n", 1)
            if line.strip():
                record_log(line)
        return len(text)

    def flush(self):
        self.stream.flush()

    def __getattr__(self, name):  # encoding, isatty, ... as the real console
        return getattr(self.stream, name)


class LogRecorder(logging.Handler):
    """For uvicorn's loggers when they write to the console directly (see start_log_capture)."""

    def emit(self, record):
        record_log(f"{record.levelname}: {record.getMessage()}")


if sys.stdout is not None:
    sys.stdout = LogTee(sys.stdout)


if sys.stderr is not None:
    sys.stderr = LogTee(sys.stderr)


def start_log_capture():
    """Called at startup. run_server.py sets up uvicorn after importing this file, so uvicorn
    already writes through LogTee; `uvicorn server:app` sets up its logging first, with the
    real console, so its loggers get a LogRecorder instead (never both: no doubled lines)."""
    global log_loop
    log_loop = asyncio.get_running_loop()
    logger = logging.getLogger("uvicorn")
    if not any(isinstance(getattr(h, "stream", None), LogTee) for h in logger.handlers):
        logger.addHandler(LogRecorder())
    # AccessLog prints these lines instead, with the account
    logging.getLogger("uvicorn.access").disabled = True
