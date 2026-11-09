"""The server keeps no log of its own (since 1.8.0). What it prints goes to its console, as in any
program; keeping those lines, showing requests and sharing them with the apps' Under the hood > Live
logs is the optional logging module's job (modules/logs; docs/logging-module-plan.md).

All that is left here: uvicorn's own access log is turned off at startup, since it would print every
request's address even on a server without that module (the module prints its own, with the account).
"""
import logging


def start_log_capture():
    logging.getLogger("uvicorn.access").disabled = True
