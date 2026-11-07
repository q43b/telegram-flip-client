"""The JSON bodies of the POST endpoints.
"""
from typing import Optional
from pydantic import BaseModel


class SendBody(BaseModel):
    chat_id: int
    text: str
    topic_id: Optional[int] = None
    reply_to_id: Optional[int] = None


class EditBody(BaseModel):
    chat_id: int
    message_id: int
    text: str


class DeleteBody(BaseModel):
    chat_id: int
    message_id: int
    revoke: bool  # True = for everyone


class ForwardBody(BaseModel):
    from_chat_id: int
    message_id: int
    to_chat_id: int


class SendStickerBody(BaseModel):
    chat_id: int
    sticker_id: int
    topic_id: Optional[int] = None
    reply_to_id: Optional[int] = None


class ReactBody(BaseModel):
    chat_id: int
    message_id: int
    emoji: Optional[str] = None  # None or "" = take my reaction back
