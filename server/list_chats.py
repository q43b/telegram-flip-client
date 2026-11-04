import os
from dotenv import load_dotenv
from telethon import TelegramClient, connection

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

async def main():
    me = await client.get_me()
    print(f"Logged in as: {me.first_name}")

    print("\nLast 10 chats:")
    async for dialog in client.iter_dialogs(limit=10):
        print(f"- {dialog.name}")

with client:
    client.loop.run_until_complete(main())