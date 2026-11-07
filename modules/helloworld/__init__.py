"""Hello world: the demo module. It uses the main parts of module API 1 once each, as a working
example for writing modules (docs/modules-plan.md)."""


def setup(context):
    # A capability other code (and later the apps) can ask for, instead of this module's name
    context.provide("helloworld", 1)

    # GET /modules/helloworld/ping, with the app token like every endpoint
    @context.router.get("/ping")
    async def ping():
        return {"pong": True, "module": context.version, "account": context.account().name}

    # The fallback text: every app that doesn't know "helloworld" sees it in Messages from the server
    @context.notice("helloworld")
    def hello_text():
        return f"Hello, world! The Hello world module {context.version} is installed on this server."

    # Runs once the server is connected to Telegram
    @context.on_start
    async def started():
        context.log("Hello, world! The module started.")
