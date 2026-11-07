"""The fallback channel's notices (GET /server_messages): every kind of information the
server tells apps registers its text here, for the apps that don't know that kind yet.
"""


# Messages from the server: a fallback, so a newer server can still tell an older app what it
# needs to know (since 0.14.0-beta). Every kind of information the server tells apps is a
# notice, registered with @notice(kind): a function giving its text now (None = nothing to say).
# Apps list the kinds they can receive in their own form in x-app-knows; /server_messages sends
# the text of every other notice. So a kind gets its own endpoint or event for apps that know
# it, and is registered here too, for the apps that don't yet.
#
# Admin notices (since 1.0.0, @notice(kind, admin=True)) only ever reach admin accounts:
# apps that know "admin_messages" get them from GET /admin_messages (and show them right away),
# older apps in /server_messages like any other notice.
notices = []
admin_kinds = set()


def notice(kind, admin=False):
    def register(text_of):
        notices.append((kind, text_of))
        if admin:
            admin_kinds.add(kind)
        return text_of
    return register
