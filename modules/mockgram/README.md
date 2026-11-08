# Mockgram

A made-up Telegram for demo accounts, so screenshots show invented chats instead of real ones.

Use: start Maintenance mode, `a) Add an account`, give it a name, type `mockgram` as the kind and
`{"scene": "showcase"}` as the settings (Enter for the default). It prints a token once; put it in the
app (or the emulator) like any other. Nothing touches Telegram or a real session.

What you get: 10 chats (private, group, forum with topics, channel, bot, Saved Messages), photos,
stickers, custom emoji, reactions, calls, and a Russian chat. Everything works: send, reply, edit, delete,
forward, react, mark as read. After the app opens the chat list, people also write on their own
(see `live` in `scenes/showcase.json`), and Mia answers a few seconds after you write to her.

Your own scene: copy `scenes/showcase.json` to `module_data/mockgram/scenes/<name>.json` next to the
server's .env, change it, and use `{"scene": "<name>"}`. Times are relative ("ago": "2h30m"), so the
chats always look fresh. A mistake in the scene is named at startup.
