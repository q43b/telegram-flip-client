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
2. **Spike**: TDLib for Android (prebuilt or built with the NDK; the Kyocera's CPU architecture),
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
