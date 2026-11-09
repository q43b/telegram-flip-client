# Logging as an optional module (1.8.0)

Owner (2026-11-09): "if someone outside were considering using our server they could see logging as a
red flag", so the server stops keeping and showing logs itself, and the logging module does it. Done in
server 1.8.0, app 1.8.0 and module `logs` 1.0.0.

## What moved

| Before 1.8.0 (server) | Now |
|---|---|
| `log_capture.py`: `LogTee` on stdout/stderr, a ring of the last 500 lines, listeners | module `logs`: `Tee`, `Recorder`, the same ring |
| `AccessLog` printed a line per request (address, account, path, status) | the server only tells modules (`request.done` observer); the module prints the line |
| "App connected from <ip> [<account>]: TG Flip x" | the server tells modules (`app.connected`); the module prints it |
| `/logs` WebSocket: ring, live lines, "Started/Stopped live sharing…" | the server keeps a thin `/logs` (token, who may see it) and hands the connection to the module (`logs.serve` hook) |
| "Private/Shared server: … may see this log" at startup | "Private server"/"Shared server" only; the module says who may see the log |
| `/server_info` `log` = may this account see the log | the same, and only when the module is installed |

What the server still prints: its own messages (startup, accounts ready, errors, cache cleaning), as any
program does; nothing with an app's address in it, and nothing is kept.

## Module API (additive)

- `context.observe("request.done" | "app.connected")`: tell-only observers, need `"uses": ["server.log"]`.
- Hook `logs.serve`: async `function(ws) -> True`, needs the same use.
- New use `server.log`, shown when the module is installed or updated (Maintenance mode asks).

## What each kind of app sees on a server without the module

| App | What happens |
|---|---|
| 1.8.0 and newer | Under the hood shows "Live logs (needs the logging module)" (it read `/server_info`: no capability `live_logs`), OK explains. |
| 0.14.0 to 1.7.x | Opens Live logs: `/logs` sends one line, "This server version needs the logging module for its live log, and it isn't installed. The server's owner can install it in Maintenance mode (x, Modules).", and stays open (no reconnect loop). The app shows it as a log line. Also a notice (kind `logging`) in Messages from the server. |
| before 0.13.0-beta | The same line (it used to get "Client too old to verify whether logs are private or shared", which it still gets WITH the module). |

With the module: private servers show the log to every account, shared servers to admins (4003 for
others), exactly as before.

## Behaviour changes to know

- Lines printed before the modules load (the very first lines of a start) are not kept: the module installs
  its capture when the server imports it.
- Without the module the server's console has no request lines at all (uvicorn's own access log stays off).
- Tested: no module / module on a private server / on a shared server (admin and not) with app versions
  1.8.0, 1.7.0, 0.13.0-beta and none; `check_mockgram.py` with the module.
