TG Flip server (Windows)
========================

The server the TG Flip phone app talks to. Python and all libraries are included.

First run
---------
1. Double-click tgflip-server.exe.
2. It asks for your Telegram API ID and hash (from https://my.telegram.org,
   "API development tools"), an MTProxy if Telegram is blocked where you are
   (press Enter to skip), and the app token (press Enter to generate one).
   These are saved to .env in this folder.
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

The photo_cache folder holds small copies of chats' profile photos for the app,
image_cache small copies of photos from messages, video_cache the videos the
phone has played, and sticker_cache sticker pictures. All of them can be deleted at any time; files are fetched again
when needed (video_cache can grow large).

In the phone app
----------------
Settings > Local network: this PC's IP address on your home network, port 9436,
and the app token.

Keep private
------------
.env (your API keys and token) and tg_session.session (your Telegram login)
give access to your account. Don't share them or upload them anywhere.
To log out of this server, delete tg_session.session.
