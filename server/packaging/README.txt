TG Flip server (Windows)
========================

The server the TG Flip phone app talks to. Python and all libraries are included.

First run
---------
1. Double-click tgflip-server.exe.
2. It asks for your Telegram API ID and hash (from https://my.telegram.org,
   "API development tools"), an MTProxy if Telegram is blocked where you are
   (press Enter to skip), the app token (press Enter to generate one), and
   whether the server is shared (see below). These are saved to .env in this folder.
3. It logs in to Telegram: your phone number, the code Telegram sends you,
   and your 2FA password if you have one. The login is saved in tg_session.session.
4. If Windows Firewall asks, allow tgflip-server on private networks,
   so the phone can reach it.

If the server says it started but the phone can't connect: Windows probably
treats your home Wi-Fi as a "Public" network, where it blocks incoming
connections. Set it to Private: Settings > Network & internet > Wi-Fi >
(your network) > Network profile type > Private. Then start the server again
and allow it if Windows asks.

Later runs just start. Stop the server with Ctrl+C or by closing the window.

Several people on one server
----------------------------
Double-click "Maintenance mode.bat". It first makes a fresh copy of the program in the
maintenance folder and runs the menu from there (so the program itself can be updated from it
later); the maintenance folder can be deleted any time the menu isn't open.
Its menu:
  a) Add an account: a name, then that person's phone number, the code Telegram
     sends them and their 2FA password, typed in here. It prints the app token for
     their phone once.
  r) Remove an account (and, if you want, log it out of Telegram)
  n) Rename an account
  m) Give or take admin rights
  p) Private or shared server: on a private server every app may see the live
     log ("Under the hood"); on a shared one only admin accounts may, since the
     log shows everyone's addresses and chats.
  c) Server cache: keep the media fetched for the apps forever, for some days
     since last used, or not at all, and the most space it may take in all
     (each account gets an equal share; also asked at the first run).
  x) Modules: optional features for the server, each its own download
     (tgflip-module-<name>-<version>.zip, from the module's release page).
     Install one from its zip (keep the .sha256 file next to it: the zip is
     checked against it first), switch modules on or off, remove them.
     Installed modules live in the modules folder here. A module is a program
     with access to every account on this server: only install modules from
     the TG Flip releases page.
  s) Start the server, q) Quit
Each account has its own app token, Telegram login (tg_session*.session) and
cached media. The list is in accounts.json.

The photo_cache folder holds small copies of chats' profile photos for the app,
image_cache small copies of photos from messages, video_cache the videos the
phone has played, and sticker_cache sticker pictures, each with one folder per
account. All of them can be deleted at any time; files are fetched again when
needed (video_cache can grow large). To keep them from growing, choose a number
of days, a largest size, or no cache at all, in Maintenance mode (c).

In the phone app
----------------
Settings > Local network: this PC's IP address on your home network, port 9436,
and the app token.

Keep private
------------
.env (your API keys), accounts.json (the app tokens) and the tg_session*.session
files (the Telegram logins) give access to the accounts. Don't share them or
upload them anywhere. To log an account out of this server, remove it in
Maintenance mode. local_favorites*.json hold the app's local favourite stickers.

Updating from before 0.13.0: nothing to do. The first start makes accounts.json
from .env (your account becomes "main") and moves the cached files into its folder.
