# Direct mode: the app as a real Telegram client (plan for 2.0)

Owner (2026-11-13): the app should be able to skip our server and talk to Telegram itself, with
TDLib placed to the side so the app keeps talking "a language it already understands". Our server
stays as it is ("Server mode"); "Direct mode" is a second way to reach Telegram, picked per saved
account.

## Shape

```
screens (chat list, chat, viewer, stickers, ...)        unchanged, apart from how they get their backend
        |
     Backend  (interface: the 31 Telegram things the screens ask for, live events, downloads)
      /     \
ApiClient    TdlibBackend
(Server       (Direct mode: TDLib in the app, translating its objects into our Message, Chat,
 mode: HTTP    Reaction... and its updates into the same live events {type: "message", ...})
 + /ws)
```

- The models (`Message`, `Chat`, `Topic`, `Reaction`, `StickerInfo`...) and the live event JSON stay the
  contract. TdlibBackend's job is what the Python server does today: turn Telegram's objects into them.
- Things only a server has stay on `ApiClient` and its screens (Under the hood: server line, Live logs,
  Messages from the server, Modules; themes from the server; admin messages; the animated stickers
  capability). In Direct mode those screens say "server only".
- No HTTP server inside the app: it would add a hop, a port and JSON both ways for nothing.

## Proxies (owner, 2026-11-13)

Telegram is blocked where the owner lives, so Direct mode needs a way through, set per account:
- **Any MTProto proxy** the user enters: server, port, secret (TDLib `proxyTypeMtproto`).
- **SOCKS5** (server, port, optional user and password; TDLib `proxyTypeSocks5`): also the way in for
  a local proxy running on the phone.
- **Later: an embedded tg-ws-proxy.** Someone has ported it to Android; it runs a local proxy that
  carries Telegram's traffic over WebSocket. Embedded, the app would start it and point TDLib at it
  on 127.0.0.1 (as SOCKS5, or whatever its port speaks: to check when we get to it). The proxy
  settings are built so that this is one more choice in the same list, not a new mechanism.
- "No proxy" for places where Telegram isn't blocked.

## Steps

1. **The Backend interface** (no behaviour change): `Backend.kt` (interface, `LiveListener`,
   `LiveStream`, `Download`), `ApiClient : Backend`, `Backends.create(context)` instead of building
   `ApiClient` in every screen; live events and video/file downloads no longer hand OkHttp objects
   to the screens. Checked against Mockgram and the real server: everything as before.
2. **Spike** (done, results below): TDLib for Android (prebuilt or built with the NDK; the Kyocera's CPU architecture),
   loads in the app, connects through an MTProto proxy. Measure the APK size it adds.
3. **Account setup for Direct mode**: Settings, Network, Account gets "Server" or "Direct" per
   account; Direct asks for api_id and api_hash (the user's own, from my.telegram.org, like the
   server's .env), the proxy, then the login: phone number, code, two-step password, all built for
   the d-pad. Each Direct account has its own TDLib folder (the app's account switcher already gives
   each account its own folders).
4. **TdlibBackend, text**: chat list (with folders' chats by id), topics, messages, send, edit,
   delete, forward, mark as read, read marks, reactions, profiles and groups; live events.
5. **Media**: profile photos, photos, voice, video, round video, files (TDLib downloads files itself;
   the backend waits for each and hands over the bytes or the file, so screens stay as they are);
   sending all of them.
6. **Stickers**: still pictures (WebP), packs, favourites, sending. Animated ones are the open
   question: our server's module converts them on the PC; on the phone Lottie (TGS) can be drawn by a
   library, video stickers (WebM with transparency) are uncertain on Android 10. Until then: still
   pictures in Direct mode.
7. **Proxy settings screen**, then the embedded tg-ws-proxy.
8. **Release 2.0.**

## Spike results (step 2, 2026-11-13)
- The Kyocera is 32-bit ARM only (`armeabi-v7a`), 862 MB of RAM (a low-RAM device), 597 MB free in phone
  memory, plus a 29 GB SD card (10 GB free) mounted as removable ("portable") storage.
- TDLib has no official Android download, so it is built from source: `android/tdlib/build.sh`, run in
  WSL (Debian), pinned to TDLib 1.8.70 (commit c8828d5), NDK 23.2, OpenSSL 1.1.1w, armeabi-v7a only,
  Java interface, API 24, everything linked statically. The first build took about 45 minutes; the
  result (`android/tdlib/libs`, `android/tdlib/java`) is git-ignored.
- Only dev builds carry it for now (`sourceSets` "debug" in app/build.gradle.kts), with a test screen
  (`app/src/debug/.../TdlibSpikeActivity.kt`, Under the hood > "🧪 TDLib test (dev builds)"): api_id,
  api_hash, a `tg://proxy` / `tg://socks` link (also handed over with `adb shell am start ... --es`),
  start/stop, authorization and connection state, proxy ping, memory, storage. It never logs in.
- **Works**: TDLib loaded on the Kyocera and reached "connection ready" and "waiting for a phone number"
  through the owner's fake-TLS MTProto proxy (secret starting with `ee`).
- **Size**: `libtdjni.so` is 16 MB, stored uncompressed in the APK and used from there (minSdk 24 means
  no unpacked copy in phone memory). TdApi.java (5.5 MB of source) becomes a few MB of code. Expected
  release APK with TDLib: about 22-23 MB (1.9.0 is 4.9 MB), well under the owner's 60 MB line, so
  installing on the SD card is not needed (Android 10 only allows it on cards set up as phone memory).
- **Memory** while connected and idle (dev build): about 92 MB in use in total, of which TDLib's own
  memory (native heap) 14 MB and its library about 8 MB; the dev build's unshrunk code is 23 MB of it and
  shrinks in a release build. The phone had about 250 MB available meanwhile.
- **Storage** before any login: database 360 KB (phone memory), files nothing yet (SD card).
- Lesson: anything slow (here `Debug.getMemoryInfo`) must stay off the screen's thread; doing it every
  second there made Android show "not responding". TDLib itself runs on its own thread; Direct mode's
  screens get only finished results, like from the server today.

## Storage (owner, 2026-11-13)
- TDLib's **database** (chats, messages, settings; small, written all the time) stays in phone memory:
  a removed card must not damage it.
- Its **files** (photos, videos, voice, stickers, documents: the big part) go where the user chooses:
  phone or SD card (the app's own folder on the card, `getExternalFilesDirs`, no permission needed).
  A missing card falls back to phone memory with a note. The same choice can later hold Server mode's
  media cache too.
- TDLib's own file cache limits (`optimizeStorage`) follow the app's existing cache settings.
- Installing the app itself on the SD card: only if the app ever grows past about 60 MB (not expected).

## Not available in Direct mode (by nature)
Modules, Maintenance mode, the server cache, shared servers and admins, Live logs, local favourites
kept on the server (Direct mode would keep its own on the phone), Mockgram demo accounts.

## Risks and things to check
- Weak phone: TDLib is the C++ library the official Telegram X app uses, so it should be fine; its
  database lives on the phone.
- The Telegram login lives on the phone instead of the PC.
- api_id/api_hash: users enter their own; never ship the owner's in a public APK.
- Testing: the login needs the owner's phone number and code, so Direct mode is tested by the owner
  on the phone; step 1 can be checked fully on the PC.
