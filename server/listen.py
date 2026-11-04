import os
from dotenv import load_dotenv
from telethon import TelegramClient, connection, events

load_dotenv()

client = TelegramClient(
    "tg_session",
    int(os.environ["API_ID"]),
    os.environ["API_HASH"],
    connection=connection.ConnectionTcpMTProxyRandomizedIntermediate,
    proxy=(
        os.environ["PROXY_HOST"],
        int(os.environ["PROXY_PORT"]),
        os.environ["PROXY_SECRET"],
    ),
)

@client.on(events.NewMessage)
async def on_message(event):
    sender = await event.get_sender()
    name = getattr(sender, "first_name", None) or "Unknown"
    direction = "->" if event.out else "<-"
    print(f"{direction} {name}: {event.raw_text}")

print("Listening... press Ctrl+C to stop")
client.start()
client.run_until_disconnected()