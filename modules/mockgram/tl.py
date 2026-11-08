"""Building Telegram's objects (Telethon's classes) without spelling out every field.

Telegram's objects have many required fields the server never reads (a Config has 31). `blank`
fills them with neutral values from the class's own type hints, so the backend only writes what
matters. A field whose type is another object needs an entry in OBJECT_DEFAULTS: a clear error
names the missing one, so adding it is a one-liner.
"""
import datetime
import inspect
import typing

from telethon import types

NOW = datetime.datetime.now(datetime.timezone.utc)

OBJECT_DEFAULTS = {
    "TypePeerNotifySettings": lambda: types.PeerNotifySettings(),
    "TypeChatPhoto": lambda: types.ChatPhotoEmpty(),
    "TypeUserProfilePhoto": lambda: types.UserProfilePhotoEmpty(),
    "TypeChatAdminRights": lambda: types.ChatAdminRights(),
    "TypeDocument": lambda: types.DocumentEmpty(id=0),
    "TypeChatReactions": lambda: types.ChatReactionsAll(),
    "TypeUpdatesState": lambda: types.updates.State(pts=1, qts=0, date=NOW, seq=1, unread_count=0),
}


def _scalar(annotation):
    """A neutral value for a plain-typed field, or the marker _UNKNOWN."""
    text = str(annotation)
    if annotation is bool:
        return False
    if annotation is int:
        return 0
    if annotation is float:
        return 0.0
    if annotation is str:
        return ""
    if annotation is bytes:
        return b""
    if "datetime" in text:
        return NOW
    if typing.get_origin(annotation) is list or text.startswith(("list[", "typing.List", "List[")):
        return []
    return _UNKNOWN


_UNKNOWN = object()


def blank(cls, **given):
    """cls(**given) with every other required field filled in neutrally."""
    arguments = dict(given)
    for name, parameter in list(inspect.signature(cls.__init__).parameters.items())[1:]:
        if name in arguments or parameter.default is not inspect.Parameter.empty:
            continue
        value = _scalar(parameter.annotation)
        if value is _UNKNOWN:
            factory = OBJECT_DEFAULTS.get(str(parameter.annotation).replace("'", ""))
            if factory is None:
                raise TypeError(f"mockgram: {cls.__name__}.{name} ({parameter.annotation}) needs a value or an OBJECT_DEFAULTS entry")
            value = factory()
        arguments[name] = value
    return cls(**arguments)


def utf16_length(text):
    """Telegram counts text in UTF-16 units (an emoji above U+FFFF is two)."""
    return len(text.encode("utf-16-le")) // 2
